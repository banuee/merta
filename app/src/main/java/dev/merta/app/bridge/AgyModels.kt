package dev.merta.app.bridge

import dev.merta.app.data.llm.LlmModel

/**
 * Каталог моделей из `agy models` (чистый, JVM-тесты).
 * Формат CLI — plain-текст `ID  Имя` построчно (порт десктопной merta):
 * ```
 * Fetching available models...
 * gemini-3.8-flash-high Gemini 3.8 Flash (High)
 * gemini-3.8-flash Gemini 3.8 Flash
 * ```
 * Варианты `-high/-medium/-low` группируются в базовую модель.
 * Без авторизации CLI отдаёт ошибку — тогда [FALLBACK].
 */
object AgyModels {

    val AGY_EFFORTS = listOf("low", "medium", "high", "max")

    val FALLBACK = listOf(
        LlmModel("gemini-3.8-flash", "Gemini 3.8 Flash", reasoningSupported = true, supportedEfforts = AGY_EFFORTS, contextLength = 1_000_000L),
        LlmModel("gemini-3.7-flash", "Gemini 3.7 Flash", reasoningSupported = true, supportedEfforts = AGY_EFFORTS, contextLength = 1_000_000L),
        LlmModel("gemini-3.1-pro", "Gemini 3.1 Pro", reasoningSupported = true, supportedEfforts = AGY_EFFORTS, contextLength = 2_000_000L),
        LlmModel("claude-sonnet-4-6", "Claude Sonnet 4.6", reasoningSupported = false, supportedEfforts = emptyList(), contextLength = 200_000L),
        LlmModel("claude-opus-4-6-thinking", "Claude Opus 4.6 Thinking", reasoningSupported = false, supportedEfforts = emptyList(), contextLength = 200_000L),
        LlmModel("gpt-oss-120b", "GPT-OSS 120B", reasoningSupported = true, supportedEfforts = AGY_EFFORTS, contextLength = 128_000L),
    )

    fun parse(raw: String): List<LlmModel> {
        val clean = raw
            .replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
            .replace('\r', '\n')
            .replace("Fetching available models...", "")
        val grouped = linkedMapOf<String, GroupAcc>()
        for (line in clean.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val m = Regex("^([a-zA-Z0-9._-]+)\\s+(.+)$").matchEntire(t) ?: continue
            val id = m.groupValues[1]
            val name = m.groupValues[2].trim()
            val variant = Regex("^(.+)-(high|medium|low)$").matchEntire(id)
            if (variant != null) {
                val base = variant.groupValues[1]
                val cleanName = name.replace(Regex("\\s*\\((High|Medium|Low)\\)$"), "").trim()
                val acc = grouped.getOrPut(base) { GroupAcc(base, cleanName) }
                if (acc.name.isBlank()) acc.name = cleanName
            } else {
                grouped.getOrPut(id) { GroupAcc(id, displayName(id, name)) }
            }
        }
        return grouped.values.map {
            val supported = it.id.startsWith("gemini") || it.id.startsWith("gpt-oss")
            val efforts = if (supported) AGY_EFFORTS else emptyList()
            val ctx = when {
                it.id.startsWith("gemini-3.1-pro") -> 2_000_000L
                it.id.startsWith("gemini") -> 1_000_000L
                it.id.startsWith("claude") -> 200_000L
                else -> 128_000L
            }
            LlmModel(it.id, it.name.ifBlank { it.id }, reasoningSupported = supported, supportedEfforts = efforts, contextLength = ctx)
        }
    }

    private class GroupAcc(val id: String, var name: String)

    private fun displayName(id: String, name: String): String = when (id) {
        "claude-sonnet-4-6" -> "Claude Sonnet 4.6"
        "claude-opus-4-6-thinking" -> "Claude Opus 4.6 Thinking"
        else -> name
    }
}
