package dev.merta.app.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.data.agent.AgentFiles
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.data.workspace.WorkspaceStore
import dev.merta.app.ui.sessions.MetroSmallButton
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable
import kotlinx.coroutines.launch

/**
 * Параметры: провайдер, модель + effort, агент (system.md, skills, mcp),
 * рабочая папка (скоупы FileGateway). Ключ — только в шифрованном хранилище.
 */
@Composable
fun SettingsScreen(
    settings: MertaSettings,
    agentFiles: AgentFiles,
    workspace: WorkspaceStore,
    onOpenModels: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initial = remember { settings.load() }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var effort by remember { mutableStateOf(initial.effort) }
    var systemPrompt by remember { mutableStateOf(agentFiles.loadSystemPrompt()) }
    val agentStatus = remember { agentFiles.status() }
    val ws by workspace.scopeFlow.collectAsState(initial = null)

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

    fun pickPreset(url: String) {
        baseUrl = url
        val def = MertaSettings.Presets.defaultModelFor(url)
        if (model.isBlank() && def.isNotBlank()) model = def
    }

    fun save() {
        settings.save(MertaSettings.LlmConfig(baseUrl, apiKey, model, effort))
        agentFiles.saveSystemPrompt(systemPrompt)
        onSaved()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(scheme.accent),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(scheme.accent),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "ПАРАМЕТРЫ",
                fontFamily = MetroFonts.headline,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                letterSpacing = 2.sp,
                color = scheme.text,
                modifier = Modifier.weight(1f),
            )
            MetroSmallButton("×", onClick = onBack)
        }

        SectionLabel("ENDPOINT")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip("OpenRouter", baseUrl == MertaSettings.Presets.OPENROUTER) {
                pickPreset(MertaSettings.Presets.OPENROUTER)
            }
            PresetChip("Zen", baseUrl == MertaSettings.Presets.ZEN) {
                pickPreset(MertaSettings.Presets.ZEN)
            }
        }
        Spacer(Modifier.height(8.dp))
        MetroField(value = baseUrl, onChange = { baseUrl = it }, hint = "https://…/v1")

        Spacer(Modifier.height(12.dp))
        SectionLabel("API-КЛЮЧ")
        MetroField(value = apiKey, onChange = { apiKey = it }, hint = "sk-or-… / zen-…", secret = true)

        Spacer(Modifier.height(12.dp))
        SectionLabel("МОДЕЛЬ")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                MetroField(value = model, onChange = { model = it }, hint = "например mimo-v2.5-free")
            }
            Spacer(Modifier.width(8.dp))
            MetroSmallButton("≣", onClick = onOpenModels)
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("EFFORT (REASONING)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip("Выкл", effort == null) { effort = null }
            for (e in MertaSettings.Efforts.ALL) {
                PresetChip(e, effort == e) { effort = e }
            }
        }
        Text(
            "Effort поддерживается OpenRouter-моделями; для Zen оставь «Выкл».",
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
            modifier = Modifier.padding(top = 4.dp),
        )

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
        Text(
            "Запрещено: " + (ws?.deniedPatterns?.joinToString() ?: "…"),
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
            modifier = Modifier.padding(top = 6.dp),
        )

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
            "Ключ хранится только в шифрованном хранилище телефона.",
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
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = LocalMetroScheme.current
    val border = if (selected) scheme.accent else scheme.stroke
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(if (selected) scheme.accent.copy(alpha = 0.22f) else scheme.glass)
            .border(1.dp, border, RoundedCornerShape(MetroDimens.radiusSmall))
            .metroClickable(targetScale = 0.93f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
    }
}

@Composable
private fun MetroField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    secret: Boolean = false,
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
