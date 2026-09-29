package dev.merta.app.data.llm

/**
 * Фаза 1: прямой доступ к нейросетям по OpenAI-совместимому HTTP.
 * Покрывает OpenRouter и OpenCode Zen одним кодом (chat/completions + SSE-стрим).
 * Ключи — только в EncryptedSharedPreferences, никогда в DataStore-plain и логах.
 */
data class LlmMessage(val role: String, val content: String)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    /** Уровень рассуждений (OpenRouter `reasoning.effort`). null = выкл. */
    val effort: String? = null,
)

/** Модель из каталога провайдера (`GET /models`). Цены — $ за 1M токенов (0 = неизвестны). */
data class LlmModel(
    val id: String,
    val name: String,
    val promptPer1M: Double = 0.0,
    val completionPer1M: Double = 0.0,
) {
    val displayName: String get() = name.ifBlank { id }
}

interface LlmStreamingProvider {
    /** Имя для UI, например "openrouter". */
    val id: String

    /**
     * Стримит ответ чанками текста; в конце — полный текст (для истории).
     * Ошибки сети/авторизации — исключением, UI маппит в SYSTEM-сообщение.
     */
    suspend fun streamChat(request: LlmRequest, onDelta: (String) -> Unit): String

    /**
     * Каталог моделей (`GET {base}/models`). Дефолт — пусто
     * (провайдер без discovery, модель вводится вручную).
     */
    suspend fun listModels(): List<LlmModel> = emptyList()

    companion object {
        /** Бесплатные/инструментальные модели без tools: retry без блока tools (как в десктопной merta). */
        const val TOOL_FALLBACK_HINT = "openrouter-tool-fallback"
    }
}

/** Ошибка провайдера: HTTP-статус + человекочитаемый текст (уже без секретов). */
class LlmException(val status: Int, message: String) : Exception(message)
