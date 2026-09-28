package dev.merta.app.data.settings

import dev.merta.app.data.llm.SseParser

/**
 * Провайдер нейросети: именованный OpenAI-совместимый endpoint с ключом
 * либо локальный демон Antigravity CLI (`kind == "agy"`, ключ не нужен).
 * Ключи живут только в EncryptedSharedPreferences (см. MertaSettings).
 */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    /** "openai" | "agy". */
    val kind: String = Kind.OPENAI,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank() || kind == Kind.AGY
    val isAgy: Boolean get() = kind == Kind.AGY

    object Kind {
        const val OPENAI = "openai"
        const val AGY = "agy"
    }
}

/**
 * Чистый JSON-кодек провайдеров и карты моделей — покрыт JVM-тестами.
 * Формат: `[{"id":"...","name":"...","baseUrl":"...","apiKey":"...","kind":"openai"}]`
 * (`kind` отсутствует у старых записей — считается "openai"),
 * модели: `{"providerId":{"modelId":"Display Name"}}`.
 */
object ProviderJson {

    fun providersToJson(providers: List<Provider>): String {
        val sb = StringBuilder("[")
        providers.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(SseParser.jsonEscape(p.id)).append('"')
                .append(",\"name\":\"").append(SseParser.jsonEscape(p.name)).append('"')
                .append(",\"baseUrl\":\"").append(SseParser.jsonEscape(p.baseUrl)).append('"')
                .append(",\"apiKey\":\"").append(SseParser.jsonEscape(p.apiKey)).append('"')
                .append(",\"kind\":\"").append(SseParser.jsonEscape(p.kind)).append("\"}")
        }
        return sb.append(']').toString()
    }

    fun providersFromJson(json: String): List<Provider> {
        val out = mutableListOf<Provider>()
        var i = json.indexOf('[')
        if (i < 0) return out
        i++
        while (i < json.length) {
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length || json[i] == ']') break
            if (json[i] != '{') {
                i++
                continue
            }
            val end = matchObject(json, i) ?: break
            val obj = json.substring(i, end)
            val id = SseParser.extractStringAfterKey(obj, "id", 0) ?: ""
            if (id.isNotBlank()) {
                out.add(
                    Provider(
                        id = id,
                        name = SseParser.extractStringAfterKey(obj, "name", 0) ?: id,
                        baseUrl = SseParser.extractStringAfterKey(obj, "baseUrl", 0) ?: "",
                        apiKey = SseParser.extractStringAfterKey(obj, "apiKey", 0) ?: "",
                        kind = SseParser.extractStringAfterKey(obj, "kind", 0)
                            ?.takeIf { it == Provider.Kind.AGY } ?: Provider.Kind.OPENAI,
                    ),
                )
            }
            i = end
        }
        return out
    }

    fun modelsCacheToJson(cache: Map<String, Map<String, String>>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((pid, models) in cache) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(SseParser.jsonEscape(pid)).append("\":{")
            var f2 = true
            for ((mid, name) in models) {
                if (!f2) sb.append(',')
                f2 = false
                sb.append('"').append(SseParser.jsonEscape(mid)).append("\":\"")
                    .append(SseParser.jsonEscape(name)).append('"')
            }
            sb.append('}')
        }
        return sb.append('}').toString()
    }

    fun modelsCacheFromJson(json: String): Map<String, Map<String, String>> {
        val out = mutableMapOf<String, Map<String, String>>()
        var i = json.indexOf('{')
        if (i < 0) return out
        i++
        while (i < json.length) {
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length || json[i] == '}' || json[i] != '"') break
            val key = readJsonString(json, i) ?: break
            i = key.second
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') break
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == '{') {
                val end = matchObject(json, i) ?: break
                out[key.first] = flatStringMap(json.substring(i, end))
                i = end
            } else {
                break
            }
        }
        return out
    }

    private fun flatStringMap(obj: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 1
        while (i < obj.length) {
            while (i < obj.length && (obj[i].isWhitespace() || obj[i] == ',')) i++
            if (i >= obj.length || obj[i] == '}' || obj[i] != '"') break
            val key = readJsonString(obj, i) ?: break
            i = key.second
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != ':') break
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != '"') break
            val value = readJsonString(obj, i) ?: break
            out[key.first] = value.first
            i = value.second
        }
        return out
    }

    /** Читает JSON-строку с позиции кавычки; возвращает (значение, позиция после). */
    private fun readJsonString(s: String, quoteIdx: Int): Pair<String, Int>? {
        var i = quoteIdx + 1
        val out = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return out.toString() to (i + 1)
            if (c == '\\') {
                i++
                if (i >= s.length) return null
                when (s[i]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'u' -> {
                        if (i + 4 >= s.length) return null
                        out.append(s.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: return null)
                        i += 4
                    }
                    else -> out.append(s[i])
                }
            } else {
                out.append(c)
            }
            i++
        }
        return null
    }

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
