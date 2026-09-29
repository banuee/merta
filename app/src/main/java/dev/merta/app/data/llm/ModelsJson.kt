package dev.merta.app.data.llm

/**
 * Чистый Kotlin без Android-зависимостей — покрыт JVM-тестами.
 * Разбирает ответ OpenAI-совместимого `GET /models`:
 * `{"data":[{"id":"...","name":"...","pricing":{"prompt":"...","completion":"..."}}, ...]}`
 * (OpenRouter, OpenCode Zen). Цены провайдера — $ за токен строкой → храним $ за 1M.
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
                out.add(LlmModel(id, name, promptPer1M(obj), completionPer1M(obj)))
            }
            i = end
        }
        return out
    }

    /** $ за токен (строкой или числом) → $ за 1M токенов. */
    private fun pricePer1M(obj: String, key: String): Double {
        val pi = obj.indexOf("\"pricing\"")
        val from = if (pi < 0) 0 else pi
        val raw = SseParser.extractStringAfterKey(obj, key, from)
            ?: extractNumber(obj, key, from)
            ?: return 0.0
        return (raw.toDoubleOrNull() ?: 0.0) * 1_000_000
    }

    private fun promptPer1M(obj: String): Double = pricePer1M(obj, "prompt")

    private fun completionPer1M(obj: String): Double = pricePer1M(obj, "completion")

    private fun extractNumber(json: String, key: String, from: Int): String? {
        var i = json.indexOf("\"$key\"", from)
        if (i < 0) return null
        i += key.length + 2
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        var j = i
        while (j < json.length && (json[j].isDigit() || json[j] == '.' || json[j] == '-' || json[j] == 'e' || json[j] == 'E' || json[j] == '+')) j++
        if (j == i) return null
        return json.substring(i, j)
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
