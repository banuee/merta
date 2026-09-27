package dev.merta.app.data.agent

import android.content.Context
import java.io.File

/**
 * Дом агента: приватная папка приложения `filesDir/merta/`:
 * ```
 * merta/
 *   system.md     — системный промт (правится в Параметрах → Агент)
 *   skills/       — по папке на скилл, внутри SKILL.md (YAML frontmatter + инструкции)
 *   mcp.json      — MCP-серверы (пока список; подключение — отдельная фаза)
 *   chats/        — история чатов (SessionStore)
 *   workspace/    — рабочая папка по умолчанию (скоуп FileGateway)
 * ```
 * Файлы сюда закидываются через SAF-импорт (фаза инструментов) или `adb push`.
 */
class AgentFiles(context: Context) {

    val root: File = File(context.filesDir, "merta")
    val systemFile: File = File(root, "system.md")
    val skillsDir: File = File(root, "skills")
    val mcpFile: File = File(root, "mcp.json")
    val chatsDir: File = File(root, "chats")
    val workspaceDir: File = File(root, "workspace")

    data class SkillInfo(val name: String, val description: String, val dirName: String)
    data class AgentStatus(
        val systemPromptChars: Int,
        val skills: List<SkillInfo>,
        val mcpServers: List<String>,
    )

    init {
        workspaceDir.mkdirs()
        chatsDir.mkdirs()
        skillsDir.mkdirs()
        if (!systemFile.exists()) systemFile.writeText(DEFAULT_SYSTEM)
        if (!mcpFile.exists()) mcpFile.writeText(DEFAULT_MCP)
    }

    fun loadSystemPrompt(): String {
        return try {
            val text = systemFile.readText()
            when {
                text.isBlank() -> DEFAULT_SYSTEM
                // Миграции со старых дефолтов (без актуальной доки по инструментам).
                text.trim() == OLD_DEFAULT_SYSTEM.trim() ||
                    text.contains("Путь — абсолютный внутри") -> {
                    systemFile.writeText(DEFAULT_SYSTEM)
                    DEFAULT_SYSTEM
                }
                else -> text
            }
        } catch (_: Exception) {
            DEFAULT_SYSTEM
        }
    }

    fun saveSystemPrompt(text: String) {
        systemFile.writeText(text)
    }

    /** Сканирует SKILL.md в подпапках skills, тянет name/description из frontmatter. */
    fun listSkills(): List<SkillInfo> {
        val dirs = try {
            skillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return dirs.mapNotNull { dir ->
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.exists()) return@mapNotNull null
            val (name, desc) = try {
                parseFrontmatter(skillFile.readText())
            } catch (_: Exception) {
                return@mapNotNull null
            }
            SkillInfo(name.ifBlank { dir.name }, desc, dir.name)
        }.sortedBy { it.name }
    }

    /** Имена MCP-серверов из mcp.json (ключи верхнего уровня / mcpServers). Без подключения. */
    fun listMcpServers(): List<String> {
        val raw = try {
            mcpFile.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        // Плоский поиск ключей объектов верхнего уровня: {"name": {...}, ...}
        val names = mutableListOf<String>()
        var i = raw.indexOf('{')
        if (i < 0) return emptyList()
        var depth = 0
        var inStr = false
        var keyStart = -1
        var key = ""
        var j = i
        while (j < raw.length) {
            val c = raw[j]
            if (inStr) {
                if (c == '\\') {
                    j += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> {
                        inStr = true
                        if (depth == 1) keyStart = j + 1
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                    ':' -> if (depth == 1 && keyStart >= 0) {
                        key = raw.substring(keyStart, j - 1).trim().trim('"')
                        if (key != "mcpServers") names.add(key)
                        keyStart = -1
                    }
                }
            }
            j++
        }
        // Вложенный формат {"mcpServers": {...}} — раскрыть один уровень.
        if (names.isEmpty() && raw.contains("\"mcpServers\"")) {
            val inner = raw.substringAfter("\"mcpServers\"")
            val sub = matchBraces(inner, inner.indexOf('{')) ?: return emptyList()
            return listMcpServersFlat(sub)
        }
        return names
    }

    private fun listMcpServersFlat(obj: String): List<String> {
        val names = mutableListOf<String>()
        var i = 0
        while (i < obj.length) {
            if (obj[i] == '"') {
                val end = obj.indexOf('"', i + 1)
                if (end < 0) break
                var k = end + 1
                while (k < obj.length && obj[k].isWhitespace()) k++
                if (k < obj.length && obj[k] == ':') names.add(obj.substring(i + 1, end))
                i = end + 1
            } else {
                i++
            }
        }
        return names.filter { it != "mcpServers" }
    }

    private fun matchBraces(s: String, openIdx: Int): String? {
        if (openIdx < 0) return null
        var depth = 0
        var inStr = false
        var i = openIdx
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(openIdx, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    fun status(): AgentStatus = AgentStatus(
        systemPromptChars = loadSystemPrompt().length,
        skills = listSkills(),
        mcpServers = listMcpServers(),
    )

    companion object {
        const val OLD_DEFAULT_SYSTEM =
            "Ты — Merta, агент-помощник по разработке на Android. " +
                "Отвечай кратко и по делу, код давай готовыми блоками. " +
                "Файлы правишь только внутри разрешённого рабочего пространства."

        const val DEFAULT_SYSTEM =
            "Ты — Merta, агент-помощник по разработке на Android. " +
                "Отвечай кратко и по делу, код давай готовыми блоками.\n\n" +
                "ТВОИ ИНСТРУМЕНТЫ (вызываются как function tools в этом же запросе):\n" +
                "- read_file {\"path\"} — прочитать текстовый файл (до 100 КБ). Путь абсолютный.\n" +
                "- list_dir {\"path\"} — список файлов и папок.\n" +
                "- grep_search {\"root\", \"pattern\"} — поиск подстроки по текстовым файлам.\n" +
                "- write_file {\"path\", \"content\"} — создать/перезаписать файл ЦЕЛИКОМ. Спросит подтверждение у пользователя, придёт следующим ходом — вызывай смело.\n" +
                "- run_command {\"command\", \"workdir\"?} — shell (sh -c, 60с). Тоже с подтверждением. Запрещены rm -rf /, mkfs, dd, форк-бомбы.\n\n" +
                "ПРАВИЛА:\n" +
                "- Работай только внутри разрешённых папок (workspace); наружу — нельзя, инструмент вернёт error.\n" +
                "- Не выдумывай содержимое файлов — сначала read_file/list_dir/grep_search.\n" +
                "- Сначала читай и разбирайся, потом правь; после правок проверяй результат чтением.\n" +
                "- Команды запускай с рабочей папкой внутри workspace."

        const val DEFAULT_MCP = "{\n  \"mcpServers\": {}\n}\n"

        /** name/description из YAML frontmatter SKILL.md (без полноценного YAML-парсера). */
        fun parseFrontmatter(content: String): Pair<String, String> {
            if (!content.startsWith("---")) return "" to ""
            val end = content.indexOf("\n---", 3)
            if (end < 0) return "" to ""
            var name = ""
            var desc = ""
            for (line in content.substring(3, end).lines()) {
                val t = line.trim()
                when {
                    t.startsWith("name:") -> name = t.removePrefix("name:").trim().trim('"', '\'')
                    t.startsWith("description:") -> desc = t.removePrefix("description:").trim().trim('"', '\'')
                }
            }
            return name to desc
        }
    }
}
