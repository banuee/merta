package dev.merta.app.data.tools

import dev.merta.app.adb.ShizukuCommand
import dev.merta.app.adb.ShizukuOps
import dev.merta.app.adb.ShizukuOpsImpl
import dev.merta.app.data.workspace.FileGateway
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Выполнение инструментов. Чистые эвристики (поиск файлов, чтение кусками) —
 * в companion, покрыты JVM-тестами. Сам запуск — через FileGateway.
 * Привилегированные операции (install/tap/run_command): сначала Shizuku-shell
 * телефона через демона ([daemonShell], rish — постоянный доступ, грант
 * Shizuku приложению не нужен), без него — [ShizukuOps]/локально.
 */
class ToolRegistry(
    private val gateway: FileGateway,
    private val defaultWorkdir: String,
    /** Авто-разрешение деструктивных инструментов без диалога. */
    private val autoApprove: Boolean = false,
    /** Привилегированные операции (null — Shizuku недоступен). */
    private val shizuku: ShizukuOps? = null,
    /** Shell телефона через демона (null — демон недоступен). */
    private val daemonShell: (suspend (command: String, timeoutS: Int) -> DaemonShellResult)? = null,
) {

    /** Результат Shizuku-shell через демона (rish). */
    data class DaemonShellResult(val code: Int, val output: String)

    suspend fun execute(
        call: ToolCall,
        requestApproval: suspend (PendingApproval) -> Boolean,
    ): String {
        val def = ToolDefs.byName(call.name) ?: return "error: неизвестный инструмент «${call.name}»"
        if (def.needsApproval && !autoApprove) {
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
                ToolDefs.INSTALL_APK -> installApk(call.args("path"))
                ToolDefs.LIST_PACKAGES -> listPackages(call.args("filter"))
                ToolDefs.TAP_SCREEN -> tapScreen(call.args("x"), call.args("y"))
                ToolDefs.SWIPE_SCREEN -> swipeScreen(
                    call.args("x1"), call.args("y1"), call.args("x2"), call.args("y2"),
                )
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
     * Резолвинг пути: пустой (для папок) и относительный — от рабочей папки
     * (первой папки пользователя). Папки нет — относительный остаётся
     * относительным, gateway ответит подсказкой про Параметры.
     * Модели любят писать `hello.txt` вместо абсолюта — не роняем, а чиним.
     */
    fun resolvePath(path: String): String {
        val p = path.trim()
        if (p.isEmpty() || p.startsWith("content://") || p.startsWith("/")) return p
        if (defaultWorkdir.isBlank()) return p
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
        // Скоуп — один раз: deny-паттерны (.git/, *.env и т.п.) проверяем
        // на КАЖДОМ файле/папке, иначе поиск светит запрещённое модели.
        val scope = gateway.currentScope()
        fun denied(path: String): Boolean =
            dev.merta.app.data.workspace.GatewayRules.checkAccess(scope, path, false) != null
        val hits = mutableListOf<String>()
        var scanned = 0
        File(base).walkTopDown()
            .onEnter { dir -> !denied(dir.absolutePath) }
            .forEach { f ->
                // Лимиты останавливают обход, а не пропускают файлы.
                if (hits.size >= MAX_HITS || scanned >= MAX_SCAN) {
                    return hits.joinToString("\n").ifBlank { "(совпадений нет)" }
                }
                if (!f.isFile || f.length() > MAX_GREP_FILE) return@forEach
                if (denied(f.absolutePath)) return@forEach
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
        // Быстрая стена: абсолютные пути команды — только внутри скоупа
        // (проверяется сама папка, workdir уже проверен выше).
        val roots = gateway.currentScope().allowedRoots.filter { it.startsWith("/") }
        val bad = outsideScopePaths(command, roots)
        if (bad.isNotEmpty()) {
            return "error: пути вне разрешённых папок: ${bad.take(5).joinToString(", ")} — " +
                "добавь папку в Параметрах или убери путь"
        }
        val shell = daemonShell
        if (shell != null) {
            // Shell телефона (права ADB/Shizuku): видит /sdcard, не видит приватные файлы приложения.
            return try {
                val r = shell(cdWrap(dir, command), 60)
                if (r.code != 0) "error: [exit ${r.code}]\n${r.output.take(MAX_OUTPUT)}".trimEnd()
                else "[exit 0]\n${r.output.take(MAX_OUTPUT)}".trimEnd().ifBlank { "[exit 0]" }
            } catch (e: Exception) {
                "error: демон недоступен: ${e.message} — подними в Termux: ~/bin/merta-agy"
            }
        }
        val dirFile = File(dir)
        if (!dirFile.isDirectory) return "error: нет папки: $dir"
        return withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(dirFile)
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(60, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext "error: таймаут 60с"
                }
                val out = proc.inputStream.bufferedReader().readText().take(MAX_OUTPUT)
                "[exit ${proc.exitValue()}]\n$out".trimEnd()
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }
    }

    /** Привилегированная команда: демон (rish) → Shizuku → ошибка с подсказкой. */
    private suspend fun privCmd(command: ShizukuCommand, args: Map<String, String>): String {
        val cmd = daemonCmd(command, args)
            ?: return "error: недопустимые аргументы для ${command.name}"
        val shell = daemonShell
        if (shell != null) {
            return try {
                val r = shell(cmd, 60)
                if (r.code != 0) {
                    "error: ${command.name.lowercase()}: ${r.output.take(500).ifBlank { "exit ${r.code}" }}"
                } else {
                    mapPrivOk(command, r.output)
                }
            } catch (e: Exception) {
                "error: демон недоступен: ${e.message} — подними в Termux: ~/bin/merta-agy"
            }
        }
        val ops = shizuku ?: return "error: shizuku недоступен — подними демона (~/bin/merta-agy) или запусти Shizuku"
        val r = ops.run(command, args)
        if (r.exitCode != 0) {
            return "error: ${command.name.lowercase()}: ${r.stderr.ifBlank { r.stdout }.take(500)}"
        }
        return mapPrivOk(command, r.stdout)
    }

    private fun mapPrivOk(command: ShizukuCommand, stdout: String): String = when (command) {
        ShizukuCommand.INSTALL_APK ->
            if ("Success" in stdout) "ok: пакет установлен" else "pm install: ${stdout.take(500)}"
        ShizukuCommand.LIST_PACKAGES -> stdout.ifBlank { "(пусто)" }.take(MAX_OUTPUT)
        ShizukuCommand.TAP -> "ok: тап выполнен"
        ShizukuCommand.SWIPE -> "ok: свайп выполнен"
        else -> stdout.take(500)
    }

    private suspend fun installApk(path: String): String {
        val ops = shizuku
        val shell = daemonShell
        if (shell == null && ops == null) {
            return "error: shizuku недоступен — подними демона (~/bin/merta-agy) или запусти Shizuku"
        }
        return privCmd(ShizukuCommand.INSTALL_APK, mapOf("apk" to path))
    }

    private suspend fun listPackages(filter: String): String {
        if (daemonShell == null && shizuku == null) {
            return "error: shizuku недоступен — подними демона (~/bin/merta-agy) или запусти Shizuku"
        }
        return privCmd(ShizukuCommand.LIST_PACKAGES, mapOf("filter" to filter))
    }

    private suspend fun tapScreen(x: String, y: String): String {
        if (daemonShell == null && shizuku == null) {
            return "error: shizuku недоступен — подними демона (~/bin/merta-agy) или запусти Shizuku"
        }
        return privCmd(ShizukuCommand.TAP, mapOf("x" to x, "y" to y))
    }

    private suspend fun swipeScreen(x1: String, y1: String, x2: String, y2: String): String {
        if (daemonShell == null && shizuku == null) {
            return "error: shizuku недоступен — подними демона (~/bin/merta-agy) или запусти Shizuku"
        }
        return privCmd(
            ShizukuCommand.SWIPE,
            mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2),
        )
    }

    companion object {
        const val MAX_READ = 100 * 1024L
        const val MAX_LIST = 300
        const val MAX_HITS = 100
        const val MAX_SCAN = 500
        const val MAX_GREP_FILE = 1024 * 1024L
        const val MAX_OUTPUT = 20 * 1024

        fun summary(call: ToolCall): String = when (call.name) {
            ToolDefs.READ_FILE -> "Читать ${call.arguments["path"].orEmpty()}"
            ToolDefs.LIST_DIR -> "Список ${(call.arguments["path"] ?: "").ifBlank { "рабочая" }}"
            ToolDefs.GREP_SEARCH -> "Поиск «${call.arguments["pattern"].orEmpty()}» в " +
                (call.arguments["root"] ?: "").ifBlank { "рабочая" }
            ToolDefs.WRITE_FILE -> "Записать ${call.arguments["path"].orEmpty()}"
            ToolDefs.RUN_COMMAND -> "Выполнить: ${call.arguments["command"].orEmpty()}"
            ToolDefs.INSTALL_APK -> "Установить APK ${call.arguments["path"].orEmpty()}"
            ToolDefs.LIST_PACKAGES -> "Пакеты: ${(call.arguments["filter"] ?: "").ifBlank { "все" }}"
            ToolDefs.TAP_SCREEN -> "Тап ${call.arguments["x"].orEmpty()},${call.arguments["y"].orEmpty()}"
            ToolDefs.SWIPE_SCREEN -> "Свайп ${call.arguments["x1"].orEmpty()},${call.arguments["y1"].orEmpty()} → ${call.arguments["x2"].orEmpty()},${call.arguments["y2"].orEmpty()}"
            else -> call.name
        }

        fun preview(call: ToolCall): String = when (call.name) {
            ToolDefs.WRITE_FILE -> (call.arguments["content"] ?: "").take(600)
            ToolDefs.RUN_COMMAND -> "cwd: ${(call.arguments["workdir"] ?: "").ifBlank { "(workspace)" }}"
            ToolDefs.INSTALL_APK -> "apk: ${(call.arguments["path"] ?: "")}"
            ToolDefs.TAP_SCREEN -> "x=${call.arguments["x"]}, y=${call.arguments["y"]}"
            ToolDefs.SWIPE_SCREEN -> "от ${call.arguments["x1"]},${call.arguments["y1"]}"
            else -> ""
        }

        /** Эвристика бинарника: нулевой байт в первых 4К. */
        fun isBinarySample(bytes: ByteArray): Boolean = bytes.any { it == 0.toByte() }

        /** POSIX-цитирование одного аргумента для sh -c (rish). */
        fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /** Системные префиксы: бинарники и данные ОС вне скоупа трогать можно. */
        private val SYSTEM_PREFIXES = listOf(
            "/system/", "/vendor/", "/apex/", "/product/", "/odm/",
            "/system_ext/", "/proc/", "/dev/",
        )

        /**
         * Абсолютные пути команды вне скоупа и системных префиксов.
         * Чистый — покрыт JVM-тестами.
         */
        fun outsideScopePaths(command: String, roots: List<String>): List<String> {
            val toks = command.split(Regex("""[\s'"`;|&()$]+""")).filter { it.startsWith("/") }
            return toks.filter { t ->
                val norm = t.trimEnd('/')
                if (SYSTEM_PREFIXES.any { norm.startsWith(it) }) return@filter false
                roots.none { r ->
                    val rr = r.trimEnd('/')
                    norm == rr || norm.startsWith(rr + "/")
                }
            }.distinct()
        }

        /** Команда с заходом в рабочую папку (rish стартует где попало). */
        fun cdWrap(dir: String, command: String): String =
            if (dir.isBlank()) command else "cd ${shQuote(dir)} && $command"

        /**
         * Та же валидация, что ShizukuOpsImpl.buildArgv, но строкой для rish.
         * null — аргументы не прошли (модель получит честную ошибку).
         */
        fun daemonCmd(command: ShizukuCommand, args: Map<String, String>): String? {
            return when (command) {
                ShizukuCommand.INSTALL_APK -> {
                    val apk = args["apk"]?.trim().orEmpty()
                    if (apk.isBlank() || !apk.endsWith(".apk")) return null
                    if (!apk.startsWith("/sdcard/") && !apk.startsWith("/storage/")) return null
                    if (".." in apk || '\n' in apk || ';' in apk) return null
                    "pm install -r -d ${shQuote(apk)}"
                }
                ShizukuCommand.LIST_PACKAGES -> {
                    val f = args["filter"]?.trim().orEmpty()
                    if (f.isEmpty()) {
                        "pm list packages"
                    } else {
                        if (f.any { it <= ' ' } || ";" in f || "|" in f || "&" in f) return null
                        "pm list packages ${shQuote(f)}"
                    }
                }
                ShizukuCommand.TAP -> {
                    val x = args["x"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    val y = args["y"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    "input tap $x $y"
                }
                ShizukuCommand.SWIPE -> {
                    val p = listOf("x1", "y1", "x2", "y2").map {
                        args[it]?.trim()?.toIntOrNull()?.takeIf { v -> v >= 0 } ?: return null
                    }
                    "input swipe ${p[0]} ${p[1]} ${p[2]} ${p[3]} 300"
                }
                else -> null
            }
        }
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
