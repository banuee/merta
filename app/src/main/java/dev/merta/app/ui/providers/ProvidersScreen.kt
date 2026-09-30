package dev.merta.app.ui.providers

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.data.settings.MertaSettings.Presets
import dev.merta.app.data.settings.Provider
import dev.merta.app.ui.sessions.MetroSmallButton
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable
import java.util.UUID

/**
 * Управление провайдерами: выбор активного, редактирование параметров и ключей,
 * добавление своих OpenAI/Agy endpoint, удаление.
 * Ключи — только в шифрованном хранилище.
 */
@Composable
fun ProvidersScreen(
    providers: List<Provider>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: (Provider) -> Unit,
    onEdit: (Provider) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = LocalMetroScheme.current

    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editUrl by remember { mutableStateOf("") }
    var editKey by remember { mutableStateOf("") }

    var showAddForm by remember { mutableStateOf(false) }
    var addKind by remember { mutableStateOf(Provider.Kind.OPENAI) }
    var addName by remember { mutableStateOf("") }
    var addUrl by remember { mutableStateOf("") }
    var addKey by remember { mutableStateOf("") }

    BackHandler {
        when {
            editingId != null -> editingId = null
            showAddForm -> showAddForm = false
            else -> onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "ПРОВАЙДЕРЫ",
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

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(providers, key = { it.id }) { p ->
                val active = p.id == activeId
                val isEditing = editingId == p.id

                if (isEditing) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MetroDimens.radius))
                            .background(scheme.glass)
                            .border(1.dp, scheme.accent.copy(alpha = 0.6f), RoundedCornerShape(MetroDimens.radius))
                            .padding(12.dp)
                            .animateItem(),
                    ) {
                        Text(
                            text = "ИЗМЕНИТЬ: ${p.name.uppercase()}",
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            letterSpacing = 1.2.sp,
                            color = scheme.accent,
                        )
                        ProviderField(editName, { editName = it }, "Название")
                        ProviderField(
                            editUrl,
                            { editUrl = it },
                            if (p.isAgy) Presets.AGY_DAEMON else "https://…/v1",
                        )
                        ProviderField(
                            editKey,
                            { editKey = it },
                            if (p.isAgy) "Токен демона (если задан secret)" else "API-ключ",
                            secret = true,
                        )
                        if (p.isAgy) {
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
                                        if (editName.isNotBlank() && editUrl.isNotBlank()) {
                                            onEdit(
                                                p.copy(
                                                    name = editName.trim(),
                                                    baseUrl = editUrl.trim().trimEnd('/'),
                                                    apiKey = editKey.trim(),
                                                ),
                                            )
                                            editingId = null
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text("СОХРАНИТЬ", fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                            }
                            MetroSmallButton("×") { editingId = null }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MetroDimens.radius))
                            .background(if (active) scheme.accent.copy(alpha = 0.22f) else scheme.glass)
                            .border(
                                1.dp,
                                if (active) scheme.accent.copy(alpha = 0.45f) else scheme.stroke,
                                RoundedCornerShape(MetroDimens.radius),
                            )
                            .padding(12.dp)
                            .animateItem(),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .metroClickable(targetScale = 0.97f) { onSelect(p.id) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = scheme.text)
                                if (active) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("• активен", fontFamily = MetroFonts.text, fontSize = 11.sp, color = scheme.accent)
                                }
                            }
                            Text(p.baseUrl, fontFamily = MetroFonts.text, fontSize = 12.sp, color = scheme.textDim)
                            val keyLabel = when {
                                p.isAgy && p.apiKey.isNotBlank() -> "токен введён"
                                p.isAgy -> "без токена (открытый демон)"
                                p.apiKey.isNotBlank() -> "ключ введён"
                                else -> "без ключа"
                            }
                            val keyColor = when {
                                p.apiKey.isNotBlank() -> scheme.accent
                                p.isAgy -> scheme.textDim
                                else -> scheme.red
                            }
                            Text(
                                keyLabel,
                                fontFamily = MetroFonts.text,
                                fontSize = 12.sp,
                                color = keyColor,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MetroSmallButton("\uF044") {
                                editingId = p.id
                                editName = p.name
                                editUrl = p.baseUrl
                                editKey = p.apiKey
                                showAddForm = false
                            }
                            if (providers.size > 1) {
                                MetroSmallButton("×") { onDelete(p.id) }
                            }
                        }
                    }
                }
            }

            item(key = "add") {
                if (!showAddForm) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                            .background(scheme.glassHover)
                            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                            .metroClickable(targetScale = 0.97f) {
                                showAddForm = true
                                editingId = null
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        Text("+ ДОБАВИТЬ", fontFamily = MetroFonts.text, fontSize = 14.sp, letterSpacing = 1.5.sp, color = scheme.text)
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MetroDimens.radius))
                            .background(scheme.glass)
                            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
                            .padding(12.dp),
                    ) {
                        Text("ТИП ПРОВАЙДЕРА", fontFamily = MetroFonts.text, fontSize = 12.sp, color = scheme.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val isAgy = addKind == Provider.Kind.AGY
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                    .background(if (!isAgy) scheme.accent.copy(alpha = 0.35f) else scheme.glassHover)
                                    .border(1.dp, if (!isAgy) scheme.accent else scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                                    .metroClickable(targetScale = 0.97f) {
                                        addKind = Provider.Kind.OPENAI
                                        if (addName == "Agy") addName = ""
                                        if (addUrl == Presets.AGY_DAEMON) addUrl = ""
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text("OpenAI API", fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.text)
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                    .background(if (isAgy) scheme.accent.copy(alpha = 0.35f) else scheme.glassHover)
                                    .border(1.dp, if (isAgy) scheme.accent else scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                                    .metroClickable(targetScale = 0.97f) {
                                        addKind = Provider.Kind.AGY
                                        if (addName.isBlank()) addName = "Agy"
                                        if (addUrl.isBlank()) addUrl = Presets.AGY_DAEMON
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text("Agy Демон", fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.text)
                            }
                        }

                        ProviderField(addName, { addName = it }, if (addKind == Provider.Kind.AGY) "Agy" else "Название (например OpenRouter)")
                        ProviderField(addUrl, { addUrl = it }, if (addKind == Provider.Kind.AGY) Presets.AGY_DAEMON else "https://…/v1")
                        ProviderField(
                            addKey,
                            { addKey = it },
                            if (addKind == Provider.Kind.AGY) "Токен демона (если задан secret)" else "API-ключ",
                            secret = true,
                        )
                        if (addKind == Provider.Kind.AGY) {
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
                                        if (addName.isNotBlank() && addUrl.isNotBlank()) {
                                            val pid = if (addKind == Provider.Kind.AGY) {
                                                if (providers.none { it.id == "agy" }) "agy" else "agy-" + UUID.randomUUID().toString().take(4)
                                            } else {
                                                "p-" + UUID.randomUUID().toString().take(8)
                                            }
                                            onAdd(
                                                Provider(
                                                    id = pid,
                                                    name = addName.trim(),
                                                    baseUrl = addUrl.trim().trimEnd('/'),
                                                    apiKey = addKey.trim(),
                                                    kind = addKind,
                                                ),
                                            )
                                            addName = ""
                                            addUrl = ""
                                            addKey = ""
                                            showAddForm = false
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text("ДОБАВИТЬ", fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                            }
                            MetroSmallButton("×") { showAddForm = false }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ProviderField(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false) {
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
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, fontFamily = MetroFonts.text, fontSize = 15.sp, color = scheme.textDim)
                inner()
            },
        )
    }
}
