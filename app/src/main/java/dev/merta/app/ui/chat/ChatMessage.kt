package dev.merta.app.ui.chat

/** Одно сообщение ленты. Фаза 1: привязка к истории сессий и стримингу. */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}
