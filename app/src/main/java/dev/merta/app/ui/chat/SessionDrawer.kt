package dev.merta.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.bridge.QuotaJson
import dev.merta.app.data.chat.SessionUsage
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable

/**
 * Правая шторка: информация о сессии (контекст, траты/лимиты),
 * раскрывающиеся выбор модели и effort, кнопка настроек.
 */
@Composable
fun SessionDrawer(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val ui by vm.state.collectAsState()
    val usage by vm.usageState.collectAsState()
    val quotaGroup = vm.quotaForCurrent()
    val quotaLoading by vm.quotaLoading.collectAsState()
    val quotaError by vm.quotaError.collectAsState()
    val hasQuotaData = vm.quotaState.collectAsState().value != null
    var modelsOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0B).copy(alpha = 0.62f))
            .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "СЕССИЯ",
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
        }

        if (vm.isAgyActive()) {
            QuotaCard(
                group = quotaGroup,
                hasData = hasQuotaData,
                loading = quotaLoading,
                error = quotaError,
                usage = usage,
                onRefresh = { vm.refreshQuota(force = true) },
            )
        } else {
            CostCard(
                providerName = ui.providerName,
                modelName = ui.modelDisplayName,
                usage = usage,
                pricing = vm.pricingForCurrent(),
            )
        }
        Spacer(Modifier.height(12.dp))

        ExpandHeader(
            title = "МОДЕЛЬ",
            current = ui.modelDisplayName.ifBlank { "не выбрана" },
            open = modelsOpen,
            onToggle = { modelsOpen = !modelsOpen },
        )
        AnimatedVisibility(
            visible = modelsOpen,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150)),
        ) {
            Column {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                for (g in ui.groups) {
                    item(key = "p-${g.provider.id}") {
                        Text(
                            text = g.provider.name.uppercase(),
                            fontFamily = MetroFonts.text,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp,
                            color = scheme.accent,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    items(g.models, key = { "m-${g.provider.id}-${it.id}" }) { m ->
                        val selected = g.provider.id == vm.activeProviderId() &&
                            m.id == vm.selectedModelName(g.provider.id)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(MetroDimens.radius))
                                .background(if (selected) scheme.accent.copy(alpha = 0.22f) else scheme.glass)
                                .border(
                                    1.dp,
                                    if (selected) scheme.accent.copy(alpha = 0.45f) else scheme.stroke,
                                    RoundedCornerShape(MetroDimens.radius),
                                )
                                .metroClickable(targetScale = 0.97f) {
                                    vm.selectModel(g.provider.id, m.id)
                                    modelsOpen = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(m.displayName, fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.text)
                            if (m.displayName != m.id) {
                                Text(
                                    m.id,
                                    fontFamily = MetroFonts.text,
                                    fontSize = 12.sp,
                                    color = scheme.textDim,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = "все модели →",
                fontFamily = MetroFonts.text,
                fontSize = 13.sp,
                color = scheme.accent,
                modifier = Modifier
                    .metroClickable(targetScale = 0.97f, onClick = onOpenModels)
                    .padding(vertical = 8.dp),
            )
            }
        }

        Spacer(Modifier.height(8.dp))
        ExpandHeader(
            title = "EFFORT",
            current = ui.effort ?: "выкл",
            open = effortOpen,
            onToggle = { effortOpen = !effortOpen },
        )
        if (!vm.modelSupportsEffort()) {
            Text(
                text = "модель без reasoning — effort не отправится",
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                color = scheme.textDim,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        AnimatedVisibility(
            visible = effortOpen,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EffortPickRow("Выкл", ui.effort == null) { vm.setEffort(null); effortOpen = false }
                for (e in MertaSettings.Efforts.ALL) {
                    EffortPickRow(e, ui.effort == e) { vm.setEffort(e); effortOpen = false }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.accent.copy(alpha = 0.85f))
                .metroClickable(targetScale = 0.97f, onClick = onOpenSettings)
                .padding(vertical = 14.dp),
        ) {
            Text(
                "НАСТРОЙКИ",
                fontFamily = MetroFonts.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                letterSpacing = 1.5.sp,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun ExpandHeader(title: String, current: String, open: Boolean, onToggle: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .metroClickable(targetScale = 0.98f, onClick = onToggle)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = title,
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            color = scheme.textDim,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = current,
            fontFamily = MetroFonts.text,
            fontSize = 13.sp,
            color = scheme.text,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (open) "▴" else "▾",
            fontFamily = MetroFonts.text,
            fontSize = 13.sp,
            color = scheme.textDim,
        )
    }
}

@Composable
private fun EffortPickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(if (selected) scheme.accent.copy(alpha = 0.22f) else scheme.glass)
            .border(
                1.dp,
                if (selected) scheme.accent.copy(alpha = 0.45f) else scheme.stroke,
                RoundedCornerShape(MetroDimens.radiusSmall),
            )
            .metroClickable(targetScale = 0.97f, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(label, fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
    }
}

/** Карточка трат OpenAI-ветки: контекст, вход/выход, доллары. */
@Composable
private fun CostCard(
    providerName: String,
    modelName: String,
    usage: SessionUsage,
    pricing: Pair<Double, Double>,
) {
    val scheme = LocalMetroScheme.current
    val cost = usage.cost(pricing.first, pricing.second)
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
            text = if (providerName.isBlank()) "провайдер не выбран" else "$providerName · $modelName",
            fontFamily = MetroFonts.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = scheme.text,
        )
        Text(
            text = "контекст: ${SessionUsage.formatTokens(usage.total())} ток. " +
                "(вх ${SessionUsage.formatTokens(usage.input)} / вых ${SessionUsage.formatTokens(usage.output)})",
            fontFamily = MetroFonts.text,
            fontSize = 13.sp,
            color = scheme.textDim,
        )
        Text(
            text = if (cost > 0) "потрачено: ${SessionUsage.formatCost(cost)}" else "тариф неизвестен — считаю только токены",
            fontFamily = MetroFonts.text,
            fontSize = 13.sp,
            color = if (cost > 0) scheme.text else scheme.textDim,
        )
    }
}

/** Карточка лимитов agy: группа текущей модели с прогресс-барами + токены сессии. */
@Composable
private fun QuotaCard(
    group: dev.merta.app.bridge.QuotaGroup?,
    hasData: Boolean,
    loading: Boolean,
    error: String?,
    usage: SessionUsage,
    onRefresh: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
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
                text = "ЛИМИТЫ",
                fontFamily = MetroFonts.text,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                color = scheme.textDim,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "⟳",
                fontFamily = MetroFonts.text,
                fontSize = 16.sp,
                color = scheme.textDim,
                modifier = Modifier.metroClickable(targetScale = 0.88f, onClick = onRefresh),
            )
        }
        if (group == null) {
            Text(
                text = when {
                    loading -> "тяну лимиты…"
                    error != null -> error
                    hasData -> "нет лимитов под эту модель"
                    else -> "открой шторку — подтяну лимиты…"
                },
                fontFamily = MetroFonts.text,
                fontSize = 13.sp,
                color = scheme.textDim,
            )
        } else {
            Text(
                text = group.name,
                fontFamily = MetroFonts.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = scheme.text,
            )
            for (b in group.buckets) {
                val pct = (b.remaining * 100).toInt()
                Text(
                    text = "${b.name}: $pct% · ${QuotaJson.resetIn(b.resetTime)}",
                    fontFamily = MetroFonts.text,
                    fontSize = 12.sp,
                    color = scheme.textDim,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(scheme.stroke),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(b.remaining.toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(if (pct < 20) scheme.red else scheme.accent),
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        Text(
            text = "сессия: ${SessionUsage.formatTokens(usage.total())} ток.",
            fontFamily = MetroFonts.text,
            fontSize = 13.sp,
            color = scheme.textDim,
        )
    }
}
