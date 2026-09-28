package dev.merta.app.data.tools

/**
 * Инструменты агента v1. Всё файловое — через FileGateway (скоупы),
 * деструктивное — только после approve в UI.
 */
object ToolDefs {
    const val READ_FILE = "read_file"
    const val LIST_DIR = "list_dir"
    const val GREP_SEARCH = "grep_search"
    const val WRITE_FILE = "write_file"
    const val RUN_COMMAND = "run_command"
    const val INSTALL_APK = "install_apk"
    const val LIST_PACKAGES = "list_packages"
    const val TAP_SCREEN = "tap_screen"
    const val SWIPE_SCREEN = "swipe_screen"

    data class Def(
        val name: String,
        val description: String,
        /** Pair(param, description) по порядку; все строковые. */
        val params: List<Pair<String, String>>,
        val required: List<String>,
        /** Требовать подтверждение пользователя перед выполнением. */
        val needsApproval: Boolean,
    )

    val ALL: List<Def> = listOf(
        Def(
            READ_FILE,
            "Прочитать текстовый файл (до 100 КБ). Относительный путь — от рабочей папки.",
            listOf("path" to "Путь к файлу (абсолютный или относительный)"),
            listOf("path"),
            needsApproval = false,
        ),
        Def(
            LIST_DIR,
            "Список файлов и папок (до 300).",
            listOf("path" to "Путь к папке (абсолютный, относительный или пусто = рабочая)"),
            listOf(),
            needsApproval = false,
        ),
        Def(
            GREP_SEARCH,
            "Поиск подстроки по текстовым файлам (до 100 совпадений, бинарные и >1МБ пропускаются).",
            listOf(
                "root" to "Папка для поиска (пусто = рабочая)",
                "pattern" to "Подстрока (без регулярок)",
            ),
            listOf("pattern"),
            needsApproval = false,
        ),
        Def(
            WRITE_FILE,
            "Создать или ПЕРЕЗАПИСАТЬ файл целиком. Содержимое — полный текст файла. Требует подтверждения.",
            listOf(
                "path" to "Абсолютный путь к файлу",
                "content" to "Полное содержимое файла",
            ),
            listOf("path", "content"),
            needsApproval = true,
        ),
        Def(
            RUN_COMMAND,
            "Выполнить shell-команду (sh -c, таймаут 60с, вывод до 20К символов). Рабочая папка по умолчанию — workspace. Требует подтверждения. Запрещены: rm -rf /, mkfs, dd, форк-бомбы.",
            listOf(
                "command" to "Команда",
                "workdir" to "Рабочая папка (необязательно)",
            ),
            listOf("command"),
            needsApproval = true,
        ),
        Def(
            INSTALL_APK,
            "Установить APK через Shizuku (pm install). Путь — только общий (/sdcard/…, положи файл в Download). Требует подтверждения.",
            listOf("path" to "Путь к .apk в /sdcard/…"),
            listOf("path"),
            needsApproval = true,
        ),
        Def(
            LIST_PACKAGES,
            "Список установленных пакетов через Shizuku (подстрока-фильтр, пусто = все).",
            listOf("filter" to "Подстрока пакета (необязательно)"),
            listOf(),
            needsApproval = false,
        ),
        Def(
            TAP_SCREEN,
            "Тап по экрану в координатах устройства через Shizuku. Требует подтверждения.",
            listOf("x" to "X в пикселях", "y" to "Y в пикселях"),
            listOf("x", "y"),
            needsApproval = true,
        ),
        Def(
            SWIPE_SCREEN,
            "Свайп по экрану через Shizuku. Требует подтверждения.",
            listOf(
                "x1" to "Начальный X",
                "y1" to "Начальный Y",
                "x2" to "Конечный X",
                "y2" to "Конечный Y",
            ),
            listOf("x1", "y1", "x2", "y2"),
            needsApproval = true,
        ),
    )

    fun byName(name: String): Def? = ALL.find { it.name == name }

    /** JSON-схема для поля tools запроса chat/completions. */
    fun toolsJson(): String {
        val sb = StringBuilder("[")
        ALL.forEachIndexed { i, d ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"function\",\"function\":{\"name\":\"").append(d.name)
                .append("\",\"description\":\"").append(esc(d.description)).append('"')
                .append(",\"parameters\":{\"type\":\"object\",\"properties\":{")
            d.params.forEachIndexed { j, (pname, pdesc) ->
                if (j > 0) sb.append(',')
                sb.append('"').append(pname).append("\":{\"type\":\"string\",\"description\":\"")
                    .append(esc(pdesc)).append("\"}")
            }
            sb.append("},\"required\":[")
            d.required.forEachIndexed { j, r ->
                if (j > 0) sb.append(',')
                sb.append('"').append(r).append('"')
            }
            sb.append("]}}}")
        }
        return sb.append(']').toString()
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}

/** Вызов инструмента из ответа модели. */
data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)

/** Запрос подтверждения: показывается диалогом. */
data class PendingApproval(
    val toolName: String,
    val summary: String,
    /** Короткий предпросмотр (первые строки контента / команда). */
    val preview: String,
)

/** Жёсткий deny-list: никогда не выполнять, даже с approve (как в десктопной merta). */
object SafetyDenyList {
    private val PATTERNS = listOf(
        "rm -rf /",
        "rm -rf /*",
        "rm -fr /",
        "mkfs",
        "dd if=",
        ":(){ :|:& };:",
        "> /dev/sd",
    )

    fun blocked(command: String): String? {
        val c = command.trim()
        return PATTERNS.firstOrNull { c.contains(it) }?.let { "заблокировано deny-list: «$it»" }
    }
}
