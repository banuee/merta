package dev.merta.app.ui.sessions

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.ui.chat.ChatViewModel
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Список чатов: открыть, новый, удалить. */
@Composable
fun SessionsScreen(vm: ChatViewModel, onOpenChat: () -> Unit, onNewChat: () -> Unit) {
    val scheme = LocalMetroScheme.current
    val ui by vm.state.collectAsState()
    val dateFmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

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
                text = "ЧАТЫ",
                fontFamily = MetroFonts.headline,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                letterSpacing = 2.sp,
                color = scheme.text,
                modifier = Modifier.weight(1f),
            )
            MetroSmallButton("+", onClick = onNewChat)
            Spacer(Modifier.width(8.dp))
            MetroSmallButton("×", onClick = onOpenChat)
        }

        if (ui.sessions.isEmpty()) {
            Text(
                "Пока пусто — начни новый чат кнопкой +.",
                fontFamily = MetroFonts.text,
                fontSize = 14.sp,
                color = scheme.textDim,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.sessions, key = { it.id }) { s ->
                    val active = s.id == ui.sessionId
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
                            .metroClickable(targetScale = 0.97f) {
                                vm.openSession(s.id)
                                onOpenChat()
                            }
                            .padding(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title,
                                fontFamily = MetroFonts.text,
                                fontSize = 15.sp,
                                color = scheme.text,
                                maxLines = 1,
                            )
                            Text(
                                "${dateFmt.format(Date(s.updatedAt))} · ${s.messageCount} сообщ.",
                                fontFamily = MetroFonts.text,
                                fontSize = 12.sp,
                                color = scheme.textDim,
                            )
                        }
                        MetroSmallButton("×") { vm.deleteSession(s.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun MetroSmallButton(label: String, onClick: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(scheme.glassHover)
            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
            .metroClickable(targetScale = 0.88f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 18.sp, color = scheme.text)
    }
}
