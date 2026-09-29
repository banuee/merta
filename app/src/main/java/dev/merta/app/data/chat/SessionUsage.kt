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
    }
}
