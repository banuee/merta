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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.data.chat.SessionStore
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Левая шторка: новый чат + история сессий (открыть, удалить). */
@Composable
fun HistoryDrawer(
    sessions: List<SessionStore.SessionMeta>,
    currentId: String,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val dateFmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.glassDeep)
            .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "ЧАТЫ",
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

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.accent.copy(alpha = 0.85f))
                .metroClickable(targetScale = 0.97f, onClick = onNew)
                .padding(vertical = 12.dp),
        ) {
            Text(
                "+ НОВЫЙ ЧАТ",
                fontFamily = MetroFonts.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                letterSpacing = 1.5.sp,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (sessions.isEmpty()) {
            Text(
                "Пока пусто.",
                fontFamily = MetroFonts.text,
                fontSize = 14.sp,
                color = scheme.textDim,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { s ->
                    val active = s.id == currentId
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
                            .metroClickable(targetScale = 0.97f) { onOpen(s.id) }
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
                        Text(
                            text = "×",
                            fontFamily = MetroFonts.text,
                            fontSize = 18.sp,
                            color = scheme.textDim,
                            modifier = Modifier.metroClickable(targetScale = 0.88f) { onDelete(s.id) },
                        )
                    }
                }
            }
        }
    }
}
