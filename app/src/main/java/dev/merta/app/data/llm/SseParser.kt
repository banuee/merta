package dev.merta.app.data.llm

/**
 * Чистый Kotlin без Android-зависимостей — покрыт JVM-тестами.
 * Разбирает Server-Sent Events потока `chat/completions` (OpenAI-совместимые:
 * OpenRouter, OpenCode Zen): строки `data: {...}`, терминатор `data: [DONE]`.
 */
object SseParser {
    const val DONE = "[DONE]"

    /** true, если payload строки — терминатор потока. */
    fun isDone(dataPayload: String): Boolean = dataPayload.trim() == DONE

    /**
     * Вытаскивает текстовую дельту из одного чанка (`choices[0].delta.content`).
     * @return null — чанк без текста (роль, tool_calls, usage) или мусор.
     */
    fun extractDelta(chunkJson: String): String? {
        val deltaIdx = chunkJson.indexOf("\"delta\"")
        if (deltaIdx < 0) return null
        return extractStringAfterKey(chunkJson, "content", deltaIdx)
    }

    /** Вытаскивает `error.message` из тела неуспешного ответа. */
    fun extractErrorMessage(errorJson: String): String? {
        val errIdx = errorJson.indexOf("\"error\"")
        val from = if (errIdx >= 0) errIdx else 0
        return extractStringAfterKey(errorJson, "message", from)
    }

    /**
     * Ищет `"key"` начиная с [from], затем корректно парсит JSON-строку значения
     * (с учётом `\"`, `\\`, `\n`, `\uXXXX`). Возвращает null, если ключа нет,
     * значение — не строка (null/объект) или строка обрезана.
     */
    fun extractStringAfterKey(json: String, key: String, from: Int): String? {
        var i = json.indexOf("\"$key\"", from)
        if (i < 0) return null
        i += key.length + 2
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return out.toString()
            if (c == '\\') {
                i++
                if (i >= json.length) return null
                when (json[i]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 4 >= json.length) return null
                        val hex = json.substring(i + 1, i + 5)
                        val code = hex.toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 4
                    }
                    else -> out.append(json[i])
                }
            } else {
                out.append(c)
            }
            i++
        }
        return null
    }

    /** Экранирует строку для вставки в JSON-тело запроса. */
    fun jsonEscape(raw: String): String {
        val out = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.toString()
    }
}
