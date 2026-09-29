package dev.merta.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroBlurEffect
import dev.merta.app.ui.theme.metroClickable
import dev.merta.app.ui.theme.metroTapConsume

/**
 * Главный экран: шапка Metro, пузыри модели/effort, лента, ввод.
 * Шторки: свайп влево — сессия (справа), свайп вправо — история (слева).
 */
private enum class Drawer { LEFT, RIGHT }

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val ui by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var showEffort by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    var drawer by remember { mutableStateOf<Drawer?>(null) }
    var dragTotal by remember { mutableStateOf(0f) }
    // Сторона для анимации закрытия: на выходе drawer уже null.
    var lastSide by remember { mutableStateOf(Drawer.RIGHT) }
    if (drawer != null) lastSide = drawer!!
    // Блюр контента под шторкой (0 = нет).
    val blurPx by animateFloatAsState(
        targetValue = if (drawer != null) 48f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "drawer-blur",
    )

    LaunchedEffect(drawer) {
        if (drawer == Drawer.LEFT) vm.refreshSessions()
        if (drawer == Drawer.RIGHT && vm.isAgyActive()) vm.refreshQuota()
    }

    if (editing != null) {
        EditMessageDialog(
            text = editText,
            onChange = { editText = it },
            onSave = {
                val id = editing!!.id
                editing = null
                vm.editAndResend(id, editText)
            },
            onDismiss = { editing = null },
        )
    }

    if (showEffort) {
        EffortDialog(
            current = ui.effort,
            available = vm.availableEffortsForCurrent(),
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

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp)
            .metroBlurEffect(blurPx)
            .pointerInput(drawer) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragTotal = 0f },
                    onDragCancel = { dragTotal = 0f },
                    onHorizontalDrag = { _, amount ->
                        dragTotal += amount
                        if (drawer == null) {
                            if (dragTotal < -90) {
                                drawer = Drawer.RIGHT
                                dragTotal = 0f
                            } else if (dragTotal > 90) {
                                drawer = Drawer.LEFT
                                dragTotal = 0f
                            }
                        } else {
                            if (drawer == Drawer.RIGHT && dragTotal > 90) {
                                drawer = null
                                dragTotal = 0f
                            } else if (drawer == Drawer.LEFT && dragTotal < -90) {
                                drawer = null
                                dragTotal = 0f
                            }
                        }
                    },
                )
            },
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
            HeaderIcon(glyph = "\uF067", onClick = onNewChat)
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
            // Auto-approve write_file/run_command без диалога (красный = осторожно).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(
                        if (ui.autoApprove) scheme.red.copy(alpha = 0.22f)
                        else scheme.glass,
                    )
                    .border(
                        1.dp,
                        if (ui.autoApprove) scheme.red else scheme.stroke,
                        RoundedCornerShape(MetroDimens.radiusSmall),
                    )
                    .metroClickable(targetScale = 0.93f) { vm.toggleAutoApprove() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "auto",
                    fontFamily = MetroFonts.text,
                    fontSize = 13.sp,
                    color = if (ui.autoApprove) scheme.red else scheme.textDim,
                )
            }
        }

        // Баннер демона: мёртв — красная карточка с действиями, жив — тихая сводка.
        if (ui.daemonDown) {
            DaemonDownBanner(
                onCopy = { vm.copyDaemonCommand(); clip.setText(AnnotatedString(ChatViewModel.DAEMON_INSTALL_CMD)) },
                onTermux = { vm.openTermux() },
                onRecheck = { vm.recheckDaemon() },
            )
        } else if (ui.daemonNote.isNotBlank()) {
            Text(
                text = ui.daemonNote,
                fontFamily = MetroFonts.text,
                fontSize = 11.sp,
                color = scheme.textDim,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.messages, key = { it.id }) { msg ->
                val thought = msg.thought
                if (msg.role == ChatMessage.Role.THINKING && thought != null) {
                    ThoughtRow(thought, Modifier.animateItem())
                } else {
                    MessageBubble(
                        msg = msg,
                        showMenu = menuFor == msg.id,
                        onLongPress = { menuFor = msg.id },
                        onDismissMenu = { menuFor = null },
                        onCopy = {
                            clip.setText(AnnotatedString(msg.text))
                            menuFor = null
                        },
                        onEdit = {
                            menuFor = null
                            editText = msg.text
                            editing = msg
                        },
                        canEdit = msg.role == ChatMessage.Role.USER && !ui.sending,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (ui.streaming != null) {
                item(key = "streaming") {
                    MessageBubble(
                        msg = ChatMessage(-1, ChatMessage.Role.ASSISTANT, ui.streaming + "▍"),
                        showMenu = false,
                        onLongPress = {},
                        onDismissMenu = {},
                        onCopy = {},
                        onEdit = {},
                        canEdit = false,
                    )
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
                        if (vm.send(input)) input = ""
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text("→", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }

        // Шторки поверх контента: свайп влево — сессия справа, вправо — история слева.
        val open = drawer
        AnimatedVisibility(
            visible = open != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .metroClickable(targetScale = 1.0f) { drawer = null },
            )
        }
        AnimatedVisibility(
            visible = open != null,
            enter = slideInHorizontally { w -> if (lastSide == Drawer.RIGHT) w else -w } + fadeIn(),
            exit = slideOutHorizontally { w -> if (lastSide == Drawer.RIGHT) w else -w } + fadeOut(),
        ) {
            Box(
                contentAlignment = if (lastSide == Drawer.LEFT) Alignment.CenterStart else Alignment.CenterEnd,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.85f)
                        .metroTapConsume()
                        .pointerInput(drawer) {                            // Тот же детектор, что на контенте: свайп изнутри
                            // панели до контента не долетает, закрываем сами.
                            detectHorizontalDragGestures(
                                onDragEnd = { dragTotal = 0f },
                                onDragCancel = { dragTotal = 0f },
                                onHorizontalDrag = { _, amount ->
                                    dragTotal += amount
                                    if (drawer == Drawer.RIGHT && dragTotal > 90) {
                                        drawer = null
                                        dragTotal = 0f
                                    } else if (drawer == Drawer.LEFT && dragTotal < -90) {
                                        drawer = null
                                        dragTotal = 0f
                                    }
                                },
                            )
                        },
                ) {
                    if (lastSide == Drawer.RIGHT) {  // lastSide: на выходе open уже null
                        SessionDrawer(
                            vm = vm,
                            onOpenSettings = { drawer = null; onOpenSettings() },
                            onOpenModels = { drawer = null; onOpenModels() },
                            onClose = { drawer = null },
                        )
                    } else {
                        HistoryDrawer(
                            sessions = ui.sessions,
                            currentId = ui.sessionId,
                            onOpen = { vm.openSession(it); drawer = null },
                            onDelete = { vm.deleteSession(it) },
                            onNew = { vm.newChat(); drawer = null },
                        )
                    }
                }
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

/** Баннер мёртвого демона: текст + копировать команду / Termux / проверить снова. */
@Composable
private fun DaemonDownBanner(onCopy: () -> Unit, onTermux: () -> Unit, onRecheck: () -> Unit) {
    val scheme = LocalMetroScheme.current
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(scheme.red.copy(alpha = 0.16f))
            .border(1.dp, scheme.red.copy(alpha = 0.6f), RoundedCornerShape(MetroDimens.radiusSmall))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = "Демон merta-agy не отвечает — agy и инструменты устройства недоступны.",
            fontFamily = MetroFonts.text,
            fontSize = 13.sp,
            color = scheme.text,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "В Termux: ~/bin/merta-agy (перезапуск) или полная установка командой ниже.",
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DaemonBtn(if (copied) "скопировано" else "команда", onClick = { onCopy(); copied = true })
            DaemonBtn("termux", onClick = onTermux)
            DaemonBtn("проверить", onClick = onRecheck)
        }
    }
}

@Composable
private fun DaemonBtn(label: String, onClick: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(scheme.glass)
            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
            .metroClickable(targetScale = 0.93f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text = label, fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.text)
    }
}

/** Маленький диалог выбора effort (reasoning). */
@Composable
private fun EffortDialog(
    current: String?,
    available: List<String>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
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
            if (available.isEmpty()) {
                Text(
                    "Эта модель не поддерживает уровень рассуждений (reasoning effort).",
                    fontFamily = MetroFonts.text,
                    fontSize = 12.sp,
                    color = scheme.textDim,
                )
            } else {
                AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EffortRow("Выкл", current == null) { onPick(null) }
                        for (e in available) {
                            EffortRow(e, current == e) { onPick(e) }
                        }
                    }
                }
                Text(
                    "Уровень рассуждений для текущей модели. Если модель не поддерживает переданный уровень — запрос повторится без него.",
                    fontFamily = MetroFonts.text,
                    fontSize = 12.sp,
                    color = scheme.textDim,
                )
            }
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

/** Строка мышления в ленте (как в opencode): раскрывается вниз по тапу. */
@Composable
private fun ThoughtRow(thought: ThoughtData, modifier: Modifier = Modifier) {
    val scheme = LocalMetroScheme.current
    // По завершении (active true→false) сворачиваем — длинное reasoning не распирает ленту.
    var expanded by remember(thought.startedMs, thought.active) { mutableStateOf(thought.active) }
    // Последняя строка reasoning — живой индикатор; шаги — фолбэк.
    val lastReasonLine = remember(thought.reasoning) {
        thought.reasoning.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(120)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radius))
            .background(scheme.glass)
            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
            .metroClickable(targetScale = 0.99f) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (thought.active) {
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = scheme.accent,
                    modifier = Modifier.width(15.dp).height(15.dp),
                )
                Text(
                    text = lastReasonLine ?: thought.steps.lastOrNull() ?: "Думаю…",
                    fontFamily = MetroFonts.text,
                    fontSize = 13.sp,
                    color = scheme.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text("\u25C8", fontFamily = MetroFonts.text, fontSize = 12.sp, color = scheme.accent)
                Text(
                    text = "Thought · " + formatThinkMs(thought.lastMs ?: 0) +
                        (if (thought.steps.isNotEmpty()) " · шагов: " + thought.steps.size else ""),
                    fontFamily = MetroFonts.text,
                    fontSize = 13.sp,
                    color = scheme.textDim,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (thought.reasoning.isNotBlank()) {
                    Text(thought.reasoning, fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.textDim)
                }
                if (thought.reasoning.isBlank() && thought.steps.isEmpty()) {
                    Text("Пока тихо.", fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.textDim)
                }
                for (step in thought.steps) {
                    Text(step, fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.textDim)
                }
            }
        }
    }
}

/** Пузырь сообщения: лонгпресс — меню (копировать / изменить), ответы — markdown. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    showMenu: Boolean,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val bubble = when (msg.role) {
        ChatMessage.Role.USER -> scheme.accent.copy(alpha = 0.22f)
        ChatMessage.Role.ASSISTANT -> scheme.glass
        else -> Color.Transparent
    }
    val border = when (msg.role) {
        ChatMessage.Role.USER -> scheme.accent.copy(alpha = 0.45f)
        else -> scheme.stroke
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radius))
                .background(bubble)
                .border(1.dp, border, RoundedCornerShape(MetroDimens.radius))
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(10.dp),
        ) {
            if (msg.role == ChatMessage.Role.ASSISTANT) {
                MarkdownText(msg.text, scheme.text)
            } else {
                Text(
                    text = msg.text,
                    fontFamily = MetroFonts.text,
                    fontSize = 14.sp,
                    color = if (msg.role == ChatMessage.Role.SYSTEM) scheme.textDim else scheme.text,
                )
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onDismissMenu,
            containerColor = scheme.glassDeep,
        ) {
            DropdownMenuItem(
                text = { Text("Копировать", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text) },
                onClick = onCopy,
                colors = MenuDefaults.itemColors(textColor = scheme.text),
            )
            if (canEdit) {
                DropdownMenuItem(
                    text = { Text("Изменить и отправить заново", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text) },
                    onClick = onEdit,
                    colors = MenuDefaults.itemColors(textColor = scheme.text),
                )
            }
        }
    }
}

/** Диалог изменения своего сообщения (память откатывается, ход идёт по новой). */
@Composable
private fun EditMessageDialog(text: String, onChange: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(MetroDimens.panelRadius))
                .background(scheme.glassDeep)
                .border(1.dp, scheme.accent, RoundedCornerShape(MetroDimens.panelRadius))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ИЗМЕНИТЬ", fontFamily = MetroFonts.text, fontSize = 12.sp, letterSpacing = 1.5.sp, color = scheme.textDim)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.glassHover)
                    .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onChange,
                    textStyle = TextStyle(fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.text),
                    cursorBrush = SolidColor(scheme.accent),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(scheme.glassHover)
                        .metroClickable(targetScale = 0.97f, onClick = onDismiss)
                        .padding(vertical = 12.dp),
                ) {
                    Text("Отмена", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(scheme.accent.copy(alpha = 0.85f))
                        .metroClickable(targetScale = 0.97f, onClick = onSave)
                        .padding(vertical = 12.dp),
                ) {
                    Text("Отправить", fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                }
            }
        }
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
