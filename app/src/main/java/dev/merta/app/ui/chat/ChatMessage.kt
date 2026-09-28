package dev.merta.app.ui.chat

/** Строка мышления в ленте (как в opencode): активная — спиннер, готовая — Thought · Xs. */
data class ThoughtData(
    val active: Boolean,
    val startedMs: Long,
    /** Прогресс не-текстовых событий (ожидание approve и т.п.). Tool-вызовы сюда НЕ пишутся — у них свои пузыри. */
    val steps: List<String> = emptyList(),
    /** Накопленный reasoning-стрим модели (может быть пустым — не все модели его отдают). */
    val reasoning: String = "",
    /** Длительность завершённого мышления, мс. */
    val lastMs: Long? = null,
)

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val thought: ThoughtData? = null,
) {
    enum class Role { USER, ASSISTANT, SYSTEM, THINKING }
}
