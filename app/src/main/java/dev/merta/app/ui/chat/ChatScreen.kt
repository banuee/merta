package dev.merta.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroAnimations
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable

/**
 * Главный экран: шапка Metro, пузыри модели/effort, лента, ввод.
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit,
    onNewChat: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val ui by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var showEffort by remember { mutableStateOf(false) }

    if (showEffort) {
        EffortDialog(
            current = ui.effort,
            onPick = {
                vm.setEffort(it)
                showEffort = false
            },
            onDismiss = { showEffort = false },
        )
    }

    val approval = ui.pendingApproval
    if (approval != null) {
        ApproveDialog(
            toolName = approval.toolName,
            summary = approval.summary,
            preview = approval.preview,
            onAllow = { vm.approveTool(true) },
            onDeny = { vm.approveTool(false) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "MERTA",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.Light,
                    fontSize = 24.sp,
                    letterSpacing = 2.sp,
                    color = scheme.text,
                )
                Spacer(Modifier.height(6.dp))
                // Маленький пилл под надписью (как в остальных приложениях).
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(scheme.accent),
                )
            }
            HeaderIcon(glyph = "", onClick = onNewChat)
            HeaderIcon(glyph = "", onClick = onOpenSessions)
            HeaderIcon(glyph = "", onClick = onOpenSettings)
        }

        // Пузыри модели и effort.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            val modelText = when {
                !ui.configured && ui.providerName.isBlank() -> "выбрать провайдера"
                !ui.configured -> "выбрать модель"
                else -> ui.modelDisplayName
            }
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(
                        if (ui.configured) scheme.accent.copy(alpha = 0.22f)
                        else scheme.glass,
                    )
                    .border(
                        1.dp,
                        if (ui.configured) scheme.accent.copy(alpha = 0.45f) else scheme.stroke,
                        RoundedCornerShape(MetroDimens.radiusSmall),
                    )
                    .metroClickable(targetScale = 0.97f, onClick = onOpenModels)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = modelText,
                    fontFamily = MetroFonts.text,
                    fontSize = 13.sp,
                    color = scheme.text,
                    maxLines = 1,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.glass)
                    .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                    .metroClickable(targetScale = 0.93f) { showEffort = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = ui.effort ?: "effort",
                    fontFamily = MetroFonts.text,
                    fontSize = 13.sp,
                    color = if (ui.effort != null) scheme.accent else scheme.textDim,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.messages, key = { it.id }) { msg ->
                MessageBubble(msg.role, msg.text, Modifier.animateItem())
            }
            if (ui.streaming != null) {
                item(key = "streaming") {
                    MessageBubble(ChatMessage.Role.ASSISTANT, ui.streaming + "▍")
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.glassHover)
                    .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(
                        fontFamily = MetroFonts.text,
                        fontSize = 15.sp,
                        color = scheme.text,
                    ),
                    cursorBrush = SolidColor(scheme.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text("Спросить…", fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.textDim)
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.accent.copy(alpha = 0.85f))
                    .metroClickable(targetScale = 0.88f) {
                        vm.send(input)
                        input = ""
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text("→", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

/** Nerd-иконка в шапке (тач-таргет ~44dp). */
@Composable
fun HeaderIcon(glyph: String, onClick: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .metroClickable(targetScale = 0.88f, onClick = onClick)
            .padding(12.dp),
    ) {
        Text(
            text = glyph,
            fontFamily = MetroFonts.icon,
            fontSize = 20.sp,
            color = scheme.textDim,
        )
    }
}

/** Маленький диалог выбора effort (reasoning). */@Composable
private fun EffortDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(MetroDimens.panelRadius))
                .background(scheme.glassDeep)
                .border(1.dp, scheme.strokeStrong, RoundedCornerShape(MetroDimens.panelRadius))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("EFFORT", fontFamily = MetroFonts.text, fontSize = 12.sp, letterSpacing = 1.5.sp, color = scheme.textDim)
            AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EffortRow("Выкл", current == null) { onPick(null) }
                    for (e in MertaSettings.Efforts.ALL) {
                        EffortRow(e, current == e) { onPick(e) }
                    }
                }
            }
            Text(
                "Уровень рассуждений (OpenRouter). Для Zen — «Выкл».",
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                color = scheme.textDim,
            )
        }
    }
}

@Composable
private fun EffortRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(if (selected) scheme.accent.copy(alpha = 0.22f) else scheme.glass)
            .border(
                1.dp,
                if (selected) scheme.accent else scheme.stroke,
                RoundedCornerShape(MetroDimens.radiusSmall),
            )
            .metroClickable(targetScale = 0.97f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(label, fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.text)
    }
}

@Composable
private fun MessageBubble(role: ChatMessage.Role, text: String, modifier: Modifier = Modifier) {
    val scheme = LocalMetroScheme.current
    val bubble = when (role) {
        ChatMessage.Role.USER -> scheme.accent.copy(alpha = 0.22f)
        ChatMessage.Role.ASSISTANT -> scheme.glass
        ChatMessage.Role.SYSTEM -> Color.Transparent
    }
    val border = when (role) {
        ChatMessage.Role.USER -> scheme.accent.copy(alpha = 0.45f)
        else -> scheme.stroke
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radius))
            .background(bubble)
            .border(1.dp, border, RoundedCornerShape(MetroDimens.radius))
            .padding(10.dp),
    ) {
        Text(
            text = text,
            fontFamily = MetroFonts.text,
            fontSize = 14.sp,
            color = if (role == ChatMessage.Role.SYSTEM) scheme.textDim else scheme.text,
        )
    }
}

/** Диалог подтверждения деструктивного вызова (write_file / run_command). */
@Composable
private fun ApproveDialog(toolName: String, summary: String, preview: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Dialog(onDismissRequest = { /* только явный выбор */ }) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(MetroDimens.panelRadius))
                .background(scheme.glassDeep)
                .border(1.dp, scheme.red.copy(alpha = 0.5f), RoundedCornerShape(MetroDimens.panelRadius))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("РАЗРЕШИТЬ?", fontFamily = MetroFonts.text, fontSize = 12.sp, letterSpacing = 1.5.sp, color = scheme.red)
            Text(summary, fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.text)
            if (preview.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                        .padding(10.dp),
                ) {
                    Text(
                        preview,
                        fontFamily = MetroFonts.text,
                        fontSize = 12.sp,
                        color = scheme.textDim,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(scheme.glassHover)
                        .metroClickable(targetScale = 0.97f, onClick = onDeny)
                        .padding(vertical = 12.dp),
                ) {
                    Text("Отклонить", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(scheme.red)
                        .metroClickable(targetScale = 0.97f, onClick = onAllow)
                        .padding(vertical = 12.dp),
                ) {
                    Text("Разрешить", fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                }
            }
        }
    }
}
