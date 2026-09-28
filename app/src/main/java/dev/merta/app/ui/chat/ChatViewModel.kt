package dev.merta.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.merta.app.data.agent.AgentFiles
import dev.merta.app.data.chat.SessionStore
import dev.merta.app.data.llm.LlmException
import dev.merta.app.data.llm.LlmModel
import dev.merta.app.data.llm.LlmStreamingProvider
import dev.merta.app.data.llm.OpenAiCompatClient
import dev.merta.app.data.llm.TurnMessage
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.data.settings.Provider
import dev.merta.app.data.tools.PendingApproval
import dev.merta.app.data.tools.ToolRegistry
import dev.merta.app.data.workspace.FileGatewayImpl
import dev.merta.app.data.workspace.WorkspaceScope
import dev.merta.app.data.workspace.WorkspaceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** Модели одного провайдера для группированного пикера. */
data class ProviderGroup(val provider: Provider, val models: List<LlmModel>)

/**
 * Чат: стрим Direct API, история сессий, каталог моделей по провайдерам.
 * Системный промт (`merta/system.md`) подмешивается первым system-сообщением.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        /** Накапливающийся стрим текущего ответа (null — стрима нет). */
        val streaming: String? = null,
        val sending: Boolean = false,
        val configured: Boolean = false,
        val providerName: String = "",
        val modelDisplayName: String = "",
        val effort: String? = null,
        val sessionId: String = "",
        val sessions: List<SessionStore.SessionMeta> = emptyList(),
        val groups: List<ProviderGroup> = emptyList(),
        val modelsLoading: Boolean = false,
        val modelsError: String? = null,
        /** Ожидающее подтверждение деструктивного вызова (диалог). */
        val pendingApproval: PendingApproval? = null,
    )

    private val settings = MertaSettings(app)
    private val agentFiles = AgentFiles(app)
    private val sessions = SessionStore(agentFiles.chatsDir)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var currentTitle = "Новый чат"
    private var nextId = 1L
    private var approvalGate: CompletableDeferred<Boolean>? = null
    /** Последний известный скоуп папок (для системного сообщения — модель должна знать все корни). */
    private val scopeState = MutableStateFlow<WorkspaceScope?>(null)

    private fun toolRegistry(): ToolRegistry {
        val app = getApplication<Application>()
        val files = AgentFiles(app)
        val store = WorkspaceStore(app, files.workspaceDir.absolutePath)
        return ToolRegistry(FileGatewayImpl(store), files.workspaceDir.absolutePath)
    }

    /** Ответ пользователя в диалоге подтверждения инструмента. */
    fun approveTool(allow: Boolean) {
        _state.update { it.copy(pendingApproval = null) }
        approvalGate?.complete(allow)
        approvalGate = null
    }

    private suspend fun waitApproval(approval: PendingApproval): Boolean {
        val gate = CompletableDeferred<Boolean>()
        approvalGate = gate
        _state.update { it.copy(pendingApproval = approval) }
        return try {
            gate.await()
        } catch (_: Exception) {
            false
        } finally {
            _state.update { it.copy(pendingApproval = null) }
        }
    }

    init {
        val id = sessions.newId()
        _state.update { it.copy(sessionId = id) }
        refreshConfig()
        refreshSessions()
        viewModelScope.launch {
            WorkspaceStore(getApplication(), AgentFiles(getApplication()).workspaceDir.absolutePath)
                .scopeFlow.collect { scopeState.value = it }
        }
    }

    /** Перечитать конфиг (после настроек/пикеров). */
    fun refreshConfig() {
        val provider = settings.activeProvider()
        val effort = settings.load().effort
        if (provider == null || !provider.hasKey) {
            _state.update {
                it.copy(
                    configured = false,
                    providerName = provider?.name ?: "",
                    modelDisplayName = "",
                    effort = effort,
                )
            }
        } else {
            val modelId = settings.selectedModel(provider.id)
            val label = settings.displayNameFor(provider.id, modelId)
            _state.update {
                it.copy(
                    configured = modelId.isNotBlank(),
                    providerName = provider.name,
                    modelDisplayName = label,
                    effort = effort,
                )
            }
        }
        if (_state.value.messages.isEmpty()) {
            val s = _state.value
            val hint = if (s.configured) {
                "Готов. Спроси что-нибудь — отвечу через ${s.modelDisplayName}."
            } else {
                "Открой параметры и добавь провайдера с API-ключом, затем выбери модель."
            }
            push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, hint))
        }
    }

    fun setEffort(effort: String?) {
        val cfg = settings.load()
        settings.save(cfg.copy(effort = effort))
        refreshConfig()
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

    /** Обновить каталог по всем провайдерам с ключами (параллельно), закэшировать имена. */
    fun refreshModels() {
        val providers = settings.loadProviders().filter { it.hasKey }
        if (providers.isEmpty()) {
            _state.update { it.copy(modelsError = "Ни у одного провайдера нет ключа — добавь в параметрах.") }
            return
        }
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            try {
                val groups = supervisorScope {
                    providers.map { p ->
                        async(Dispatchers.IO) {
                            try {
                                val list = makeProvider(p).listModels()
                                    .sortedBy { it.displayName.lowercase() }
                                ProviderGroup(p, list)
                            } catch (e: LlmException) {
                                ProviderGroup(p, emptyList())
                            }
                        }
                    }.awaitAll()
                }.filter { it.models.isNotEmpty() }
                // Кэш имён для подписей пузыря без сети.
                val cache = settings.modelsNamesCache().toMutableMap()
                for (g in groups) {
                    cache[g.provider.id] = g.models.associate { it.id to it.displayName }
                }
                settings.saveModelsNamesCache(cache)
                _state.update {
                    it.copy(
                        groups = groups,
                        modelsLoading = false,
                        modelsError = if (groups.isEmpty()) "Каталог пуст — проверь ключи и сеть." else null,
                    )
                }
                refreshConfig()
            } catch (e: Exception) {
                _state.update { it.copy(modelsLoading = false, modelsError = e.message ?: "Ошибка сети.") }
            }
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        settings.setActiveProvider(providerId)
        settings.setSelectedModel(providerId, modelId)
        refreshConfig()
    }

    // ---------- отправка ----------

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        val provider = settings.activeProvider()
        if (provider == null || !provider.hasKey) {
            push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "Выбери провайдера с ключом и модель — пузырь под заголовком."))
            return
        }
        val modelId = settings.selectedModel(provider.id)
        if (modelId.isBlank()) {
            push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "Выбери модель — пузырь под заголовком."))
            return
        }
        cancelStream(keepPartial = false)
        push(ChatMessage(nextId(), ChatMessage.Role.USER, trimmed))

        // Транскрипт для API: системный промт + пользователь/ассистент
        // (SYSTEM-строки ленты — только отображение, в запрос не идут).
        // К системному добавляем динамический список разрешённых папок:
        // модель обязана знать ВСЕ корни, иначе сидит только в дефолтной.
        val wsDir = AgentFiles(getApplication()).workspaceDir.absolutePath
        val roots = scopeState.value?.allowedRoots?.takeIf { it.isNotEmpty() } ?: listOf(wsDir)
        val system = agentFiles.loadSystemPrompt() +
            "\n\nРазрешённые папки:\n" + roots.joinToString("\n") { "- $it" } +
            "\nРабочая папка по умолчанию: $wsDir. " +
            "Относительные пути (hello.txt) резолвятся от неё; " +
            "файлы в других разрешённых папках открывай по АБСОЛЮТНОМУ пути. " +
            "Сначала list_dir по нужной папке — не выдумывай содержимое."
        val transcript = mutableListOf<TurnMessage>()
        if (system.isNotBlank()) transcript.add(TurnMessage("system", system))
        transcript.addAll(
            _state.value.messages
                .filter { it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.ASSISTANT }
                // Хвост истории: без усечения длинные сессии раздувают тело и ловят 400/лимиты.
                .takeLast(MAX_HISTORY)
                .map {
                    TurnMessage(
                        role = if (it.role == ChatMessage.Role.USER) "user" else "assistant",
                        content = it.text,
                    )
                },
        )
        val client = makeProvider(provider) as OpenAiCompatClient
        val effort = settings.load().effort
        val registry = toolRegistry()
        val thinkStart = System.currentTimeMillis()
        val thoughtId = nextId()
        push(ChatMessage(thoughtId, ChatMessage.Role.THINKING, "", ThoughtData(active = true, startedMs = thinkStart)))
        _state.update { it.copy(sending = true, streaming = "") }
        streamJob = viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) {
                    client.runAgent(
                        modelId, transcript, effort, registry,
                        cb = object : OpenAiCompatClient.AgentCallbacks {
                            override fun onDelta(text: String) {
                                _state.update { s -> s.copy(streaming = (s.streaming ?: "") + text) }
                            }

                            override fun onReasoning(text: String) {
                                appendReasoning(thoughtId, text)
                            }

                            override fun onToolStart(name: String, summary: String) {
                                // Tool-вызов виден своим пузырём — в Thought не дублируем.
                                push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "⚙ " + summary))
                            }

                            override suspend fun onApproval(approval: PendingApproval): Boolean {
                                addThinkStep(thoughtId, "ожидание: " + approval.summary)
                                return waitApproval(approval)
                            }
                        },
                    )
                }
                finishThought(thoughtId, System.currentTimeMillis() - thinkStart)
                _state.update { it.copy(streaming = null, sending = false) }
                if (full.isNotBlank()) push(ChatMessage(nextId(), ChatMessage.Role.ASSISTANT, full))
            } catch (e: LlmException) {
                _state.update { it.copy(streaming = null, sending = false, pendingApproval = null) }
                if (e.status != CANCELLED) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, describeError(e)))
                }
                finishThought(thoughtId, System.currentTimeMillis() - thinkStart)
            }
            persist()
        }
    }

    /** Остановить стрим; [keepPartial] — сохранить настримленное как ответ. */
    fun cancelStream(keepPartial: Boolean = true) {
        streamJob?.cancel()
        streamJob = null
        val partial = _state.value.streaming
        val now = System.currentTimeMillis()
        _state.update { st ->
            st.copy(
                streaming = null,
                sending = false,
                messages = st.messages.map { m ->
                    val t = m.thought
                    if (t != null && t.active) {
                        m.copy(thought = t.copy(active = false, lastMs = now - t.startedMs))
                    } else {
                        m
                    }
                },
            )
        }
        if (keepPartial && !partial.isNullOrBlank()) {
            push(ChatMessage(nextId(), ChatMessage.Role.ASSISTANT, partial))
            persist()
        }
    }

    fun makeProvider(p: Provider): LlmStreamingProvider {
        val headers = if (p.baseUrl.trimEnd('/') == MertaSettings.Presets.OPENROUTER) {
            OpenAiCompatClient.openRouterHeaders()
        } else {
            emptyMap()
        }
        return OpenAiCompatClient(p.baseUrl, p.apiKey, headers)
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
        401 -> "Ошибка 401: неверный API-ключ. Проверь ключ провайдера."
        404 -> "Ошибка 404: endpoint или модель не найдены."
        429 -> "Ошибка 429: квота/лимит провайдера. Подожди или смени модель."
        -1 -> e.message ?: "Ошибка сети."
        else -> "Ошибка ${e.status}: ${e.message}"
    }


    private fun addThinkStep(thoughtId: Long, step: String) {
        _state.update { st ->
            st.copy(
                messages = st.messages.map { m ->
                    if (m.id == thoughtId && m.thought != null) {
                        m.copy(thought = m.thought.copy(steps = m.thought.steps + step))
                    } else {
                        m
                    }
                },
            )
        }
    }

    private fun finishThought(thoughtId: Long, tookMs: Long) {
        _state.update { st ->
            st.copy(
                messages = st.messages.map { m ->
                    if (m.id == thoughtId && m.thought != null) {
                        m.copy(thought = m.thought.copy(active = false, lastMs = tookMs))
                    } else {
                        m
                    }
                },
            )
        }
    }

    /** Доклеить кусок reasoning-стрима в Thought (с обрезкой хвоста). */
    private fun appendReasoning(thoughtId: Long, chunk: String) {
        _state.update { st ->
            st.copy(
                messages = st.messages.map { m ->
                    if (m.id == thoughtId && m.thought != null) {
                        m.copy(thought = m.thought.copy(reasoning = (m.thought.reasoning + chunk).takeLast(MAX_THOUGHT_CHARS)))
                    } else {
                        m
                    }
                },
            )
        }
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

        /** Хвост reasoning, хранимый в Thought (память + JSON истории). */
        const val MAX_THOUGHT_CHARS = 4000

        /** Хвост переписки, уходящий в запрос (старое отрезаем). */
        const val MAX_HISTORY = 40
    }
}
