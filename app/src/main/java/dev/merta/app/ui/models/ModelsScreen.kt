package dev.merta.app.ui.models

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.ui.chat.ChatViewModel
import dev.merta.app.ui.sessions.MetroSmallButton
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable

/**
 * Каталог моделей провайдера: обновление, поиск, выбор.
 * Выбор сразу сохраняется в конфиг (модель подхватывает чат).
 */
@Composable
fun ModelsScreen(
    vm: ChatViewModel,
    currentModel: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val ui by vm.state.collectAsState()
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (ui.models.isEmpty() && ui.modelsError == null) vm.refreshModels()
    }

    val shown = remember(ui.models, filter) {
        val f = filter.trim().lowercase()
        if (f.isEmpty()) ui.models
        else ui.models.filter { it.id.lowercase().contains(f) || it.displayName.lowercase().contains(f) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                text = "МОДЕЛИ",
                fontFamily = MetroFonts.headline,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                letterSpacing = 2.sp,
                color = scheme.text,
                modifier = Modifier.weight(1f),
            )
            MetroSmallButton("⟳") { vm.refreshModels() }
            Spacer(Modifier.width(8.dp))
            MetroSmallButton("×", onClick = onBack)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.glassHover)
                .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                textStyle = TextStyle(fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.text),
                cursorBrush = SolidColor(scheme.accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (filter.isEmpty()) Text("Поиск…", fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.textDim)
                    inner()
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        when {
            ui.modelsLoading -> Text("Загружаю каталог…", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.textDim)
            ui.modelsError != null -> Text(ui.modelsError!!, fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.textDim)
            shown.isEmpty() -> Text(
                if (ui.models.isEmpty()) "Каталог пуст — обнови кнопкой ⟳." else "Ничего не найдено.",
                fontFamily = MetroFonts.text,
                fontSize = 14.sp,
                color = scheme.textDim,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { m ->
                    val selected = m.id == currentModel
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
                            .metroClickable(targetScale = 0.97f) { onPick(m.id) }
                            .padding(12.dp),
                    ) {
                        Text(m.id, fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
                        if (m.name.isNotBlank() && m.name != m.id) {
                            Text(m.displayName, fontFamily = MetroFonts.text, fontSize = 12.sp, color = scheme.textDim)
                        }
                    }
                }
            }
        }
    }
}
