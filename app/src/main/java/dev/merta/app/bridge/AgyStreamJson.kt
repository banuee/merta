package dev.merta.app.bridge

import dev.merta.app.data.llm.SseParser

/**
 * Разбор NDJSON от `agy -p --output-format stream-json` (через демон).
 * Точная схема (живой agy 1.2.13):
 * - `step_update.step_type == "tool"`: `tool_name`, `tool_info{name,
 *   parameters{...}, output}` (output только в DONE), state ACTIVE/DONE;
 * - `step_update.step_type == "agent_response"`: текст в `text_delta`;
 * - `result`: `{status, response, error, conversation_id, denied_actions[]}`;
 * - не-JSON строки stdout — дельты текста.
 * Текст берём ТОЛЬКО из text_delta: tool-output в чат не тащим.
 * Мышления в стриме НЕТ (только thinking_tokens в usage) — Thought
 * собираем из tool-шагов (аргументы + результат) на стороне приложения.
 */
object AgyStreamJson {

    /** Токены хода (result.usage). */
    data class TurnUsage(val input: Long = 0L, val output: Long = 0L, val thinking: Long = 0L)

    sealed interface AgyEvent {
        data class Init(val conversationId: String) : AgyEvent
        data class Delta(val text: String) : AgyEvent
        /** Tool-шаг: details — имя + аргументы, output — результат (только DONE). */
        data class Tool(val details: String, val output: String, val done: Boolean) : AgyEvent
        data class Done(
            val response: String,
            val conversationId: String,
            val denied: List<String> = emptyList(),
            val usage: TurnUsage = TurnUsage(),
        ) : AgyEvent
        data class Error(val message: String) : AgyEvent
    }

    fun parseLine(line: String): List<AgyEvent> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        if (!t.startsWith("{")) return listOf(AgyEvent.Delta(t))
        return when (SseParser.extractStringAfterKey(t, "event", 0)) {
            "init" -> listOf(AgyEvent.Init(convId(t)))
            "result" -> listOf(parseResult(t, t.indexOf("\"result\"")))
            "step_update" -> parseStep(t, t.indexOf("\"step_update\""))
            else -> parseBare(t)
        }
    }

    /** Голый конверт без event (json-формат) или мусор. */
    private fun parseBare(t: String): List<AgyEvent> {
        if (t.contains("\"status\"") || t.contains("\"response\"")) {
            return listOf(parseResult(t, 0))
        }
        return emptyList()
    }

    private fun parseResult(t: String, from: Int): AgyEvent {
        val status = SseParser.extractStringAfterKey(t, "status", from)?.uppercase() ?: ""
        val response = SseParser.extractStringAfterKey(t, "response", from) ?: ""
        val conv = convId(t)
        return if (status == "SUCCESS" || (status.isEmpty() && response.isNotEmpty())) {
            AgyEvent.Done(response, conv, parseDenied(t, from), parseUsage(t, from))
        } else {
            val err = SseParser.extractStringAfterKey(t, "error", from)
                ?: SseParser.extractStringAfterKey(t, "message", from)
                ?: status.ifBlank { "неизвестная ошибка agy" }
            AgyEvent.Error(err)
        }
    }

    /** Токены хода из result.usage (нет — нули). */
    private fun parseUsage(t: String, from: Int): TurnUsage {
        val ui = t.indexOf("\"usage\"", from)
        if (ui < 0) return TurnUsage()
        return TurnUsage(
            input = SseParser.extractLongAfterKey(t, "input_tokens", ui) ?: 0L,
            output = SseParser.extractLongAfterKey(t, "output_tokens", ui) ?: 0L,
            thinking = SseParser.extractLongAfterKey(t, "thinking_tokens", ui) ?: 0L,
        )
    }

    /** display_name отклонённых действий (permission request-review без yolo). */
    private fun parseDenied(t: String, from: Int): List<String> {        val dk = t.indexOf("\"denied_actions\"", from)
        if (dk < 0) return emptyList()
        val open = t.indexOf('[', dk + 16)
        if (open < 0) return emptyList()
        val end = skipBalanced(t, open) ?: return emptyList()
        val out = mutableListOf<String>()
        var i = dk
        while (out.size < 10) {
            val k = t.indexOf("\"display_name\"", i)
            if (k < 0 || k >= end) break
            SseParser.extractStringAfterKey(t, "display_name", k)?.let { out.add(it) }
            i = k + 15
        }
        return out
    }

    private fun parseStep(t: String, from: Int): List<AgyEvent> {
        val stepType = SseParser.extractStringAfterKey(t, "step_type", from) ?: ""
        if (stepType == "tool") {
            val name = SseParser.extractStringAfterKey(t, "tool_name", from)
                ?: toolInfoName(t, from)
                ?: "tool"
            val state = SseParser.extractStringAfterKey(t, "state", from)
            val done = state == "DONE"
            val params = parseParams(t, from)
            val details = renderDetails(name, params)
            val rawOut = if (done) {
                SseParser.extractStringAfterKey(t, "output", toolInfoIndex(t, from)) ?: ""
            } else {
                ""
            }
            val output = rawOut.replace("\r\n", "\n").replace('\r', '\n').take(500)
            return listOf(AgyEvent.Tool(details, output, done))
        }
        val d = SseParser.extractStringAfterKey(t, "text_delta", from)
        return if (!d.isNullOrEmpty()) listOf(AgyEvent.Delta(d)) else emptyList()
    }

    /** Короткая подпись вызова: имя + аргументы (одно значение — как есть). */
    private fun renderDetails(name: String, params: Map<String, String>): String {
        if (params.isEmpty()) return name
        fun cap(v: String) = v.take(120)
        return if (params.size == 1) {
            "$name: ${cap(params.values.first())}"
        } else {
            "$name(" + params.entries.joinToString(", ") { "${it.key}=${cap(it.value)}" }.take(280) + ")"
        }
    }

    private fun toolInfoIndex(t: String, from: Int): Int {
        val ti = t.indexOf("\"tool_info\"", from)
        return if (ti < 0) from else ti
    }

    /** Плоский разбор {"k": "v"/число/bool} после "parameters" (вложенное → <…>). */
    private fun parseParams(t: String, from: Int): Map<String, String> {
        val pk = t.indexOf("\"parameters\"", from)
        if (pk < 0) return emptyMap()
        var i = t.indexOf('{', pk + 12)
        if (i < 0) return emptyMap()
        val out = LinkedHashMap<String, String>()
        i++
        while (i < t.length && out.size < 20) {
            while (i < t.length && (t[i].isWhitespace() || t[i] == ',')) i++
            if (i >= t.length || t[i] == '}') break
            if (t[i] != '"') {
                i++
                continue
            }
            val key = readJsonString(t, i) ?: break
            i = key.second
            while (i < t.length && t[i].isWhitespace()) i++
            if (i >= t.length || t[i] != ':') break
            i++
            while (i < t.length && t[i].isWhitespace()) i++
            if (i >= t.length) break
            val v: String? = when {
                t[i] == '"' -> {
                    val r = readJsonString(t, i)
                    i = r?.second ?: break
                    r?.first
                }
                t[i] == '{' || t[i] == '[' -> {
                    i = skipBalanced(t, i) ?: break
                    "<…>"
                }
                else -> {
                    var j = i
                    while (j < t.length && ",}]".indexOf(t[j]) < 0) j++
                    val s = t.substring(i, j).trim()
                    i = j
                    if (s == "null" || s.isEmpty()) null else s
                }
            }
            if (v != null) out[key.first] = v
        }
        return out
    }

    private fun readJsonString(t: String, quoteIdx: Int): Pair<String, Int>? {
        var i = quoteIdx + 1
        val out = StringBuilder()
        while (i < t.length) {
            val c = t[i]
            if (c == '"') return out.toString() to (i + 1)
            if (c == '\\') {
                i++
                if (i >= t.length) return null
                when (t[i]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'u' -> {
                        if (i + 4 >= t.length) return null
                        out.append(t.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: return null)
                        i += 4
                    }
                    else -> out.append(t[i])
                }
            } else {
                out.append(c)
            }
            i++
        }
        return null
    }

    /** Позиция после закрывающей скобки объекта/массива с учётом строк. */
    private fun skipBalanced(t: String, openIdx: Int): Int? {
        val closeFor = if (t[openIdx] == '{') '}' else ']'
        var depth = 0
        var inStr = false
        var i = openIdx
        while (i < t.length) {
            val c = t[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return null
    }

    private fun toolInfoName(t: String, from: Int): String? {        val ti = t.indexOf("\"tool_info\"", from)
        if (ti < 0) {
            // Иногда имя лежит в step_type ("tool:read_file") или title.
            val st = SseParser.extractStringAfterKey(t, "step_type", from)
            if (st != null && ':' in st) return st.substringAfter(':').trim().takeIf { it.isNotEmpty() }
            return SseParser.extractStringAfterKey(t, "title", from)
        }
        return SseParser.extractStringAfterKey(t, "name", ti)
    }

    private fun convId(t: String): String =
        SseParser.extractStringAfterKey(t, "conversation_id", 0) ?: ""
}
