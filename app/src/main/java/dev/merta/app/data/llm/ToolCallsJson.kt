package dev.merta.app.data.llm

/**
 * Ход переписки для агентного цикла (v1 tools).
 * assistant может нести tool_calls, tool отвечает по tool_call_id.
 */
data class TurnMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<OutToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class OutToolCall(val id: String, val name: String, val argumentsJson: String)

/** Фрагмент tool_call из SSE-чанка (аргументы досылаются кусками). */
data class ToolCallFrag(
    val index: Int,
    val id: String?,
    val name: String?,
    val argsFrag: String?,
)

/**
 * Парсинг `choices[0].delta.tool_calls` из чанка. Чистый Kotlin — JVM-тесты.
 * Возвращает фрагменты; сборка по index — на вызывающей стороне.
 */
object ToolCallsJson {

    fun extractFrags(chunkJson: String): List<ToolCallFrag> {
        val tcIdx = chunkJson.indexOf("\"tool_calls\"")
        if (tcIdx < 0) return emptyList()
        val arrStart = chunkJson.indexOf('[', tcIdx)
        if (arrStart < 0) return emptyList()
        val out = mutableListOf<ToolCallFrag>()
        var i = arrStart + 1
        while (i < chunkJson.length) {
            while (i < chunkJson.length && (chunkJson[i].isWhitespace() || chunkJson[i] == ',')) i++
            if (i >= chunkJson.length || chunkJson[i] == ']') break
            if (chunkJson[i] != '{') {
                i++
                continue
            }
            val end = matchCurlies(chunkJson, i) ?: break
            val obj = chunkJson.substring(i, end)
            out.add(
                ToolCallFrag(
                    index = extractInt(obj, "index") ?: out.size,
                    id = SseParser.extractStringAfterKey(obj, "id", 0),
                    name = extractFunctionField(obj, "name"),
                    argsFrag = extractFunctionField(obj, "arguments"),
                ),
            )
            i = end
        }
        return out
    }

    private fun extractFunctionField(obj: String, field: String): String? {
        val fIdx = obj.indexOf("\"function\"")
        val from = if (fIdx >= 0) fIdx else 0
        return SseParser.extractStringAfterKey(obj, field, from)
    }

    /** Целое после "key": digits (возвращает null если нет). */
    fun extractInt(json: String, key: String): Int? {
        var i = json.indexOf("\"$key\"")
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
        return json.substring(i, j).toIntOrNull()
    }

    private fun matchCurlies(json: String, openIdx: Int): Int? {
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
