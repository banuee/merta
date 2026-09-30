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

/** Вложение к сообщению (изображение, скриншот, документ). */
data class Attachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val base64Data: String? = null,
    val localPath: String? = null,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val thought: ThoughtData? = null,
    val attachments: List<Attachment> = emptyList(),
) {
    enum class Role { USER, ASSISTANT, SYSTEM, THINKING }
}

