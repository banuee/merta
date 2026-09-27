package dev.merta.app.ui.providers

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
import dev.merta.app.data.settings.Provider
import dev.merta.app.ui.sessions.MetroSmallButton
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable
import java.util.UUID

/**
 * Управление провайдерами: выбор активного, добавление своих
 * OpenAI-совместимых endpoint, удаление. Ключи — только в шифрованном хранилище.
 */
@Composable
fun ProvidersScreen(
    providers: List<Provider>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: (Provider) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    var showForm by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

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
                        .metroClickable(targetScale = 0.97f) { onSelect(p.id) }
                        .padding(12.dp)
                        .animateItem(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = scheme.text)
                        Text(p.baseUrl, fontFamily = MetroFonts.text, fontSize = 12.sp, color = scheme.textDim)
                        Text(
                            if (p.hasKey) "ключ введён" else "без ключа",
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = if (p.hasKey) scheme.accent else scheme.red,
                        )
                    }
                    if (providers.size > 1) {
                        MetroSmallButton("×") { onDelete(p.id) }
                    }
                }
            }

            item(key = "add") {
                if (!showForm) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                            .background(scheme.glassHover)
                            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                            .metroClickable(targetScale = 0.97f) { showForm = true }
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
                        ProviderField(name, { name = it }, "Название (например My LLM)")
                        ProviderField(url, { url = it }, "https://…/v1")
                        ProviderField(key, { key = it }, "API-ключ", secret = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                    .background(scheme.accent.copy(alpha = 0.85f))
                                    .metroClickable(targetScale = 0.97f) {
                                        if (name.isNotBlank() && url.isNotBlank()) {
                                            onAdd(
                                                Provider(
                                                    id = "p-" + UUID.randomUUID().toString().take(8),
                                                    name = name.trim(),
                                                    baseUrl = url.trim().trimEnd('/'),
                                                    apiKey = key.trim(),
                                                ),
                                            )
                                            name = ""
                                            url = ""
                                            key = ""
                                            showForm = false
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text("ДОБАВИТЬ", fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                            }
                            MetroSmallButton("×") { showForm = false }
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
