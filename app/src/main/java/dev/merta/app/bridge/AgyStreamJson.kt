package dev.merta.app.bridge

import dev.merta.app.data.llm.SseParser

/**
 * Разбор NDJSON от `agy -p --output-format stream-json` (через демон).
 * Точная схема (десктопная merta + живой agy 1.2.12):
 * - `step_update.step_type == "tool"`: `tool_name` / `tool_info.name`,
 *   state ACTIVE (старт) / DONE (конец);
 * - `step_update.step_type == "agent_response"`: текст в `text_delta`;
 * - `result`: `{status, response, error, conversation_id}` (+ usage, игнорим);
 * - не-JSON строки stdout — дельты текста.
 * Текст берём ТОЛЬКО из text_delta: tool-output в чат не тащим.
 */
object AgyStreamJson {

    sealed interface AgyEvent {
        data class Init(val conversationId: String) : AgyEvent
        data class Delta(val text: String) : AgyEvent
        data class Tool(val label: String) : AgyEvent
        data class Done(val response: String, val conversationId: String) : AgyEvent
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
            AgyEvent.Done(response, conv)
        } else {
            val err = SseParser.extractStringAfterKey(t, "error", from)
                ?: SseParser.extractStringAfterKey(t, "message", from)
                ?: status.ifBlank { "неизвестная ошибка agy" }
            AgyEvent.Error(err)
        }
    }

    private fun parseStep(t: String, from: Int): List<AgyEvent> {
        val stepType = SseParser.extractStringAfterKey(t, "step_type", from) ?: ""
        if (stepType == "tool") {
            val name = SseParser.extractStringAfterKey(t, "tool_name", from)
                ?: toolInfoName(t, from)
                ?: "tool"
            val state = SseParser.extractStringAfterKey(t, "state", from)
            return listOf(
                AgyEvent.Tool(if (state == "DONE") "$name — готово" else name),
            )
        }
        val d = SseParser.extractStringAfterKey(t, "text_delta", from)
        return if (!d.isNullOrEmpty()) listOf(AgyEvent.Delta(d)) else emptyList()
    }

    private fun toolInfoName(t: String, from: Int): String? {
        val ti = t.indexOf("\"tool_info\"", from)
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
