package dev.merta.app.adb

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.merta.app.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

/**
 * Живая реализация [ShizukuOps] через Shizuku UserService (shell UID 2000).
 * Команды — только из белого списка ([buildArgv] валидирует до отправки).
 */
class ShizukuOpsImpl(context: Context) : ShizukuOps {

    private val appContext = context.applicationContext

    override suspend fun status(): ShizukuOps.ShizukuStatus = withContext(Dispatchers.IO) {
        if (Shizuku.isPreV11()) return@withContext ShizukuOps.ShizukuStatus.NOT_INSTALLED
        try {
            if (!Shizuku.pingBinder()) return@withContext ShizukuOps.ShizukuStatus.NOT_RUNNING
        } catch (_: Exception) {
            return@withContext ShizukuOps.ShizukuStatus.NOT_RUNNING
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuOps.ShizukuStatus.READY
        } else {
            ShizukuOps.ShizukuStatus.NOT_AUTHORIZED
        }
    }

    /** Запросить разрешение у Shizuku (системный диалог). Результат — слушателем. */
    fun requestPermission(code: Int) {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(code)
        } catch (_: Exception) {
        }
    }

    fun addPermissionListener(l: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(l)
    }

    fun removePermissionListener(l: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(l)
    }

    fun addBinderReceivedListener(l: Shizuku.OnBinderReceivedListener) {
        try { Shizuku.addBinderReceivedListenerSticky(l) } catch (_: Exception) {}
    }

    fun removeBinderReceivedListener(l: Shizuku.OnBinderReceivedListener) {
        try { Shizuku.removeBinderReceivedListener(l) } catch (_: Exception) {}
    }

    fun addBinderDeadListener(l: Shizuku.OnBinderDeadListener) {
        try { Shizuku.addBinderDeadListener(l) } catch (_: Exception) {}
    }

    fun removeBinderDeadListener(l: Shizuku.OnBinderDeadListener) {
        try { Shizuku.removeBinderDeadListener(l) } catch (_: Exception) {}
    }

    override suspend fun run(
        command: ShizukuCommand,
        args: Map<String, String>,
    ): ShizukuOps.CommandResult = withContext(Dispatchers.IO) {
        if (status() != ShizukuOps.ShizukuStatus.READY) {
            return@withContext ShizukuOps.CommandResult(-1, "", "shizuku не готов")
        }
        val argv = buildArgv(command, args) ?: return@withContext ShizukuOps.CommandResult(
            -1, "", "плохие аргументы для ${command.name}",
        )
        try {
            val (exit, out) = execViaUserService(argv)
            ShizukuOps.CommandResult(exit, out.trimEnd().take(MAX_OUT), "")
        } catch (e: Exception) {
            ShizukuOps.CommandResult(-1, "", "shizuku: ${e.message}")
        }
    }

    /**
     * Синхронный вызов UserService: bind → runShell → unbind.
     * Возвращает (exitCode, вывод). Бросает исключение при сбое bind/IPC.
     */
    private fun execViaUserService(argv: Array<String>): Pair<Int, String> {
        val args = UserServiceArgs(
            ComponentName(appContext.packageName, ShellUserService::class.java.name),
        ).daemon(false)
            .processNameSuffix("merta_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
        val holder = AtomicReference<IBinder?>()
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                holder.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                latch.countDown()
            }
        }
        Shizuku.bindUserService(args, conn)
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw IllegalStateException("таймаут bind user service")
            }
            val binder = holder.get() ?: throw IllegalStateException("нет биндера user service")
            val raw = IShellService.Stub.asInterface(binder).runShell(argv)
            val nl = raw.indexOf('\n')
            if (nl < 0) return -1 to raw
            return (raw.substring(0, nl).toIntOrNull() ?: -1) to raw.substring(nl + 1)
        } finally {
            try {
                Shizuku.unbindUserService(args, conn, false)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val MAX_OUT = 20 * 1024
        /**
         * Чистая сборка argv из белого списка. null — аргументы не прошли валидацию.
         * Покрыта JVM-тестами (Shizuku не нужен).
         */
        fun buildArgv(command: ShizukuCommand, args: Map<String, String>): Array<String>? {
            return when (command) {
                ShizukuCommand.INSTALL_APK -> {
                    val apk = args["apk"]?.trim().orEmpty()
                    // shell (UID 2000) не читает приватные файлы приложения —
                    // только общие (/sdcard, /storage).
                    if (apk.isBlank() || !apk.endsWith(".apk")) return null
                    if (!apk.startsWith("/sdcard/") && !apk.startsWith("/storage/")) return null
                    if (".." in apk || apk.contains('\n') || apk.contains(';')) return null
                    arrayOf("pm", "install", "-r", "-d", apk)
                }
                ShizukuCommand.START_ACTIVITY -> {
                    val c = args["component"]?.trim().orEmpty()
                    if (c.isBlank() || "/" !in c || " " in c) return null
                    arrayOf("am", "start", "-n", c)
                }
                ShizukuCommand.LIST_PACKAGES -> {
                    val f = args["filter"]?.trim().orEmpty()
                    if (f.isEmpty()) {
                        arrayOf("pm", "list", "packages")
                    } else {
                        if (" " in f || ";" in f || "|" in f) return null
                        arrayOf("pm", "list", "packages", f)
                    }
                }
                ShizukuCommand.GRANT_PERMISSION -> {
                    val pkg = args["pkg"]?.trim().orEmpty()
                    val perm = args["permission"]?.trim().orEmpty()
                    if (pkg.isBlank() || " " in pkg || perm.isBlank() || " " in perm) return null
                    arrayOf("pm", "grant", pkg, perm)
                }
                ShizukuCommand.DUMP_CRASH -> {
                    val tag = args["tag"]?.trim().orEmpty()
                    if (tag.isBlank() || " " in tag) return null
                    arrayOf("dumpsys", "dropbox", "--print", tag)
                }
                ShizukuCommand.TAP -> {
                    val x = parseCoord(args["x"]) ?: return null
                    val y = parseCoord(args["y"]) ?: return null
                    arrayOf("input", "tap", x.toString(), y.toString())
                }
                ShizukuCommand.SWIPE -> {
                    val x1 = parseCoord(args["x1"]) ?: return null
                    val y1 = parseCoord(args["y1"]) ?: return null
                    val x2 = parseCoord(args["x2"]) ?: return null
                    val y2 = parseCoord(args["y2"]) ?: return null
                    arrayOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString())
                }
            }
        }

        /** Координаты тапа/свайпа: только неотрицательные целые. */
        fun parseCoord(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
    }
}
