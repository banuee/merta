package dev.merta.app.ui.chat

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
        val autoApprove: Boolean = false,
        val sessionId: String = "",
        val sessions: List<SessionStore.SessionMeta> = emptyList(),
        val groups: List<ProviderGroup> = emptyList(),
        val modelsLoading: Boolean = false,
        val modelsError: String? = null,
        /** Ожидающее подтверждение деструктивного вызова (диалог). */
        val pendingApproval: PendingApproval? = null,
        /** Демон merta-agy недоступен (баннер с командой запуска). */
        val daemonDown: Boolean = false,
        /** Короткая сводка демона (версия agy, патч, rish). */
        val daemonNote: String = "",
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
    /** Текущий Thought хода (для onReasoning/onApproval между onTurnStart). */
    private var currentThoughtId: Long? = null
    private var currentThoughtStart = 0L

    private fun toolRegistry(): ToolRegistry {
        val app = getApplication<Application>()
        val store = WorkspaceStore(app)
        // Дефолт для относительных путей — первая папка пользователя;
        // пусто — относительные резолвить не во что, gateway вернёт подсказку.
        val defaultWorkdir = scopeState.value?.allowedRoots?.firstOrNull() ?: ""
        val agy = settings.loadProviders().find { it.isAgy }
        val daemon: (suspend (String, Int) -> ToolRegistry.DaemonShellResult)? =
            if (agy == null || !daemonState.value.alive) {
                null
            } else {
                { cmd, t ->
                    try {
                        val r = dev.merta.app.bridge.AgyDaemonClient(agy.baseUrl).shell(cmd, t)
                        ToolRegistry.DaemonShellResult(r.code, r.output)
                    } catch (e: LlmException) {
                        if (e.status == -1) {
                            daemonState.value = DaemonInfo(alive = false, note = "упал во время вызова")
                            _state.update { it.copy(daemonDown = true) }
                        }
                        throw e
                    }
                }
            }
        return ToolRegistry(
            FileGatewayImpl(store), defaultWorkdir,
            settings.loadAutoApprove(), dev.merta.app.adb.ShizukuOpsImpl(app), daemon,
        )
    }

    /** Состояние демона для баннера и маршрутизации инструментов. */
    data class DaemonInfo(val alive: Boolean = false, val note: String = "")
    val daemonState = MutableStateFlow(DaemonInfo())

    /** Статус Shizuku для настроек (обновляется по запросу). */
    val shizukuStatus = MutableStateFlow(dev.merta.app.adb.ShizukuOps.ShizukuStatus.NOT_RUNNING)

    fun refreshShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shizukuStatus.value = dev.merta.app.adb.ShizukuOpsImpl(getApplication()).status()
            } catch (_: Exception) {
            }
        }
    }

    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }

    fun requestShizukuPermission() {
        try {
            dev.merta.app.adb.ShizukuOpsImpl(getApplication()).requestPermission(SHIZUKU_PERM_CODE)
        } catch (_: Exception) {
        }
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
            val app = getApplication<Application>()
            val store = WorkspaceStore(app)
            // Миграция: внутренняя filesDir-папка исключена из скоупа —
            // выкидываем её из сохранённых корней один раз.
            val internalWs = java.io.File(AgentFiles(app).root, "workspace").absolutePath
            if (store.scope().allowedRoots.any { it == internalWs }) {
                store.removeRoot(internalWs)
            }
            store.scopeFlow.collect { scopeState.value = it }
        }
        // Проверка демона при входе: патч-слет → автопатч, демона нет → баннер.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dev.merta.app.adb.ShizukuOpsImpl(getApplication()).addPermissionListener(shizukuPermListener)
            } catch (_: Exception) {
            }
        }
        recheckDaemon()
    }

    /** Проверка демона: статус, автопатч при слетевшем патче, баннер если мёртв. */
    fun recheckDaemon() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val agy = settings.loadProviders().find { it.isAgy } ?: return@launch
                val daemon = dev.merta.app.bridge.AgyDaemonClient(agy.baseUrl)
                val st = daemon.status()
                if (!st.alive) {
                    daemonState.value = DaemonInfo(alive = false, note = "не отвечает")
                    _state.update { it.copy(daemonDown = true, daemonNote = "") }
                    return@launch
                }
                val note = buildString {
                    append(st.agyVersion.ifBlank { "agy" })
                    append(if (st.patchCode == 0) " · патч ок" else " · патч: код ${st.patchCode}")
                    append(if (st.rishOk) " · rish ок" else " · rish нет")
                }
                daemonState.value = DaemonInfo(alive = true, note = note)
                _state.update { it.copy(daemonDown = false, daemonNote = note) }
                if (st.patchCode == 1) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "agy: патч слетел, перепатчиваю…"))
                    try {
                        val (code, _) = daemon.patch()
                        push(
                            ChatMessage(
                                nextId(), ChatMessage.Role.SYSTEM,
                                if (code == 0) "agy: перепатчен, можно работать." else "agy: не перепатчился (code $code).",
                            ),
                        )
                    } catch (e: LlmException) {
                        push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "agy: не перепатчился: ${e.message}"))
                    }
                }
            } catch (_: Exception) {
                daemonState.value = DaemonInfo(alive = false, note = "ошибка проверки")
                _state.update { it.copy(daemonDown = true) }
            }
        }
    }

    /** Команда установки демона в буфер обмена (из баннера). */
    fun copyDaemonCommand() {
        val app = getApplication<Application>()
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("merta-agy", DAEMON_INSTALL_CMD))
    }

    /** Открыть Termux для ручного подъёма демона (из баннера). */
    fun openTermux() {
        val app = getApplication<Application>()
        try {
            val i = app.packageManager.getLaunchIntentForPackage("com.termux") ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(i)
        } catch (_: Exception) {
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
            // Agy без модели — валидно (модель по умолчанию в CLI).
            val label = if (modelId.isBlank() && provider.isAgy) {
                "agy · по умолчанию"
            } else {
                settings.displayNameFor(provider.id, modelId)
            }
            _state.update {
                it.copy(
                    configured = modelId.isNotBlank() || provider.isAgy,
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
        _state.update { it.copy(autoApprove = settings.loadAutoApprove()) }
    }

    fun toggleAutoApprove() {
        settings.saveAutoApprove(!settings.loadAutoApprove())
        refreshConfig()
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
                                val list = if (p.isAgy) {
                                    // Каталог из CLI (нужна авторизация), иначе фолбэк
                                    // + выбранная вручную модель, чтобы не терялась.
                                    val daemon = dev.merta.app.bridge.AgyDaemonClient(p.baseUrl)
                                    val live = daemon.models()
                                    val sel = settings.selectedModel(p.id)
                                    val merged = (live + dev.merta.app.bridge.AgyModels.FALLBACK)
                                        .distinctBy { it.id }
                                    if (sel.isNotBlank() && merged.none { it.id == sel }) {
                                        merged + LlmModel(sel, settings.displayNameFor(p.id, sel))
                                    } else {
                                        merged
                                    }
                                } else {
                                    makeProvider(p).listModels()
                                        .sortedBy { it.displayName.lowercase() }
                                }
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
        if (!checkConfigured()) return
        cancelStream(keepPartial = false)
        push(ChatMessage(nextId(), ChatMessage.Role.USER, trimmed))
        startTurn()
    }

    /**
     * Изменить своё сообщение и отправить заново: всё после него выкидывается
     * из ленты (память модели откатывается), текст заменяется, ход идёт по новой.
     */
    fun editAndResend(id: Long, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        if (!checkConfigured()) return
        cancelStream(keepPartial = false)
        val msgs = _state.value.messages
        val idx = msgs.indexOfFirst { it.id == id && it.role == ChatMessage.Role.USER }
        if (idx < 0) return
        val kept = msgs.take(idx).toMutableList()
        kept.add(msgs[idx].copy(text = trimmed))
        _state.update { it.copy(messages = kept, streaming = null) }
        startTurn()
    }

    private fun checkConfigured(): Boolean {
        val provider = settings.activeProvider()
        if (provider == null || !provider.hasKey) {
            push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "Выбери провайдера с ключом и модель — пузырь под заголовком."))
            return false
        }
        if (settings.selectedModel(provider.id).isBlank() && !provider.isAgy) {
            push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "Выбери модель — пузырь под заголовком."))
            return false
        }
        return true
    }

    /** Ход агента: последнее USER-сообщение в ленте уже лежит, стрим идёт с чистого листа. */
    private fun startTurn() {
        val provider = settings.activeProvider() ?: return
        val modelId = settings.selectedModel(provider.id)

        // Транскрипт для API: системный промт + пользователь/ассистент
        // (SYSTEM/THINKING-строки ленты — только отображение, в запрос не идут).
        // К системному добавляем динамический список разрешённых папок:
        // модель обязана знать ВСЕ корни, иначе сидит только в дефолтной.
        val roots = scopeState.value?.allowedRoots?.takeIf { it.isNotEmpty() }
        val system = agentFiles.loadSystemPrompt() + if (roots == null) {
            "\n\nРазрешённых папок нет — попроси пользователя добавить папку " +
                "в Параметрах (Рабочая папка) и пока работай без файлов."
        } else {
            "\n\nРазрешённые папки:\n" + roots.joinToString("\n") { "- $it" } +
                "\nРабочая папка по умолчанию: ${roots.first()}. " +
                "Относительные пути (hello.txt) резолвятся от неё; " +
                "файлы в других разрешённых папках открывай по АБСОЛЮТНОМУ пути. " +
                "Сначала list_dir по нужной папке — не выдумывай содержимое."
        }
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
        if (provider.isAgy) {
            startAgyTurn(provider, transcript, effort)
            return
        }
        val registry = toolRegistry()
        currentThoughtId = null
        _state.update { it.copy(sending = true, streaming = "") }
        streamJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client.runAgent(
                        modelId, transcript, effort, registry,
                        cb = object : OpenAiCompatClient.AgentCallbacks {
                            override fun onDelta(text: String) {
                                _state.update { s -> s.copy(streaming = (s.streaming ?: "") + text) }
                            }

                            override fun onReasoning(text: String) {
                                currentThoughtId?.let { appendReasoning(it, text) }
                            }

                            override fun onTurnStart() {
                                newTurn()
                            }

                            override fun onToolStart(name: String, summary: String) {
                                // Tool-вызов виден своим пузырём — в Thought не дублируем.
                                push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "⚙ " + summary))
                            }

                            override suspend fun onApproval(approval: PendingApproval): Boolean {
                                currentThoughtId?.let { addThinkStep(it, "ожидание: " + approval.summary) }
                                return waitApproval(approval)
                            }
                        },
                    )
                }
                // Запечатать последний ход: стрим — в пузырь, Thought — готов.
                sealTurn()
                _state.update { it.copy(streaming = null, sending = false) }
            } catch (e: LlmException) {
                _state.update { it.copy(streaming = null, sending = false, pendingApproval = null) }
                if (e.status != CANCELLED) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, describeError(e)))
                }
                currentThoughtId?.let { finishThought(it, System.currentTimeMillis() - currentThoughtStart) }
                currentThoughtId = null
            }
            persist()
        }
    }

    /**
     * Граница хода агентного цикла: текущий стрим запечатывается отдельным
     * пузырём ответа, текущий Thought завершается, открываются новые.
     */
    private fun newTurn() {
        sealTurn()
        val now = System.currentTimeMillis()
        currentThoughtStart = now
        val id = nextId()
        currentThoughtId = id
        push(ChatMessage(id, ChatMessage.Role.THINKING, "", ThoughtData(active = true, startedMs = now)))
    }

    /** Запечатать текущий ход: непустой стрим — в пузырь, Thought — готов. */
    private fun sealTurn() {
        val now = System.currentTimeMillis()
        val s = _state.value.streaming
        val thought = currentThoughtId
        currentThoughtId = null
        _state.update { it.copy(streaming = "") }
        if (!s.isNullOrBlank()) {
            push(ChatMessage(nextId(), ChatMessage.Role.ASSISTANT, s))
        }
        if (thought != null) {
            finishThought(thought, now - currentThoughtStart)
        }
    }

    /** conversation_id agy по сессиям чата (память между ходов — резум беседы). */
    private val agyConversations = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun agyProvider(): Provider? = settings.loadProviders().find { it.isAgy }

    /**
     * Ход через Antigravity CLI (демон в proot).
     * Контекст держит сам agy (resume по conversation_id): первый ход шлём
     * system + вопрос, дальше — только новые вопросы. Каждый tool-шаг agy —
     * отдельным пузырём, как в openai-ветке.
     */
    private fun startAgyTurn(provider: Provider, transcript: List<TurnMessage>, effort: String?) {
        val lastUser = transcript.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
        if (lastUser.isEmpty()) return
        val sessionId = _state.value.sessionId
        val system = transcript.firstOrNull { it.role == "system" }?.content.orEmpty()
        val convId = agyConversations[sessionId]
        // Первый ход беседы: к системному добавляем доку по устройству (мост /shell).
        val prompt = if (convId == null && system.isNotBlank()) {
            "$system$AGY_DEVICE_SECTION\n\n$lastUser"
        } else {
            lastUser
        }
        val model = settings.selectedModel(provider.id).ifBlank { null }
        val dirs = scopeState.value?.allowedRoots
            ?.filter { it.startsWith("/") && !it.startsWith("content://") } ?: emptyList()
        val daemon = dev.merta.app.bridge.AgyDaemonClient(provider.baseUrl)
        currentThoughtId = null
        _state.update { it.copy(sending = true, streaming = "") }
        streamJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var thoughtOpened = false
                    fun needThought() {
                        if (!thoughtOpened) {
                            thoughtOpened = true
                            newTurn()
                        }
                    }
                    daemon.runStream(
                        dev.merta.app.bridge.AgyDaemonClient.AgyRun(
                            prompt = prompt,
                            conversationId = convId,
                            model = model,
                            effort = effort,
                            yolo = settings.loadAutoApprove(),
                            dirs = dirs,
                        ),
                    ) { ev ->
                        when (ev) {
                            is dev.merta.app.bridge.AgyStreamJson.AgyEvent.Init -> {
                                if (ev.conversationId.isNotBlank()) {
                                    agyConversations[sessionId] = ev.conversationId
                                }
                            }
                            is dev.merta.app.bridge.AgyStreamJson.AgyEvent.Delta -> {
                                needThought()
                                _state.update { s -> s.copy(streaming = (s.streaming ?: "") + ev.text) }
                            }
                            is dev.merta.app.bridge.AgyStreamJson.AgyEvent.Tool -> {
                                if (!ev.done || currentThoughtId == null) {
                                    // Старт вызова (или DONE без ACTIVE): свежий Thought
                                    // с аргументами + пузырь. Результат придёт шагом в DONE.
                                    sealTurn()
                                    newTurn()
                                    currentThoughtId?.let { addThinkStep(it, ev.details) }
                                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "⚙ " + ev.details))
                                    thoughtOpened = true
                                } else if (ev.output.isNotBlank()) {
                                    currentThoughtId?.let { addThinkStep(it, "→ " + ev.output.take(300)) }
                                }
                            }
                            is dev.merta.app.bridge.AgyStreamJson.AgyEvent.Done -> {
                                needThought()
                                if (ev.conversationId.isNotBlank()) {
                                    agyConversations[sessionId] = ev.conversationId
                                }
                                // Стрим уже показал текст — дубли не пушим, только новое.
                                val streamed = _state.value.streaming.orEmpty()
                                val r = ev.response.trim()
                                sealTurn()
                                if (r.isNotBlank()) {
                                    val probe = r.take(60)
                                    if (streamed.isBlank()) {
                                        push(ChatMessage(nextId(), ChatMessage.Role.ASSISTANT, r))
                                    } else if (!streamed.contains(probe) && !r.contains(streamed.take(60).trim())) {
                                        push(ChatMessage(nextId(), ChatMessage.Role.ASSISTANT, r))
                                    }
                                }
                                if (ev.denied.isNotEmpty()) {
                                    push(
                                        ChatMessage(
                                            nextId(), ChatMessage.Role.SYSTEM,
                                            "agy: отклонено (нужен auto): " + ev.denied.joinToString(", "),
                                        ),
                                    )
                                }
                            }
                            is dev.merta.app.bridge.AgyStreamJson.AgyEvent.Error -> {
                                throw LlmException(-1, agyErrorText(ev.message))
                            }
                        }
                    }
                }
                _state.update { it.copy(streaming = null, sending = false) }
            } catch (e: LlmException) {
                _state.update { it.copy(streaming = null, sending = false, pendingApproval = null) }
                if (e.status != CANCELLED) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, describeError(e)))
                }
                currentThoughtId?.let { finishThought(it, System.currentTimeMillis() - currentThoughtStart) }
                currentThoughtId = null
            }
            persist()
        }
    }

    private fun agyErrorText(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "authentication" in lower -> "agy: нужна авторизация — в Termux: proot-distro login …, затем agy (войти в аккаунт)."
            "busy" in lower -> "agy: предыдущий запрос ещё идёт — дождись и повтори."
            "недоступен" in lower -> raw
            else -> "agy: $raw"
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
        try {
            dev.merta.app.adb.ShizukuOpsImpl(getApplication()).removePermissionListener(shizukuPermListener)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CANCELLED = -2
        const val SHIZUKU_PERM_CODE = 5101

        /** Установка/обновление демона одной командой в Termux. */
        const val DAEMON_INSTALL_CMD =
            "curl -fsSL https://raw.githubusercontent.com/banuee/merta/main/bridge/install-phone.sh | bash"

        /**
         * Устройство для agy-сессий: CLI работает в Linux-proot, а команды
         * ТЕЛЕФОНА идут через мост /shell (Shizuku-права, постоянный доступ).
         * Дописывается к системному промту на первом ходу беседы.
         */
        const val AGY_DEVICE_SECTION =
            "\n\nУСТРОЙСТВО (ты работаешь прямо на телефоне; твои shell-команды идут " +
                "в Linux-окружение proot, а команды ТЕЛЕФОНА — через мост, curl-ом):\n" +
                "- shell телефона: curl -s -X POST 127.0.0.1:18080/shell " +
                "-H \"Content-Type: application/json\" -d '{\"command\":\"ТВОЯ КОМАНДА\"}'\n" +
                "- установить APK: {\"command\":\"pm install -r /sdcard/Download/файл.apk\"}\n" +
                "- тап/свайп: {\"command\":\"input tap X Y\"}, " +
                "{\"command\":\"input swipe X1 Y1 X2 Y2 300\"}\n" +
                "- пакеты: {\"command\":\"pm list packages часть.имени\"}, " +
                "открыть приложение: {\"command\":\"monkey -p имя.пакета " +
                "-c android.intent.category.LAUNCHER 1\"}\n" +
                "- пути телефона: /sdcard/… (= /storage/emulated/0). " +
                "Рабочие папки из запроса уже проброшены тебе через --add-dir."

        /** Хвост reasoning, хранимый в Thought (память + JSON истории). */
        const val MAX_THOUGHT_CHARS = 4000

        /** Хвост переписки, уходящий в запрос (старое отрезаем). */
        const val MAX_HISTORY = 40
    }
}
