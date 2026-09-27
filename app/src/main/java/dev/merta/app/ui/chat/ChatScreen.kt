package dev.merta.app.ui.chat

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
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable

/**
 * Лента чата в стиле Metro. Состояние — в [ChatViewModel] (стрим Direct API).
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit,
    onNewChat: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val ui by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 12.dp),
    ) {
        // Верхняя акцентная полоска (Top accent glow).
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
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        ) {
            // Акцентный пилл-индикатор заголовка.
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(scheme.accent),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "MERTA",
                fontFamily = MetroFonts.headline,
                fontWeight = FontWeight.Light,
                fontSize = 24.sp,
                letterSpacing = 2.sp,
                color = scheme.text,
                modifier = Modifier.weight(1f),
            )
            // Новый чат и список чатов.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .metroClickable(targetScale = 0.88f, onClick = onNewChat)
                    .padding(12.dp),
            ) {
                Text(
                    text = "+",
                    fontFamily = MetroFonts.text,
                    fontSize = 22.sp,
                    color = scheme.textDim,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .metroClickable(targetScale = 0.88f, onClick = onOpenSessions)
                    .padding(12.dp),
            ) {
                Text(
                    text = "≣",
                    fontFamily = MetroFonts.text,
                    fontSize = 20.sp,
                    color = scheme.textDim,
                )
            }
            // Шестерёнка параметров: тач-таргет 44dp (см. AGENTS.md лаунчера).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .metroClickable(targetScale = 0.88f, onClick = onOpenSettings)
                    .padding(12.dp),
            ) {
                Text(
                    text = "\uF013",
                    fontFamily = MetroFonts.icon,
                    fontSize = 20.sp,
                    color = scheme.textDim,
                )
            }
        }
        Text(
            text = if (ui.configured) ui.modelLabel else "не настроено — открой параметры",
            fontFamily = MetroFonts.text,
            fontSize = 12.sp,
            color = scheme.textDim,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.messages, key = { it.id }) { msg ->
                MessageBubble(msg.role, msg.text)
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

@Composable
private fun MessageBubble(role: ChatMessage.Role, text: String) {
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
        modifier = Modifier
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
