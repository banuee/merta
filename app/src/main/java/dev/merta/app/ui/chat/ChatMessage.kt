package dev.merta.app.ui.chat

/** Строка мышления в ленте (как в opencode): активная — спиннер, готовая — Thought · Xs. */
data class ThoughtData(
    val active: Boolean,
    val startedMs: Long,
    val steps: List<String> = emptyList(),
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
