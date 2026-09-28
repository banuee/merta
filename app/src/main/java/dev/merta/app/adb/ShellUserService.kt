package dev.merta.app.adb

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * UserService: выполняется в отдельном процессе под shell (UID 2000).
 * Контекст здесь урезанный — только запуск процессов, никакого UI/провайдеров.
 */
class ShellUserService : IShellService.Stub {

    constructor() : super()

    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun runShell(argv: Array<String>): String {
        return try {
            val proc = ProcessBuilder(*argv)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return "-1\nтаймаут 120с"
            }
            val out = proc.inputStream.bufferedReader().readText().take(MAX_OUT)
            "${proc.exitValue()}\n$out"
        } catch (e: Exception) {
            "-1\nshell: ${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    companion object {
        const val MAX_OUT = 20 * 1024
    }
}
