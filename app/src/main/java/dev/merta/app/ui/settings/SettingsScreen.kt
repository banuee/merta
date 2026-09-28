package dev.merta.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.data.agent.AgentFiles
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.data.wallpaper.WallpaperRepository
import dev.merta.app.data.workspace.WorkspaceStore
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
    val activeProvider = remember { settings.activeProvider() }

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
            Text(
                (activeProvider?.baseUrl ?: "") + " · " +
                    if (activeProvider?.hasKey == true) "ключ введён" else "без ключа",
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                color = scheme.textDim,
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

        Spacer(Modifier.height(16.dp))
        SectionLabel("РАБОЧАЯ ПАПКА")
        val roots = ws?.allowedRoots ?: emptyList()
        if (roots.isEmpty()) {
            Text("Загрузка…", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.textDim)
        }
        for (root in roots) {
            val removable = root != agentFiles.workspaceDir.absolutePath
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
                if (removable) {
                    MetroSmallButton("×") {
                        scope.launch { workspace.removeRoot(root) }
                    }
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
            "Внутренняя папка видна только приложению — для работы с проводником добавь /sdcard/… и выдай доступ.",
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
            visualTransformation = VisualTransformation.None,
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
