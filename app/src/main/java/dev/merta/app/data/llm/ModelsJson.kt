package dev.merta.app.data.llm

/**
 * Чистый Kotlin без Android-зависимостей — покрыт JVM-тестами.
 * Разбирает ответ OpenAI-совместимого `GET /models`:
 * `{"data":[{"id":"...","name":"..."}, ...]}` (OpenRouter, OpenCode Zen).
 */
object ModelsJson {

    fun parseModelsList(json: String): List<LlmModel> {
        val dataIdx = json.indexOf("\"data\"")
        if (dataIdx < 0) return emptyList()
        val arrStart = json.indexOf('[', dataIdx)
        if (arrStart < 0) return emptyList()
        val out = mutableListOf<LlmModel>()
        var i = arrStart + 1
        while (i < json.length) {
            // Пропуск пробелов, запятых и закрывающей скобки массива.
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length) break
            if (json[i] == ']') break
            if (json[i] != '{') {
                i++
                continue
            }
            val end = matchObject(json, i) ?: break
            val obj = json.substring(i, end)
            val id = SseParser.extractStringAfterKey(obj, "id", 0)
            if (!id.isNullOrBlank()) {
                val name = SseParser.extractStringAfterKey(obj, "name", 0) ?: ""
                out.add(LlmModel(id, name))
            }
            i = end
        }
        return out
    }

    /** Индекс конца объекта (позиция после `}`), балансируя вложенность и строки. */
    private fun matchObject(json: String, openIdx: Int): Int? {
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
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return null
    }
}
