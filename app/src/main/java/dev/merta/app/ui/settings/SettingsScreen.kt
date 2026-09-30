package dev.merta.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.data.agent.AgentFiles
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.data.settings.MertaSettings.Presets
import dev.merta.app.data.wallpaper.WallpaperRepository
import dev.merta.app.data.workspace.WorkspaceStore
import dev.merta.app.adb.ShizukuOps
import kotlinx.coroutines.flow.StateFlow
import dev.merta.app.ui.sessions.MetroSmallButton
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable
import kotlinx.coroutines.launch

/**
 * Параметры: провайдер, агент (system.md, skills, mcp), рабочая папка, обновления.
 * Ключи провайдеров — только в шифрованном хранилище, правятся на экране провайдеров.
 */
@Composable
fun SettingsScreen(
    settings: MertaSettings,
    agentFiles: AgentFiles,
    workspace: WorkspaceStore,
    wallpaper: WallpaperRepository,
    shizukuStatus: StateFlow<ShizukuOps.ShizukuStatus>,
    onRefreshShizuku: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenProviders: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var systemPrompt by remember { mutableStateOf(agentFiles.loadSystemPrompt()) }
    var manualPath by remember { mutableStateOf("") }
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // All-files доступ нужен для обычных путей (/sdcard/...) — без него только внутренняя папка.
    val hasAllFiles = remember(resumeTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
    }
    val agentStatus = remember { agentFiles.status() }
    val ws by workspace.scopeFlow.collectAsState(initial = null)
    var providerTick by remember { mutableStateOf(0) }
    val activeProvider = remember(resumeTick, providerTick) { settings.activeProvider() }
    var showEditActive by remember { mutableStateOf(false) }
    var editActiveName by remember(activeProvider?.id, showEditActive) { mutableStateOf(activeProvider?.name ?: "") }
    var editActiveUrl by remember(activeProvider?.id, showEditActive) { mutableStateOf(activeProvider?.baseUrl ?: "") }
    var editActiveKey by remember(activeProvider?.id, showEditActive) { mutableStateOf(activeProvider?.apiKey ?: "") }
    LaunchedEffect(Unit) { onRefreshShizuku() }

    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        scope.launch { workspace.addRoot(uri.toString()) }
    }

    BackHandler {
        if (showEditActive) {
            showEditActive = false
        } else {
            onBack()
        }
    }

    fun save() {
        agentFiles.saveSystemPrompt(systemPrompt)
        onSaved()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "ПАРАМЕТРЫ",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.Light,
                    fontSize = 24.sp,
                    letterSpacing = 2.sp,
                    color = scheme.text,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(scheme.accent),
                )
            }
            MetroSmallButton("×", onClick = onBack)
        }

        SectionLabel("ПРОВАЙДЕР")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radius))
                .background(scheme.glass)
                .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                activeProvider?.name ?: "не выбран",
                fontFamily = MetroFonts.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = scheme.text,
            )
            val keyStatus = when {
                activeProvider == null -> "провайдер не выбран"
                activeProvider.isAgy && activeProvider.apiKey.isNotBlank() -> "токен введён"
                activeProvider.isAgy -> "без токена (открытый демон)"
                activeProvider.apiKey.isNotBlank() -> "ключ введён"
                else -> "без ключа"
            }
            val keyColor = when {
                activeProvider?.apiKey?.isNotBlank() == true -> scheme.accent
                activeProvider?.isAgy == true -> scheme.textDim
                else -> scheme.red
            }
            Text(
                (activeProvider?.baseUrl ?: "") + " · " + keyStatus,
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                color = keyColor,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(scheme.glassHover)
                        .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                        .metroClickable(targetScale = 0.97f, onClick = onOpenModels)
                        .padding(vertical = 10.dp),
                ) {
                    Text("Модели", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(scheme.glassHover)
                        .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                        .metroClickable(targetScale = 0.97f, onClick = onOpenProviders)
                        .padding(vertical = 10.dp),
                ) {
                    Text("Провайдеры", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
                }
                if (activeProvider != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                            .background(if (showEditActive) scheme.accent.copy(alpha = 0.35f) else scheme.glassHover)
                            .border(1.dp, if (showEditActive) scheme.accent else scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                            .metroClickable(targetScale = 0.97f, onClick = {
                                editActiveName = activeProvider.name
                                editActiveUrl = activeProvider.baseUrl
                                editActiveKey = activeProvider.apiKey
                                showEditActive = !showEditActive
                            })
                            .padding(vertical = 10.dp),
                    ) {
                        Text(if (showEditActive) "Свернуть" else "Изменить", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
                    }
                }
            }

            if (showEditActive && activeProvider != null) {
                Spacer(Modifier.height(6.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    MetroField(
                        value = editActiveName,
                        onChange = { editActiveName = it },
                        hint = "Название",
                    )
                    MetroField(
                        value = editActiveUrl,
                        onChange = { editActiveUrl = it },
                        hint = if (activeProvider.isAgy) Presets.AGY_DAEMON else "https://…/v1",
                    )
                    MetroField(
                        value = editActiveKey,
                        onChange = { editActiveKey = it },
                        hint = if (activeProvider.isAgy) "Токен демона (если задан secret)" else "API-ключ",
                        secret = true,
                    )
                    if (activeProvider.isAgy) {
                        Text(
                            text = "Токен демона показывает установщик ~/bin/merta-agy. Если демон открыт (без secret), оставь поле пустым.",
                            fontFamily = MetroFonts.text,
                            fontSize = 11.sp,
                            color = scheme.textDim,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                .background(scheme.accent.copy(alpha = 0.85f))
                                .metroClickable(targetScale = 0.97f) {
                                    if (editActiveName.isNotBlank() && editActiveUrl.isNotBlank()) {
                                        val updated = activeProvider.copy(
                                            name = editActiveName.trim(),
                                            baseUrl = editActiveUrl.trim().trimEnd('/'),
                                            apiKey = editActiveKey.trim(),
                                        )
                                        settings.saveProviders(
                                            settings.loadProviders().map { if (it.id == updated.id) updated else it }
                                        )
                                        providerTick++
                                        onSaved()
                                        showEditActive = false
                                    }
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text("СОХРАНИТЬ", fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                        }
                        MetroSmallButton("×") { showEditActive = false }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("АГЕНТ")
        MetroField(
            value = systemPrompt,
            onChange = { systemPrompt = it },
            hint = "Системный промт…",
            minLines = 4,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "system.md · ${systemPrompt.length} симв. · skills: ${agentStatus.skills.size} · mcp: ${agentStatus.mcpServers.size}",
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
        )
        Text(
            agentFiles.root.absolutePath,
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
        )
        if (agentStatus.skills.isNotEmpty()) {
            Text(
                "Скиллы: " + agentStatus.skills.joinToString { it.name },
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                color = scheme.textDim,
            )
        }
        Text(
            "сбросить к умолчанию",
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.accent,
            modifier = Modifier.metroClickable(targetScale = 0.97f) {
                systemPrompt = AgentFiles.DEFAULT_SYSTEM
            },
        )
        Spacer(Modifier.height(6.dp))
        ShizukuRow(
            status = shizukuStatus.collectAsState().value,
            onRefresh = onRefreshShizuku,
            onRequest = onRequestShizuku,
        )

        Spacer(Modifier.height(10.dp))
        GraphifySection(
            workspaceRoot = ws?.allowedRoots?.firstOrNull(),
            settings = settings,
        )

        Spacer(Modifier.height(10.dp))
        SkillsMcpSection(
            agentFiles = agentFiles,
            settings = settings,
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("РАБОЧАЯ ПАПКА")
        val roots = ws?.allowedRoots ?: emptyList()
        if (roots.isEmpty()) {
            Text(
                "Папок нет — агент работает без файлов. Добавь папку ниже.",
                fontFamily = MetroFonts.text,
                fontSize = 14.sp,
                color = scheme.textDim,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        for (root in roots) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.glass)
                    .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    shortRoot(root),
                    fontFamily = MetroFonts.text,
                    fontSize = 13.sp,
                    color = scheme.text,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                MetroSmallButton("×") {
                    scope.launch { workspace.removeRoot(root) }
                }
            }
        }
        if (!hasAllFiles) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.red.copy(alpha = 0.85f))
                    .metroClickable(targetScale = 0.97f) {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + context.packageName),
                                ),
                            )
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                    .padding(vertical = 12.dp),
            ) {
                Text("ДОСТУП КО ВСЕМ ФАЙЛАМ", fontFamily = MetroFonts.text, fontSize = 14.sp, letterSpacing = 1.5.sp, color = Color.White)
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.glassHover)
                .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                .metroClickable(targetScale = 0.97f) { safLauncher.launch(null) }
                .padding(vertical = 12.dp),
        ) {
            Text("+ ДОБАВИТЬ ПАПКУ", fontFamily = MetroFonts.text, fontSize = 14.sp, letterSpacing = 1.5.sp, color = scheme.text)
        }
        Spacer(Modifier.height(6.dp))
        MetroField(
            value = manualPath,
            onChange = { manualPath = it },
            hint = "/sdcard/Download/code…",
        )
        Spacer(Modifier.height(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.glassHover)
                .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                .metroClickable(targetScale = 0.97f) {
                    val p = manualPath.trim()
                    if (p.isNotEmpty() && !p.startsWith("content://")) {
                        scope.launch {
                            workspace.addRoot(p)
                            manualPath = ""
                        }
                    }
                }
                .padding(vertical = 12.dp),
        ) {
            Text("+ ДОБАВИТЬ ПУТЬ", fontFamily = MetroFonts.text, fontSize = 14.sp, letterSpacing = 1.5.sp, color = scheme.text)
        }
        Text(
            "Агент работает только в этих папках. Для путей /sdcard/… выдай доступ ко всем файлам.",
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "Запрещено: " + (ws?.deniedPatterns?.joinToString() ?: "…"),
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("ОБОИ")
        WallpaperSection(wallpaper)

        Spacer(Modifier.height(16.dp))
        SectionLabel("ОБНОВЛЕНИЯ")
        UpdatesSection(settings)

        Spacer(Modifier.height(16.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.accent.copy(alpha = 0.85f))
                .metroClickable(targetScale = 0.97f, onClick = ::save)
                .padding(vertical = 14.dp),
        ) {
            Text("СОХРАНИТЬ", fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 1.5.sp, color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Ключи провайдеров хранятся только в шифрованном хранилище телефона.",
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** Строка статуса Shizuku: установка APK, пакеты, тапы. */
@Composable
private fun ShizukuRow(
    status: ShizukuOps.ShizukuStatus,
    onRefresh: () -> Unit,
    onRequest: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val text = when (status) {
        ShizukuOps.ShizukuStatus.READY -> "Shizuku: готов"
        ShizukuOps.ShizukuStatus.NOT_RUNNING -> "Shizuku: не запущен"
        ShizukuOps.ShizukuStatus.NOT_AUTHORIZED -> "Shizuku: нет разрешения"
        ShizukuOps.ShizukuStatus.NOT_INSTALLED -> "Shizuku: недоступен"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = if (status == ShizukuOps.ShizukuStatus.READY) scheme.accent else scheme.textDim,
            modifier = Modifier.weight(1f),
        )
        if (status == ShizukuOps.ShizukuStatus.NOT_AUTHORIZED) {
            MetroSmallButton("Разрешить", onClick = onRequest)
        } else {
            MetroSmallButton("\uF01E", onClick = onRefresh)
        }
    }
}

/** Короткое имя корня: для SAF — последний сегмент, для путей — хвост. */
private fun shortRoot(root: String): String {
    if (root.startsWith("content://")) {
        return try {
            Uri.decode(root.substringAfterLast("%2F", root.substringAfterLast('/')))
        } catch (_: Exception) {
            root
        }
    }
    return root
}

@Composable
private fun SectionLabel(text: String) {
    val scheme = LocalMetroScheme.current
    Text(text, fontFamily = MetroFonts.text, fontSize = 12.sp, letterSpacing = 1.5.sp, color = scheme.textDim)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun MetroField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    minLines: Int = 1,
    secret: Boolean = false,
) {
    val scheme = LocalMetroScheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(scheme.glassHover)
            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.text),
            cursorBrush = SolidColor(scheme.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            singleLine = minLines == 1,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.textDim)
                inner()
            },
        )
    }
}

@Composable
private fun GraphifySection(
    workspaceRoot: String?,
    settings: MertaSettings,
) {
    val scheme = LocalMetroScheme.current
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radius))
            .background(scheme.glass)
            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "GRAPHIFY",
                fontFamily = MetroFonts.headline,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.accent,
                modifier = Modifier.weight(1f),
            )
            Text(
                "AST-граф",
                fontFamily = MetroFonts.text,
                fontSize = 11.sp,
                color = scheme.textDim,
            )
        }
        Text(
            text = "Контекстный граф проекта для агента: query, path, explain, update. Позволяет агенту мгновенно исследовать архитектуру без расхода токенов LLM.",
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
        )
        Text(
            text = "Установка в Termux: ~/bin/merta install-graphify",
            fontFamily = MetroFonts.text,
            fontSize = 11.sp,
            color = scheme.textDim,
        )
        if (statusText != null) {
            Text(
                text = statusText!!,
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                color = if (statusText!!.startsWith("Ошибка")) scheme.red else scheme.text,
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(if (isRunning) scheme.glassHover else scheme.accent.copy(alpha = 0.22f))
                .border(1.dp, scheme.accent.copy(alpha = 0.5f), RoundedCornerShape(MetroDimens.radiusSmall))
                .metroClickable(targetScale = 0.96f) {
                    if (isRunning) return@metroClickable
                    if (workspaceRoot.isNullOrBlank()) {
                        statusText = "Сначала добавь рабочую папку в настройках ниже."
                        return@metroClickable
                    }
                    val agy = settings.loadProviders().find { it.isAgy }
                    if (agy == null) {
                        statusText = "Провайдер Agy не настроен (нужен для запуска в Termux)."
                        return@metroClickable
                    }
                    isRunning = true
                    statusText = "Индексация кодовой базы через Graphify…"
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val client = dev.merta.app.bridge.AgyDaemonClient(agy.baseUrl, token = agy.apiKey)
                            val res = client.shell("graphify \"$workspaceRoot\"", timeoutS = 90)
                            statusText = if (res.code == 0) {
                                "Граф успешно построен!"
                            } else {
                                "Завершено (код ${res.code}): ${res.output.take(120)}"
                            }
                        } catch (e: Exception) {
                            statusText = "Ошибка: ${e.message?.take(100)}"
                        } finally {
                            isRunning = false
                        }
                    }
                }
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = if (isRunning) "ИНДЕКСАЦИЯ…" else "ПОСТРОИТЬ / ОБНОВИТЬ ГРАФ",
                fontFamily = MetroFonts.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                color = scheme.text,
            )
        }
    }
}

@Composable
private fun SkillsMcpSection(
    agentFiles: AgentFiles,
    settings: MertaSettings,
) {
    val scheme = LocalMetroScheme.current
    var skillsTick by remember { mutableStateOf(0) }
    val skills = remember(skillsTick) { agentFiles.listSkills() }
    val mcpServers = remember(skillsTick) { agentFiles.listMcpServers() }
    val disabledSkills = remember(skillsTick) { settings.loadDisabledSkills() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radius))
            .background(scheme.glass)
            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "НАВЫКИ И MCP",
                fontFamily = MetroFonts.headline,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                "skills: ${skills.size} · mcp: ${mcpServers.size}",
                fontFamily = MetroFonts.text,
                fontSize = 11.sp,
                color = scheme.textDim,
            )
        }

        if (skills.isEmpty()) {
            Text(
                text = "Навыки не установлены. Добавь папку со SKILL.md в files/merta/skills/<name>/",
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                color = scheme.textDim,
            )
        } else {
            for (skill in skills) {
                val isEnabled = !disabledSkills.contains(skill.dirName)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(if (isEnabled) scheme.glassHover else scheme.glass)
                        .border(
                            1.dp,
                            if (isEnabled) scheme.accent.copy(alpha = 0.4f) else scheme.stroke,
                            RoundedCornerShape(MetroDimens.radiusSmall),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = skill.name,
                            fontFamily = MetroFonts.text,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = scheme.text,
                        )
                        if (skill.description.isNotBlank()) {
                            Text(
                                text = skill.description,
                                fontFamily = MetroFonts.text,
                                fontSize = 11.sp,
                                color = scheme.textDim,
                                maxLines = 2,
                            )
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                            .background(if (isEnabled) scheme.accent.copy(alpha = 0.22f) else scheme.glass)
                            .border(
                                1.dp,
                                if (isEnabled) scheme.accent else scheme.stroke,
                                RoundedCornerShape(MetroDimens.radiusSmall),
                            )
                            .metroClickable(targetScale = 0.92f) {
                                settings.setSkillEnabled(skill.dirName, !isEnabled)
                                skillsTick++
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (isEnabled) "ВКЛ" else "ВЫКЛ",
                            fontFamily = MetroFonts.text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEnabled) scheme.accent else scheme.textDim,
                        )
                    }
                }
            }
        }

        if (mcpServers.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "MCP-серверы: " + mcpServers.joinToString(),
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                color = scheme.textDim,
            )
        }
    }
}

