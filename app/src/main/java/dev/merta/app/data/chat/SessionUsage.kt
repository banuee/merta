package dev.merta.app.data.chat

/**
 * Токены текущей сессии (копятся по ходам обеих веток: agy result.usage,
 * OpenAI SSE usage). Стоимость — по тарифам каталога ($ за 1M):
 * thinking считаем по тарифу output (так биллит OpenRouter).
 * Чистый — покрыт JVM-тестами.
 */
data class SessionUsage(
    val input: Long = 0L,
    val output: Long = 0L,
    val thinking: Long = 0L,
) {
    fun add(inputTokens: Long, outputTokens: Long, thinkingTokens: Long = 0L): SessionUsage =
        copy(
            input = input + inputTokens.coerceAtLeast(0L),
            output = output + outputTokens.coerceAtLeast(0L),
            thinking = thinking + thinkingTokens.coerceAtLeast(0L),
        )

    fun total(): Long = input + output + thinking

    /** $ сессии. Тарифов нет (0, 0) — 0.0, UI покажет только токены. */
    fun cost(promptPer1M: Double, completionPer1M: Double): Double {
        if (promptPer1M <= 0 && completionPer1M <= 0) return 0.0
        return (input * promptPer1M + (output + thinking) * completionPer1M) / 1_000_000
    }

    companion object {
        private val LOCALE = java.util.Locale.US

        /** 12345 → "12.3k", 999 → "999". */
        fun formatTokens(n: Long): String = when {
            n < 1000 -> n.toString()
            n < 1_000_000 -> {
                val v = n / 1000.0
                if (v >= 100) v.toInt().toString() + "k"
                else "%.1f".format(LOCALE, v).trimEnd('0').trimEnd('.') + "k"
            }
            else -> "%.1fM".format(LOCALE, n / 1_000_000.0)
        }

        /** 0.0312 → "$0.031", 1.5 → "$1.50". */
        fun formatCost(usd: Double): String = if (usd < 1) {
            "$%.3f".format(LOCALE, usd)
        } else {
            "$%.2f".format(LOCALE, usd)
        }

        /**
         * Быстрая и точная эвристика подсчёта токенов по тексту:
         * - Кириллица: ~2.2 символа на BPE-токен (русский язык);
         * - Латиница/цифры/пробелы: ~3.8 символа на токен;
         * - Знаки препинания / спецсимволы / CJK: ~1.8 символа на токен.
         */
        fun estimateTokens(text: String): Long {
            if (text.isEmpty()) return 0L
            var cyrillic = 0
            var ascii = 0
            var other = 0
            for (ch in text) {
                when (ch) {
                    in 'a'..'z', in 'A'..'Z', in '0'..'9', ' ', '\n', '\t' -> ascii++
                    in 'а'..'я', in 'А'..'Я', 'ё', 'Ё' -> cyrillic++
                    else -> other++
                }
            }
            val est = (cyrillic / 2.2) + (ascii / 3.8) + (other / 1.8)
            return est.toLong().coerceAtLeast(1L)
        }

        /** Оценка контекста и использования по списку сообщений сессии. */
        fun estimateFromMessages(messages: List<dev.merta.app.ui.chat.ChatMessage>): SessionUsage {
            var input = 0L
            var output = 0L
            var thinking = 0L
            for (m in messages) {
                when (m.role) {
                    dev.merta.app.ui.chat.ChatMessage.Role.USER -> {
                        input += estimateTokens(m.text) + 4L
                    }
                    dev.merta.app.ui.chat.ChatMessage.Role.ASSISTANT -> {
                        output += estimateTokens(m.text) + 4L
                    }
                    dev.merta.app.ui.chat.ChatMessage.Role.THINKING -> {
                        val t = m.thought
                        val thoughtText = (t?.steps?.joinToString("\n") ?: "") + "\n" + (t?.reasoning ?: "")
                        thinking += estimateTokens(thoughtText)
                    }
                    dev.merta.app.ui.chat.ChatMessage.Role.SYSTEM -> {
                        input += estimateTokens(m.text) + 4L
                    }
                }
            }
            return SessionUsage(input = input, output = output, thinking = thinking)
        }
    }

    fun detailString(): String {
        val base = "вх ${formatTokens(input)} / вых ${formatTokens(output)}"
        return if (thinking > 0) "$base / мыслей ${formatTokens(thinking)}" else base
    }
}
