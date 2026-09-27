package dev.merta.app.bridge

/**
 * Фаза 4: мост к Antigravity CLI (`agy`) в proot-Ubuntu под Termux.
 * RUN_COMMAND — только бутстрап демона; рабочий канал — localhost HTTP:
 * приложение шлёт prompt, демон запускает `agy -p --output-format stream-json`
 * и отдаёт NDJSON чанками. Только так есть настоящий стриминг.
 * Multi-turn: `--input-format stream-json` / `--continue` / `--conversation`.
 */
sealed interface AgyEvent {
    data class Init(val conversationId: String) : AgyEvent
    data class Delta(val text: String) : AgyEvent
    data class ToolCall(val label: String) : AgyEvent
    data class Result(val response: String) : AgyEvent
}

data class AgyRequest(
    val prompt: String,
    /** Проброс рабочего пространства: agy `--add-dir`. */
    val workDir: String? = null,
    /** Продолжить последнюю беседу (`--continue`) или конкретную (`--conversation`). */
    val conversationId: String? = null,
    val continueLast: Boolean = false,
)

interface AgyBridge {
    /** Поднят ли демон (health-check localhost). */
    suspend fun isDaemonAlive(): Boolean

    /** Стримит [AgyEvent] по мере NDJSON от демона. */
    suspend fun streamPrompt(request: AgyRequest, onEvent: (AgyEvent) -> Unit)
}
