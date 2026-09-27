package dev.merta.app.adb

/**
 * Фаза 5: привилегированные операции через Shizuku (shell, UID 2000 — НЕ root).
 * Библиотека: RikkaApps/Shizuku-API, выполнение через UserService.
 * Белый список команд — только установка/запуск/диагностика своих сборок.
 * Без Shizuku приложение работает (чат + файлы + agy), этот модуль деградирует.
 *
 * Потолок: чужие /data/user/0/<pkg> недоступны, после ребута Shizuku надо
 * перезапустить (wireless debugging, Android 11+).
 */
enum class ShizukuCommand(val template: String) {
    INSTALL_APK("pm install -r -d {apk}"),
    START_ACTIVITY("am start -n {component}"),
    LIST_PACKAGES("pm list packages {filter}"),
    GRANT_PERMISSION("pm grant {pkg} {permission}"),
    DUMP_CRASH("dumpsys dropbox --print {tag}"),
}

interface ShizukuOps {
    /** Установлен и запущен ли демон Shizuku + авторизовано ли приложение. */
    suspend fun status(): ShizukuStatus

    /**
     * Выполнить команду из белого списка. Всё вне [ShizukuCommand] — отказ.
     * Деструктивное — только после approve в UI.
     */
    suspend fun run(command: ShizukuCommand, args: Map<String, String>): CommandResult

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    enum class ShizukuStatus {
        NOT_INSTALLED, NOT_RUNNING, NOT_AUTHORIZED, READY,
    }
}
