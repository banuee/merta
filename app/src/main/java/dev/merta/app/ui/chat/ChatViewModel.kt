package dev.merta.app.ui.chat

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
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
        /** Процент расхода контекста текущей модели (0-100%). Предупреждение при >= 75%. */
        val contextPercent: Int = 0,
        /** Shizuku отключён / упал (контекстная плашка для быстрого перезапуска). */
        val shizukuWarning: Boolean = false,
    )

    private val settings = MertaSettings(app)
    private val agentFiles = AgentFiles(app)
    private val sessions = SessionStore(agentFiles.chatsDir)
    /** conversation_id agy по сессиям чата (память между ходов — резум беседы). */
    private val agyConversations = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val persistMutex = kotlinx.coroutines.sync.Mutex()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    /** Активные сетевые клиенты текущего хода — рвём в cancelStream. */
    private var activeClient: OpenAiCompatClient? = null
    private var activeAgy: dev.merta.app.bridge.AgyDaemonClient? = null
    private var currentTitle = "Новый чат"
    private var nextId = 1L
    private var approvalGate: CompletableDeferred<Boolean>? = null
    /** Последний известный скоуп папок (для системного сообщения — модель должна знать все корни). */
    private val scopeState = MutableStateFlow<WorkspaceScope?>(null)
    /** Текущий Thought хода (для onReasoning/onApproval между onTurnStart). */
    @Volatile
    private var currentThoughtId: Long? = null
    @Volatile
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
                        val r = dev.merta.app.bridge.AgyDaemonClient(agy.baseUrl, token = agy.apiKey).shell(cmd, t)
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

    /** Токены текущей сессии (ходы обеих веток). Сброс при смене/создании чата. */
    val usageState = MutableStateFlow(dev.merta.app.data.chat.SessionUsage())

    /** Лимиты agy (`/usage`), с временем замера. null — не запрашивали/нет. */
    data class QuotaData(val groups: List<dev.merta.app.bridge.QuotaGroup>, val fetchedAt: Long)
    val quotaState = MutableStateFlow<QuotaData?>(null)
    /** Идёт ли запрос лимитов (индикатор в шторке). */
    val quotaLoading = MutableStateFlow(false)
    /** Ошибка последнего запроса лимитов (показ в шторке вместо вечного хинта). */
    val quotaError = MutableStateFlow<String?>(null)
    private val quotaFetching = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Статус Shizuku для настроек (обновляется по запросу и фоновым поллером). */
    val shizukuStatus = MutableStateFlow(dev.merta.app.adb.ShizukuOps.ShizukuStatus.NOT_RUNNING)

    private val shizukuReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        refreshShizuku()
        _state.update { it.copy(shizukuWarning = false) }
    }

    private val shizukuDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        shizukuStatus.value = dev.merta.app.adb.ShizukuOps.ShizukuStatus.NOT_RUNNING
        _state.update { it.copy(shizukuWarning = true) }
    }

    fun refreshShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val st = dev.merta.app.adb.ShizukuOpsImpl(getApplication()).status()
                shizukuStatus.value = st
                val wasReady = _state.value.shizukuWarning || st == dev.merta.app.adb.ShizukuOps.ShizukuStatus.READY
                _state.update { it.copy(shizukuWarning = st == dev.merta.app.adb.ShizukuOps.ShizukuStatus.NOT_RUNNING && wasReady) }
            } catch (_: Exception) {
            }
        }
    }

    /** Перезапуск Shizuku: через Termux мост / start.sh или запуск приложения Shizuku. */
    fun restartShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            val agy = settings.loadProviders().find { it.isAgy }
            var ok = false
            if (agy != null && daemonState.value.alive) {
                try {
                    val client = dev.merta.app.bridge.AgyDaemonClient(agy.baseUrl, token = agy.apiKey)
                    val r = client.shell(
                        "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh || am start -n moe.shizuku.privileged.api/.ui.MainActivity",
                        timeoutS = 15,
                    )
                    ok = r.code == 0
                } catch (_: Exception) {
                }
            }
            if (!ok) {
                try {
                    val ctx = getApplication<Application>()
                    val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }
                } catch (_: Exception) {
                }
            }
            kotlinx.coroutines.delay(2500)
            refreshShizuku()
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

    /** Выбранные вложения перед отправкой сообщения. */
    val pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())

    fun addAttachments(uris: List<Uri>) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<Attachment>()
            val attDir = java.io.File(context.cacheDir, "attachments").apply { mkdirs() }
            for (uri in uris) {
                try {
                    var name = "file_${System.currentTimeMillis()}"
                    var size = 0L
                    var mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                            if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                        }
                    }
                    val cleanName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val localFile = java.io.File(attDir, "${System.currentTimeMillis()}_$cleanName")
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        localFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    if (size == 0L) size = localFile.length()
                    val isImg = mime.startsWith("image/")
                    val base64 = if (isImg && size < 4 * 1024 * 1024L) {
                        try {
                            val bytes = localFile.readBytes()
                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        } catch (_: Exception) { null }
                    } else null
                    list.add(
                        Attachment(
                            uri = uri.toString(),
                            name = name,
                            mimeType = mime,
                            sizeBytes = size,
                            base64Data = base64,
                            localPath = localFile.absolutePath,
                        )
                    )
                } catch (_: Exception) {
                }
            }
            if (list.isNotEmpty()) {
                pendingAttachments.update { it + list }
            }
        }
    }

    fun removePendingAttachment(index: Int) {
        pendingAttachments.update {
            if (index in it.indices) it.toMutableList().apply { removeAt(index) } else it
        }
    }

    fun clearPendingAttachments() {
        pendingAttachments.value = emptyList()
    }

    fun modelSupportsVision(): Boolean {
        val p = settings.activeProvider() ?: return true
        val m = settings.selectedModel(p.id).lowercase()
        if (m.isBlank()) return true
        if (p.isAgy) {
            return m.contains("gemini") || m.contains("claude") || m.contains("gpt-4") || m.contains("vision")
        }
        return m.contains("gemini") || m.contains("gpt-4o") || m.contains("gpt-4.5") ||
            m.contains("o1") || m.contains("o3") || m.contains("claude-3") ||
            m.contains("vision") || m.contains("-vl") || m.contains("pixtral") ||
            m.contains("minicpm") || m.contains("llava")
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена во время диалога — останавливаем цикл, а не делаем лишние ходы.
            throw e
        } catch (_: Exception) {
            false
        } finally {
            _state.update { it.copy(pendingApproval = null) }
        }
    }

    init {
        val last = sessions.list().firstOrNull()
        if (last != null) {
            val loaded = sessions.load(last.id)
            if (loaded.isNotEmpty()) {
                currentTitle = last.title
                nextId = (loaded.maxOfOrNull { it.id } ?: 0) + 1
                sessions.loadConversationId(last.id)?.let { agyConversations[last.id] = it }
                usageState.value = sessions.loadUsage(last.id) ?: dev.merta.app.data.chat.SessionUsage.estimateFromMessages(loaded)
                _state.update { it.copy(sessionId = last.id, messages = loaded) }
            } else {
                val id = sessions.newId()
                _state.update { it.copy(sessionId = id) }
            }
        } else {
            val id = sessions.newId()
            _state.update { it.copy(sessionId = id) }
        }
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
                val ops = dev.merta.app.adb.ShizukuOpsImpl(getApplication())
                ops.addPermissionListener(shizukuPermListener)
                ops.addBinderReceivedListener(shizukuReceivedListener)
                ops.addBinderDeadListener(shizukuDeadListener)
            } catch (_: Exception) {
            }
        }
        viewModelScope.launch {
            while (isActive) {
                refreshShizuku()
                kotlinx.coroutines.delay(20000)
            }
        }
        recheckDaemon()
    }

    /** Проверка демона: статус, автопатч при слетевшем патче, баннер если мёртв. */
    fun recheckDaemon() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val agy = settings.loadProviders().find { it.isAgy } ?: return@launch
                val daemon = dev.merta.app.bridge.AgyDaemonClient(agy.baseUrl, token = agy.apiKey)
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
        recheckDaemon()
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
        usageState.value = dev.merta.app.data.chat.SessionUsage()
        quotaState.value = null
        quotaLoading.value = false
        quotaError.value = null
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
        sessions.loadConversationId(id)?.let { agyConversations[id] = it }
        usageState.value = sessions.loadUsage(id) ?: dev.merta.app.data.chat.SessionUsage.estimateFromMessages(loaded)
        quotaState.value = null
        quotaLoading.value = false
        quotaError.value = null
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
                                    val daemon = dev.merta.app.bridge.AgyDaemonClient(p.baseUrl, token = p.apiKey)
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
                val pricing = settings.modelsPricingCache().toMutableMap()
                for (g in groups) {
                    cache[g.provider.id] = g.models.associate { it.id to it.displayName }
                    pricing[g.provider.id] = g.models.associate {
                        it.id to "${it.promptPer1M}|${it.completionPer1M}"
                    }
                }
                settings.saveModelsNamesCache(cache)
                settings.saveModelsPricingCache(pricing)
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

    fun activeProviderId(): String = settings.activeProviderId()

    fun selectedModelName(providerId: String): String = settings.selectedModel(providerId)

    fun isAgyActive(): Boolean = settings.activeProvider()?.isAgy == true

    /** Доступные уровни effort для текущей выбранной модели. */
    fun availableEffortsForCurrent(): List<String> {
        val p = settings.activeProvider() ?: return MertaSettings.Efforts.ALL
        val model = settings.selectedModel(p.id)
        if (p.isAgy) {
            val modelObj = _state.value.groups.find { it.provider.id == p.id }
                ?.models?.find { it.id == model }
            if (modelObj != null) return modelObj.supportedEfforts
            if (model.startsWith("gemini") || model.startsWith("gpt-oss")) {
                return dev.merta.app.bridge.AgyModels.AGY_EFFORTS
            }
            return emptyList()
        }
        val modelObj = _state.value.groups.find { it.provider.id == p.id }
            ?.models?.find { it.id == model }
        if (modelObj != null) {
            if (!modelObj.reasoningSupported) return emptyList()
            if (modelObj.supportedEfforts.isNotEmpty()) return modelObj.supportedEfforts
        }
        // Эвристика по ID модели, если каталог ещё не загружен
        if (model.contains("grok", ignoreCase = true) || model.startsWith("x-ai/")) {
            return listOf(
                MertaSettings.Efforts.MINIMAL,
                MertaSettings.Efforts.LOW,
                MertaSettings.Efforts.MEDIUM,
                MertaSettings.Efforts.HIGH,
                MertaSettings.Efforts.XHIGH,
            )
        }
        if (!modelSupportsEffort()) return emptyList()
        return listOf(
            MertaSettings.Efforts.LOW,
            MertaSettings.Efforts.MEDIUM,
            MertaSettings.Efforts.HIGH,
        )
    }

    /** Effort для запроса: null, если модель из каталога явно без reasoning. */
    fun effortForRequest(): String? {
        val e = settings.load().effort ?: return null
        val available = availableEffortsForCurrent()
        if (available.isEmpty()) return null
        if (e !in available) {
            return if (e == MertaSettings.Efforts.MINIMAL) available.first()
            else if (e == MertaSettings.Efforts.XHIGH || e == MertaSettings.Efforts.MAX) available.last()
            else available.find { it == MertaSettings.Efforts.MEDIUM } ?: available.first()
        }
        return e
    }

    /** Поддержка effort текущей моделью (для пометки в меню). */
    fun modelSupportsEffort(): Boolean {
        val p = settings.activeProvider() ?: return true
        val model = settings.selectedModel(p.id)
        if (p.isAgy) {
            val modelObj = _state.value.groups.find { it.provider.id == p.id }
                ?.models?.find { it.id == model }
            if (modelObj != null) return modelObj.reasoningSupported
            return model.startsWith("gemini") || model.startsWith("gpt-oss")
        }
        return _state.value.groups.find { it.provider.id == p.id }
            ?.models?.find { it.id == model }?.reasoningSupported ?: true
    }

    /** Тарифы текущей модели ($ за 1M). Нет — (0, 0). */
    fun pricingForCurrent(): Pair<Double, Double> {
        val p = settings.activeProvider() ?: return 0.0 to 0.0
        return settings.pricingFor(p.id, settings.selectedModel(p.id))
    }

    /**
     * Лимиты agy для панели сессии. Кэш 5 минут (как официальные тулзы),
     * force — мимо кэша. Параллельные запросы схлопываются, на неудачу —
     * один ретрай и текст ошибки (а не вечный «подтяну»).
     */
    fun refreshQuota(force: Boolean = false) {
        val agy = settings.loadProviders().find { it.isAgy } ?: return
        val cached = quotaState.value
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < 5 * 60 * 1000) return
        if (!quotaFetching.compareAndSet(false, true)) return
        quotaLoading.value = true
        quotaError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = dev.merta.app.bridge.AgyDaemonClient(agy.baseUrl, token = agy.apiKey)
                var groups = client.quota()
                if (groups.isEmpty()) {
                    kotlinx.coroutines.delay(2000)
                    groups = client.quota()
                }
                if (groups.isNotEmpty()) {
                    quotaState.value = QuotaData(groups, System.currentTimeMillis())
                } else {
                    quotaError.value = "не подтянулось — жми ⟳"
                }
            } catch (_: Exception) {
                quotaError.value = "не подтянулось — жми ⟳"
            } finally {
                quotaLoading.value = false
                quotaFetching.set(false)
            }
        }
    }

    /** Группа лимитов под текущую модель (claude/gpt → Claude, иначе Gemini). */
    fun quotaForCurrent(): dev.merta.app.bridge.QuotaGroup? {
        val agy = settings.loadProviders().find { it.isAgy } ?: return null
        val model = settings.selectedModel(agy.id)
        return dev.merta.app.bridge.QuotaJson.groupForModel(quotaState.value?.groups.orEmpty(), model)
    }

    // ---------- отправка ----------

    /** true — ход запущен; false — отказ (текст ввода не трогаем). */
    /** true — ход запущен; false — отказ (текст ввода не трогаем). */
    fun send(text: String, attachments: List<Attachment> = pendingAttachments.value): Boolean {
        val trimmed = text.trim()
        val atts = attachments.toList()
        if ((trimmed.isEmpty() && atts.isEmpty()) || _state.value.sending) return false
        if (!checkConfigured()) return false
        cancelStream(keepPartial = false)
        clearPendingAttachments()
        val displayText = trimmed.ifBlank {
            if (atts.size == 1) "Вложение: ${atts.first().name}" else "Вложения: ${atts.size} файлов"
        }
        push(ChatMessage(nextId(), ChatMessage.Role.USER, displayText, attachments = atts))
        startTurn()
        return true
    }

    /** Повторить последний ход (перегенерация ответа модели или повтор после ошибки). */
    fun retryLastTurn() {
        if (_state.value.sending) return
        val msgs = _state.value.messages
        val lastUserIdx = msgs.indexOfLast { it.role == ChatMessage.Role.USER }
        if (lastUserIdx < 0) return
        cancelStream(keepPartial = false)
        val kept = msgs.take(lastUserIdx + 1).toMutableList()
        _state.update { it.copy(messages = kept, streaming = null) }
        agyConversations.remove(_state.value.sessionId)
        startTurn()
    }

    /** Повторить от конкретного сообщения (для меню действий на пузырях). */
    fun retryFromMessage(id: Long) {
        if (_state.value.sending) return
        val msgs = _state.value.messages
        val idx = msgs.indexOfFirst { it.id == id }
        if (idx < 0) return
        cancelStream(keepPartial = false)
        val targetUserIdx = if (msgs[idx].role == ChatMessage.Role.USER) {
            idx
        } else {
            msgs.take(idx).indexOfLast { it.role == ChatMessage.Role.USER }
        }
        if (targetUserIdx >= 0) {
            val kept = msgs.take(targetUserIdx + 1).toMutableList()
            _state.update { it.copy(messages = kept, streaming = null) }
            agyConversations.remove(_state.value.sessionId)
            startTurn()
        }
    }

    fun canRetry(): Boolean {
        val s = _state.value
        return !s.sending && s.messages.any { it.role == ChatMessage.Role.USER }
    }

    /** Лимит контекста текущей модели в токенах. */
    fun contextLimitForCurrent(): Long {
        val p = settings.activeProvider() ?: return 128_000L
        val mId = settings.selectedModel(p.id)
        val mObj = _state.value.groups.find { it.provider.id == p.id }?.models?.find { it.id == mId }
        if (mObj != null && mObj.contextLength > 0L) return mObj.contextLength
        val lower = mId.lowercase()
        return when {
            "gemini-1.5-pro" in lower || "gemini-2.0-pro" in lower || "gemini-3.1-pro" in lower -> 2_000_000L
            "gemini" in lower -> 1_000_000L
            "claude" in lower -> 200_000L
            "deepseek" in lower -> 64_000L
            "gpt-4o" in lower || "gpt-4.5" in lower || "o1" in lower || "o3" in lower -> 128_000L
            else -> 128_000L
        }
    }

    /** Процент использования контекста текущей сессии (0-100%). */
    fun contextUsagePercent(): Int {
        val total = usageState.value.total().takeIf { it > 0 }
            ?: dev.merta.app.data.chat.SessionUsage.estimateFromMessages(_state.value.messages).total()
        val limit = contextLimitForCurrent()
        if (limit <= 0L || total <= 0L) return 0
        return ((total * 100) / limit).toInt().coerceIn(0, 100)
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
        // Серверный agy помнит старый промпт этой беседы — сбрасываем resume,
        // иначе исправление уйдёт новым сообщением в старый контекст.
        agyConversations.remove(_state.value.sessionId)
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
        // Хвост истории: без усечения длинные сессии раздувают тело и ловят 400/лимиты.
        // Режем только по границе USER — хвост с ведущего assistant без своего
        // пользователя строгие провайдеры отвергают 400.
        val history = _state.value.messages
            .filter { it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.ASSISTANT }
            .takeLast(MAX_HISTORY)
        val cut = history.indexOfFirst { it.role == ChatMessage.Role.USER }.takeIf { it >= 0 } ?: 0
        transcript.addAll(
            history.drop(cut)
                .map {
                    TurnMessage(
                        role = if (it.role == ChatMessage.Role.USER) "user" else "assistant",
                        content = it.text,
                        attachments = it.attachments,
                    )
                },
        )
        val client = makeProvider(provider) as? OpenAiCompatClient ?: run {
            push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "Провайдер «${provider.name}» не OpenAI-совместимый."))
            return
        }
        activeClient = client
        // Effort шлём всем провайдерам (протокол у всех OpenAI-совместимый,
        // 400-фолбэк в клиенте срежет неподдерживаемое), кроме моделей,
        // которые в каталоге явно без reasoning.
        val effort = effortForRequest()
        if (provider.isAgy) {
            startAgyTurn(provider, transcript, effort)
            return
        }
        val registry = toolRegistry()
        currentThoughtId = null
        _state.update { it.copy(sending = true, streaming = "") }
        streamJob = viewModelScope.launch {
            val ticker = launch(Dispatchers.Default) {
                while (isActive) {
                    kotlinx.coroutines.delay(5000)
                    persist()
                }
            }
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
                                push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "\uF013 " + summary))
                            }

                            override suspend fun onApproval(approval: PendingApproval): Boolean {
                                currentThoughtId?.let { addThinkStep(it, "ожидание: " + approval.summary) }
                                return waitApproval(approval)
                            }

                            override fun onUsage(inputTokens: Long, outputTokens: Long) {
                                usageState.update { it.add(inputTokens, outputTokens) }
                            }
                        },
                    )
                }
                sealTurn()
            } catch (e: LlmException) {
                if (e.status != CANCELLED) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, describeError(e)))
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "Ошибка: ${e.message}"))
                }
            } finally {
                ticker.cancel()
                sealTurn()
                val now = System.currentTimeMillis()
                _state.update { st ->
                    st.copy(
                        streaming = null,
                        sending = false,
                        pendingApproval = null,
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
                if (usageState.value.total() == 0L && _state.value.messages.isNotEmpty()) {
                    usageState.value = dev.merta.app.data.chat.SessionUsage.estimateFromMessages(_state.value.messages)
                }
                currentThoughtId = null
                activeClient = null
                persist()
            }
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
        val lastUserMsg = _state.value.messages.lastOrNull { it.role == ChatMessage.Role.USER }
        val attNote = lastUserMsg?.attachments?.joinToString("\n") {
            "[Прикреплён файл: ${it.name}, путь: ${it.localPath ?: it.uri} (${it.mimeType})]"
        }.orEmpty()
        val fullLastUser = if (attNote.isNotBlank()) "$lastUser\n\n$attNote" else lastUser
        // Первый ход беседы: к системному добавляем доку по устройству (мост /shell).
        val prompt = if (convId == null && system.isNotBlank()) {
            "$system$AGY_DEVICE_SECTION\n\n$fullLastUser"
        } else {
            fullLastUser
        }
        val model = settings.selectedModel(provider.id).ifBlank { null }
        val dirs = scopeState.value?.allowedRoots
            ?.filter { it.startsWith("/") && !it.startsWith("content://") } ?: emptyList()
        val daemon = dev.merta.app.bridge.AgyDaemonClient(provider.baseUrl, token = provider.apiKey)
        activeAgy = daemon
        currentThoughtId = null
        _state.update { it.copy(sending = true, streaming = "") }
        streamJob = viewModelScope.launch {
            val ticker = launch(Dispatchers.Default) {
                while (isActive) {
                    kotlinx.coroutines.delay(5000)
                    persist()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    var thoughtOpened = false
                    fun needThought() {
                        if (!thoughtOpened) {
                            thoughtOpened = true
                            newTurn()
                        }
                    }
                    var stepCountedIn = 0L
                    var stepCountedOut = 0L
                    var stepCountedThinking = 0L
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
                                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "\uF013 " + ev.details))
                                    thoughtOpened = true
                                } else if (ev.output.isNotBlank()) {
                                    currentThoughtId?.let { addThinkStep(it, "→ " + ev.output.take(300)) }
                                }
                            }
                            is dev.merta.app.bridge.AgyStreamJson.AgyEvent.Usage -> {
                                stepCountedIn += ev.usage.input
                                stepCountedOut += ev.usage.output
                                stepCountedThinking += ev.usage.thinking
                                usageState.update { it.add(ev.usage.input, ev.usage.output, ev.usage.thinking) }
                            }
                            is dev.merta.app.bridge.AgyStreamJson.AgyEvent.Done -> {
                                needThought()
                                if (ev.conversationId.isNotBlank()) {
                                    agyConversations[sessionId] = ev.conversationId
                                }
                                val remIn = (ev.usage.input - stepCountedIn).coerceAtLeast(0L)
                                val remOut = (ev.usage.output - stepCountedOut).coerceAtLeast(0L)
                                val remThinking = (ev.usage.thinking - stepCountedThinking).coerceAtLeast(0L)
                                if (remIn > 0 || remOut > 0 || remThinking > 0) {
                                    usageState.update { it.add(remIn, remOut, remThinking) }
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
                                val remIn = (ev.usage.input - stepCountedIn).coerceAtLeast(0L)
                                val remOut = (ev.usage.output - stepCountedOut).coerceAtLeast(0L)
                                val remThinking = (ev.usage.thinking - stepCountedThinking).coerceAtLeast(0L)
                                if (remIn > 0 || remOut > 0 || remThinking > 0) {
                                    usageState.update { it.add(remIn, remOut, remThinking) }
                                }
                                throw LlmException(-1, agyErrorText(ev.message))
                            }
                        }
                    }
                    sealTurn()
                }
            } catch (e: LlmException) {
                sealTurn()
                if (e.status != CANCELLED) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, describeError(e)))
                }
            } catch (e: Exception) {
                sealTurn()
                if (e !is CancellationException) {
                    push(ChatMessage(nextId(), ChatMessage.Role.SYSTEM, "Ошибка: ${e.message}"))
                }
            } finally {
                ticker.cancel()
                val now = System.currentTimeMillis()
                _state.update { st ->
                    st.copy(
                        streaming = null,
                        sending = false,
                        pendingApproval = null,
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
                if (usageState.value.total() == 0L && _state.value.messages.isNotEmpty()) {
                    usageState.value = dev.merta.app.data.chat.SessionUsage.estimateFromMessages(_state.value.messages)
                }
                currentThoughtId = null
                activeAgy = null
                persist()
            }
        }
    }

    private fun agyErrorText(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "authentication" in lower -> "agy: нужна авторизация — в Termux: proot-distro login …, затем agy (войти в аккаунт)."
            "busy" in lower -> "agy: предыдущий запрос ещё идёт — дождись и повтори."
            "quota" in lower || "exhausted" in lower || "rate" in lower || "429" in lower ||
                "capacity" in lower || "credit" in lower || "limit" in lower || "лимит" in lower || "квота" in lower ->
                "agy: закончилась квота (лимит запросов модели). Смени модель или подожди сброса лимита."
            "недоступен" in lower -> raw
            else -> "agy: $raw"
        }
    }

    /** Остановить стрим; [keepPartial] — сохранить настримленное как ответ. */
    fun cancelStream(keepPartial: Boolean = true) {
        streamJob?.cancel()
        streamJob = null
        // Рвём и сеть, иначе блокирующие execute()/forEachLine висят дальше.
        activeClient?.cancel()
        activeClient = null
        activeAgy?.cancel()
        activeAgy = null
        approvalGate?.cancel()
        approvalGate = null
        // Иначе onReasoning после отмены пишет в уже завершённый Thought.
        currentThoughtId = null
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
            val title = currentTitle
            val msgs = s.messages
            val sessionId = s.sessionId
            val convId = agyConversations[sessionId]
            val currentUsage = if (usageState.value.total() > 0) {
                usageState.value
            } else {
                dev.merta.app.data.chat.SessionUsage.estimateFromMessages(msgs)
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    persistMutex.withLock {
                        sessions.save(sessionId, title, msgs, convId, currentUsage)
                    }
                    withContext(Dispatchers.Main) {
                        refreshSessions()
                    }
                } catch (_: Exception) {
                }
            }
        }
        val ctxPercent = contextUsagePercent()
        _state.update { it.copy(contextPercent = ctxPercent) }
    }

    private fun describeError(e: LlmException): String {
        val msg = e.message.orEmpty()
        val lower = msg.lowercase()
        val isQuota = e.status == 429 || "quota" in lower || "exhausted" in lower || "rate_limit" in lower ||
            "rate limit" in lower || "capacity" in lower || "429" in lower || "лимит" in lower || "квота" in lower
        if (isQuota) {
            return "Ошибка квоты / лимита (429): исчерпан лимит запросов модели. Подожди сброса лимита или смени модель."
        }
        return when (e.status) {
            401 -> "Ошибка 401: неверный API-ключ. Проверь ключ провайдера."
            404 -> "Ошибка 404: endpoint или модель не найдены."
            429 -> "Ошибка 429: квота/лимит провайдера. Подожди или смени модель."
            -1 -> if (msg.isNotBlank()) msg else "Ошибка сети."
            else -> "Ошибка ${e.status}: $msg"
        }
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
        persist()
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
        persist()
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
        persist()
    }

    private fun nextId(): Long = nextId++

    override fun onCleared() {
        cancelStream(keepPartial = true)
        try {
            persist()
        } catch (_: Exception) {
        }
        try {
            val ops = dev.merta.app.adb.ShizukuOpsImpl(getApplication())
            ops.removePermissionListener(shizukuPermListener)
            ops.removeBinderReceivedListener(shizukuReceivedListener)
            ops.removeBinderDeadListener(shizukuDeadListener)
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
