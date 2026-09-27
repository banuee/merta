package dev.merta.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.merta.app.data.agent.AgentFiles
import dev.merta.app.data.chat.SessionStore
import dev.merta.app.data.llm.LlmException
import dev.merta.app.data.llm.LlmMessage
import dev.merta.app.data.llm.LlmModel
import dev.merta.app.data.llm.LlmRequest
import dev.merta.app.data.llm.LlmStreamingProvider
import dev.merta.app.data.llm.OpenAiCompatClient
import dev.merta.app.data.settings.MertaSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Чат: стрим Direct API, история сессий, каталог моделей.
 * Системный промт (`merta/system.md`) подмешивается первым system-сообщением.
 * Персистентность сессий — файлы; остальное состояние — в памяти.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        /** Накапливающийся стрим текущего ответа (null — стрима нет). */
        val streaming: String? = null,
        val sending: Boolean = false,
        val configured: Boolean = false,
        val modelLabel: String = "",
        val sessionId: String = "",
        val sessions: List<SessionStore.SessionMeta> = emptyList(),
        val models: List<LlmModel> = emptyList(),
        val modelsLoading: Boolean = false,
        val modelsError: String? = null,
    )

    private val settings = MertaSettings(app)
    private val agentFiles = AgentFiles(app)
    private val sessions = SessionStore(agentFiles.chatsDir)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var currentTitle = "Новый чат"
    private var nextId = 1L

    init {
        val id = sessions.newId()
        _state.update { it.copy(sessionId = id) }
        refreshConfig()
        refreshSessions()
    }

    /** Перечитать конфиг (после экрана настроек). */
    fun refreshConfig() {
        val cfg = settings.load()
        val label = buildString {
            append(if (cfg.model.isBlank()) "модель не выбрана" else cfg.model)
            if (!cfg.effort.isNullOrBlank()) append(" · ${cfg.effort}")
        }
        _state.update { it.copy(configured = cfg.isConfigured, modelLabel = label) }
        if (_state.value.messages.isEmpty()) {
            val hint = if (cfg.isConfigured) {
                "Готов. Спроси что-нибудь — отвечу через ${cfg.model}."
            } else {
                "Открой параметры (шестерёнка справа вверху) и введи endpoint, API-ключ и модель."
            }
            push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, hint))
        }
    }

    // ---------- сессии ----------

    fun refreshSessions() {
        _state.update { it.copy(sessions = sessions.list()) }
    }

    fun newChat() {
        cancelStream(keepPartial = false)
        currentTitle = "Новый чат"
        nextId = 1L
        _state.update { it.copy(sessionId = sessions.newId(), messages = emptyList(), streaming = null, sending = false) }
        refreshConfig()
        refreshSessions()
    }

    fun openSession(id: String) {
        if (_state.value.sending) return
        cancelStream(keepPartial = false)
        val meta = sessions.list().find { it.id == id } ?: return
        val loaded = sessions.load(id)
        currentTitle = meta.title
        nextId = (loaded.maxOfOrNull { it.id } ?: 0) + 1
        _state.update { it.copy(sessionId = id, messages = loaded, streaming = null) }
    }

    fun deleteSession(id: String) {
        sessions.delete(id)
        if (id == _state.value.sessionId) newChat() else refreshSessions()
    }

    // ---------- модели ----------

    fun refreshModels() {
        val cfg = settings.load()
        if (cfg.apiKey.isBlank()) {
            _state.update { it.copy(modelsError = "Сначала введи API-ключ.") }
            return
        }
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { makeProvider(cfg).listModels() }
                    .sortedBy { it.displayName.lowercase() }
                _state.update { it.copy(models = list, modelsLoading = false) }
            } catch (e: LlmException) {
                _state.update { it.copy(modelsLoading = false, modelsError = describeError(e)) }
            }
        }
    }

    // ---------- отправка ----------

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        val cfg = settings.load()
        if (!cfg.isConfigured) {
            push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "Сначала настрой endpoint, ключ и модель в параметрах."))
            return
        }
        cancelStream(keepPartial = false)
        push(ChatMessage(nextId(), ChatMessage.Role.USER, trimmed))

        val system = agentFiles.loadSystemPrompt()
        val history = buildList {
            if (system.isNotBlank()) add(LlmMessage("system", system))
            addAll(
                _state.value.messages
                    .filter { it.role != ChatMessage.Role.SYSTEM }
                    .map {
                        LlmMessage(
                            role = if (it.role == ChatMessage.Role.USER) "user" else "assistant",
                            content = it.text,
                        )
                    },
            )
        }
        val provider = makeProvider(cfg)
        _state.update { it.copy(sending = true, streaming = "") }
        streamJob = viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) {
                    provider.streamChat(LlmRequest(cfg.model, history, cfg.effort)) { delta ->
                        _state.update { s -> s.copy(streaming = (s.streaming ?: "") + delta) }
                    }
                }
                _state.update { it.copy(streaming = null, sending = false) }
                if (full.isNotBlank()) push(ChatMessage(nextId(), ChatMessage.Role.ASSISTANT, full))
            } catch (e: LlmException) {
                _state.update { it.copy(streaming = null, sending = false) }
                if (e.status != CANCELLED) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, describeError(e)))
                }
            }
            persist()
        }
    }

    /** Остановить стрим; [keepPartial] — сохранить настримленное как ответ. */
    fun cancelStream(keepPartial: Boolean = true) {
        streamJob?.cancel()
        streamJob = null
        val partial = _state.value.streaming
        _state.update { it.copy(streaming = null, sending = false) }
        if (keepPartial && !partial.isNullOrBlank()) {
            push(ChatMessage(nextId(), ChatMessage.Role.ASSISTANT, partial))
            persist()
        }
    }

    private fun makeProvider(cfg: MertaSettings.LlmConfig): LlmStreamingProvider {
        val headers = if (cfg.baseUrl.trimEnd('/') == MertaSettings.Presets.OPENROUTER) {
            OpenAiCompatClient.openRouterHeaders()
        } else {
            emptyMap()
        }
        return OpenAiCompatClient(cfg.baseUrl, cfg.apiKey, headers)
    }

    private fun persist() {
        val s = _state.value
        val firstUser = s.messages.firstOrNull { it.role == ChatMessage.Role.USER }?.text
        if (firstUser != null && currentTitle == "Новый чат") {
            currentTitle = SessionStore.titleFromPrompt(firstUser)
        }
        if (s.messages.any { it.role == ChatMessage.Role.USER }) {
            sessions.save(s.sessionId, currentTitle, s.messages)
            refreshSessions()
        }
    }

    private fun describeError(e: LlmException): String = when (e.status) {
        401 -> "Ошибка 401: неверный API-ключ. Проверь ключ в параметрах."
        404 -> "Ошибка 404: endpoint или модель не найдены. Проверь URL и имя модели."
        429 -> "Ошибка 429: квота/лимит провайдера. Подожди или смени модель."
        -1 -> e.message ?: "Ошибка сети."
        else -> "Ошибка ${e.status}: ${e.message}"
    }

    private fun push(msg: ChatMessage) {
        _state.update { it.copy(messages = it.messages + msg) }
    }

    private fun nextId(): Long = nextId++

    override fun onCleared() {
        cancelStream(keepPartial = false)
    }

    companion object {
        const val CANCELLED = -2
    }
}
