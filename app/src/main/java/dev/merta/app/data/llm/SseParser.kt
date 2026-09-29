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

    /**
     * Вытаскивает кусок размышлений модели из одного чанка:
     * - `choices[0].delta.reasoning` (строка — DeepSeek-R1 style через OpenRouter),
     * - `choices[0].delta.reasoning_content` (строка — DeepSeek-нативный стиль),
     * - `choices[0].delta.reasoning_details[]` (объекты `reasoning.text` → `text`,
     *   `reasoning.summary` → `summary`).
     *
     * OpenRouter дублирует один и тот же текст сразу в `reasoning` и в
     * `reasoning_details[].text` одного чанка — точные дубли внутри чанка
     * выкидываются, порядок — по позиции в документе.
     * @return склеенный кусок или null, если чанк без размышлений.
     */
    fun extractReasoning(chunkJson: String): String? {
        val deltaIdx = chunkJson.indexOf("\"delta\"")
        if (deltaIdx < 0) return null
        // (позиция, текст) — сортировка чинит порядок text/summary.
        // Поиск с кавычками точный: "reasoning" не матчит "reasoning_details".
        val pieces = mutableListOf<Pair<Int, String>>()
        for (key in arrayOf("reasoning", "reasoning_content")) {
            val kIdx = chunkJson.indexOf("\"$key\"", deltaIdx)
            if (kIdx >= 0) {
                extractStringAfterKey(chunkJson, key, kIdx)?.let {
                    if (it.isNotEmpty()) pieces.add(kIdx to it)
                }
            }
        }
        var rdIdx = chunkJson.indexOf("\"reasoning_details\"", deltaIdx)
        while (rdIdx >= 0) {
            val arrStart = chunkJson.indexOf('[', rdIdx)
            val arrEnd = if (arrStart < 0) null else matchSquare(chunkJson, arrStart)
            if (arrEnd == null) break
            for (key in arrayOf("text", "summary")) {
                var i = arrStart
                while (true) {
                    val tIdx = chunkJson.indexOf("\"$key\"", i)
                    if (tIdx < 0 || tIdx >= arrEnd) break
                    extractStringAfterKey(chunkJson, key, i)?.let {
                        if (it.isNotEmpty()) pieces.add(tIdx to it)
                    }
                    i = tIdx + key.length + 2
                }
            }
            rdIdx = chunkJson.indexOf("\"reasoning_details\"", arrEnd)
        }
        if (pieces.isEmpty()) return null
        // Порядок по позиции + выкидываем точные дубли чанка (OpenRouter-дубль).
        val out = StringBuilder()
        val seen = mutableSetOf<String>()
        for ((_, text) in pieces.sortedBy { it.first }) {
            if (seen.add(text)) out.append(text)
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /** Парная квадратная скобка к [openIdx] с учётом строк. null — нет пары. */
    private fun matchSquare(json: String, openIdx: Int): Int? {
        var depth = 0
        var inStr = false
        var i = openIdx
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return null
    }

    /** Вытаскивает `error.message` из тела неуспешного ответа. */
    fun extractErrorMessage(errorJson: String): String? {
        val errIdx = errorJson.indexOf("\"error\"")
        val from = if (errIdx >= 0) errIdx else 0
        return extractStringAfterKey(errorJson, "message", from)
    }

    /**
     * Usage чанка (`"usage":{"prompt_tokens":N,"completion_tokens":M,...}`,
     * OpenRouter при `stream_options: {"include_usage": true}`).
     * @return (prompt, completion) или null, если usage нет.
     */
    fun extractUsage(chunkJson: String): Pair<Long, Long>? {
        val uIdx = chunkJson.indexOf("\"usage\"")
        if (uIdx < 0) return null
        val p = extractLongAfterKey(chunkJson, "prompt_tokens", uIdx) ?: return null
        val c = extractLongAfterKey(chunkJson, "completion_tokens", uIdx) ?: 0L
        return p to c
    }

    /** Целое число после `"key":` начиная с [from]. null — нет/не число. */
    fun extractLongAfterKey(json: String, key: String, from: Int): Long? {
        var i = json.indexOf("\"$key\"", from)
        if (i < 0) return null
        i += key.length + 2
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        var j = i
        if (j < json.length && json[j] == '-') j++
        while (j < json.length && json[j].isDigit()) j++
        if (j == i) return null
        return json.substring(i, j).toLongOrNull()
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
