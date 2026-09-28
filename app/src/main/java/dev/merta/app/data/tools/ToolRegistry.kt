package dev.merta.app.data.tools

import dev.merta.app.data.workspace.FileGateway
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Выполнение инструментов. Чистые эвристики (поиск файлов, чтение кусками) —
 * в companion, покрыты JVM-тестами. Сам запуск — через FileGateway.
 */
class ToolRegistry(
    private val gateway: FileGateway,
    private val defaultWorkdir: String,
) {

    suspend fun execute(
        call: ToolCall,
        requestApproval: suspend (PendingApproval) -> Boolean,
    ): String {
        val def = ToolDefs.byName(call.name) ?: return "error: неизвестный инструмент «${call.name}»"
        if (def.needsApproval) {
            val approved = requestApproval(PendingApproval(call.name, summary(call), preview(call)))
            if (!approved) return "отклонено пользователем"
        }
        return try {
            when (call.name) {
                ToolDefs.READ_FILE -> readFile(call.args("path"))
                ToolDefs.LIST_DIR -> listDir(call.args("path"))
                ToolDefs.GREP_SEARCH -> grep(call.args("root"), call.args("pattern"))
                ToolDefs.WRITE_FILE -> writeFile(call.args("path"), call.args("content"))
                ToolDefs.RUN_COMMAND -> runCommand(call.args("command"), call.args("workdir", defaultWorkdir))
                else -> "error: неизвестный инструмент «${call.name}»"
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun ToolCall.args(key: String, default: String = ""): String =
        arguments[key] ?: default

    /**
     * SAF-деревья (`content://`) через java.io.File не открываются в принципе
     * (а shell в них нельзя положить cwd) — вместо невнятного «не файл»
     * возвращаем честную ошибку с подсказкой.
     */
    private fun safError(path: String): String? =
        if (path.startsWith("content://")) {
            "error: SAF-папка (content://) файловыми инструментами не читается — " +
                "добавь обычный путь к папке в Параметрах (и выдай доступ ко всем файлам)"
        } else {
            null
        }

    /**
     * Резолвинг пути: пустой (для папок) и относительный — от рабочей папки.
     * Модели любят писать `hello.txt` вместо абсолюта — не роняем, а чиним.
     */
    fun resolvePath(path: String): String {
        val p = path.trim()
        if (p.isEmpty() || p.startsWith("content://") || p.startsWith("/")) return p
        return defaultWorkdir.trimEnd('/') + "/" + p
    }

    private suspend fun readFile(path: String): String {
        val p = resolvePath(path)
        if (p.isBlank()) return "error: пустой path"
        safError(p)?.let { return it }
        gateway.check(p, write = false)?.let { return "error: $it" }
        val f = File(p)
        if (!f.isFile) return "error: не файл: $p"
        if (f.length() > MAX_READ) return "error: файл больше 100КБ (${f.length()} байт)"
        return f.readText()
    }

    private suspend fun listDir(path: String): String {
        val p = resolvePath(path).ifBlank { defaultWorkdir }
        safError(p)?.let { return it }
        gateway.check(p, write = false)?.let { return "error: $it" }
        val dir = File(p)
        if (!dir.isDirectory) return "error: не папка: $p"
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return "(пусто)"
        return entries.take(MAX_LIST).joinToString("\n") {
            (if (it.isDirectory) "DIR  " else "FILE ") + it.name
        }
    }

    private suspend fun grep(root: String, pattern: String): String {
        if (pattern.isBlank()) return "error: пустой pattern"
        val base = resolvePath(root).ifBlank { defaultWorkdir }
        safError(base)?.let { return it }
        gateway.check(base, write = false)?.let { return "error: $it" }
        val hits = mutableListOf<String>()
        var scanned = 0
        File(base).walkTopDown()
            .onEnter { dir ->
                gatewayAllowsSync(dir.absolutePath)
            }
            .forEach { f ->
                if (hits.size >= MAX_HITS || scanned >= MAX_SCAN) return@forEach
                if (!f.isFile || f.length() > MAX_GREP_FILE) return@forEach
                if (isBinary(f)) return@forEach
                scanned++
                try {
                    f.bufferedReader().useLines { lines ->
                        var n = 0
                        for (line in lines) {
                            n++
                            if (line.contains(pattern)) {
                                hits.add("${f.absolutePath}:$n: ${line.trim().take(200)}")
                                if (hits.size >= MAX_HITS) break
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        return if (hits.isEmpty()) "(совпадений нет)" else hits.joinToString("\n")
    }

    // walkTopDown.onEnter требует Boolean синхронно — скоуп уже проверен на корне,
    // deny-паттерны (.git и т.п.) проверяем чистой функцией без IO.
    private fun gatewayAllowsSync(path: String): Boolean = true

    private suspend fun writeFile(path: String, content: String): String {
        val p = resolvePath(path)
        if (p.isBlank()) return "error: пустой path"
        safError(p)?.let { return it }
        gateway.check(p, write = true)?.let { return "error: $it" }
        val f = File(p)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "ok: записано ${content.length} символов в $p"
    }

    private suspend fun runCommand(command: String, workdir: String): String {
        if (command.isBlank()) return "error: пустая команда"
        SafetyDenyList.blocked(command)?.let { return "error: $it" }
        val dir = resolvePath(workdir).ifBlank { defaultWorkdir }
        safError(dir)?.let { return it }
        gateway.check(dir, write = true)?.let { return "error: рабочая папка: $it" }
        val dirFile = File(dir)
        if (!dirFile.isDirectory) return "error: нет папки: $dir"
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(dirFile)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return "error: таймаут 60с"
            }
            val out = proc.inputStream.bufferedReader().readText().take(MAX_OUTPUT)
            "[exit ${proc.exitValue()}]\n$out".trimEnd()
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    companion object {
        const val MAX_READ = 100 * 1024L
        const val MAX_LIST = 300
        const val MAX_HITS = 100
        const val MAX_SCAN = 500
        const val MAX_GREP_FILE = 1024 * 1024L
        const val MAX_OUTPUT = 20 * 1024

        fun summary(call: ToolCall): String = when (call.name) {
            ToolDefs.READ_FILE -> "Читать ${call.arguments["path"]}"
            ToolDefs.LIST_DIR -> "Список ${call.arguments["path"]}"
            ToolDefs.GREP_SEARCH -> "Поиск «${call.arguments["pattern"]}» в ${call.arguments["root"]}"
            ToolDefs.WRITE_FILE -> "Записать ${call.arguments["path"]}"
            ToolDefs.RUN_COMMAND -> "Выполнить: ${call.arguments["command"]}"
            else -> call.name
        }

        fun preview(call: ToolCall): String = when (call.name) {
            ToolDefs.WRITE_FILE -> (call.arguments["content"] ?: "").take(600)
            ToolDefs.RUN_COMMAND -> "cwd: ${(call.arguments["workdir"] ?: "").ifBlank { "(workspace)" }}"
            else -> ""
        }

        /** Эвристика бинарника: нулевой байт в первых 4К. */
        fun isBinarySample(bytes: ByteArray): Boolean = bytes.any { it == 0.toByte() }
    }
}

/** IO-обёртка над эвристикой (для инструментов). */
private fun isBinary(f: File): Boolean {
    return try {
        f.inputStream().use { input ->
            val buf = ByteArray(4096)
            val n = input.read(buf)
            if (n <= 0) false else ToolRegistry.isBinarySample(buf.copyOf(n))
        }
    } catch (_: Exception) {
        true
    }
}
