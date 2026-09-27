package dev.merta.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.BuildConfig
import dev.merta.app.data.settings.MertaSettings
import dev.merta.app.data.update.AutoUpdateManager
import dev.merta.app.data.update.AutoUpdateNotificationHelper
import dev.merta.app.data.update.UpdateRepository
import dev.merta.app.data.update.UpdateState
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable
import kotlinx.coroutines.launch

private val INTERVALS = listOf(0, 10, 30, 60, 180, 360, 720, 1440)
private val INTERVAL_LABELS = listOf("Выкл", "10м", "30м", "1ч", "3ч", "6ч", "12ч", "24ч")

/**
 * OTA через GitHub Releases: версия + проверка, автосканирование с переключалкой
 * уведомлений, скачивание с прогрессом и установка. Та же система, что в лаунчере.
 */
@Composable
fun UpdatesSection(settings: MertaSettings) {
    val scheme = LocalMetroScheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateRepo = remember { UpdateRepository(context) }
    val state by updateRepo.updateState.collectAsState()

    var interval by remember { mutableStateOf(settings.loadUpdate().intervalMinutes) }
    var hasNotifPerm by remember { mutableStateOf(AutoUpdateNotificationHelper.canPostNotifications(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotifPerm = granted || AutoUpdateNotificationHelper.canPostNotifications(context)
    }

    fun setInterval(minutes: Int) {
        interval = minutes
        val cur = settings.loadUpdate()
        settings.saveUpdate(cur.copy(intervalMinutes = minutes))
        AutoUpdateManager.schedule(context, minutes)
        if (minutes > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Карточка текущей версии.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radius))
            .background(scheme.glass)
            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
            .padding(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("УСТАНОВЛЕННАЯ ВЕРСИЯ", fontFamily = MetroFonts.text, fontSize = 11.sp, letterSpacing = 1.sp, color = scheme.textDim)
            Text(
                "v${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})",
                fontFamily = MetroFonts.headline,
                fontWeight = FontWeight.Light,
                fontSize = 17.sp,
                color = scheme.text,
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.glassHover)
                .metroClickable(targetScale = 0.94f) { scope.launch { updateRepo.checkForUpdates() } }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("Проверить", color = scheme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = MetroFonts.text)
        }
    }
    Spacer(Modifier.height(8.dp))

    // Автосканирование.
    Text("АВТОПРОВЕРКА", fontFamily = MetroFonts.text, fontSize = 11.sp, letterSpacing = 1.sp, color = scheme.textDim)
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            INTERVALS.take(4).forEachIndexed { i, mins ->
                IntervalChip(INTERVAL_LABELS[i], interval == mins) { setInterval(mins) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            INTERVALS.drop(4).forEachIndexed { i, mins ->
                IntervalChip(INTERVAL_LABELS[i + 4], interval == mins) { setInterval(mins) }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    if (interval > 0) {
        if (!hasNotifPerm) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.red.copy(alpha = 0.12f))
                    .border(1.dp, scheme.red.copy(alpha = 0.35f), RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Уведомления отключены", color = scheme.red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = MetroFonts.text)
                    Text("Без них о новых релизах не узнаешь", color = scheme.textDim, fontSize = 11.sp, fontFamily = MetroFonts.text)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(scheme.red)
                        .metroClickable(targetScale = 0.94f) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Включить", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = MetroFonts.text)
                }
            }
        } else {
            Text("Проверка каждые ${INTERVAL_LABELS[INTERVALS.indexOf(interval)]} · уведомления включены", fontFamily = MetroFonts.text, fontSize = 12.sp, color = scheme.textDim)
        }
    } else {
        Text("Автопроверка выключена — только вручную.", fontFamily = MetroFonts.text, fontSize = 12.sp, color = scheme.textDim)
    }
    Spacer(Modifier.height(8.dp))

    // Состояние проверки/загрузки.
    when (val s = state) {
        is UpdateState.Idle -> Text("Нажми «Проверить», чтобы узнать о релизах на GitHub.", fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.textDim)
        is UpdateState.Checking -> Text("Проверка обновлений на GitHub…", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
        is UpdateState.UpToDate -> UpdateCard("У тебя последняя версия!", border = scheme.accent.copy(alpha = 0.4f))
        is UpdateState.Available -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radius))
                .background(scheme.glass)
                .border(1.dp, scheme.accent, RoundedCornerShape(MetroDimens.radius))
                .padding(12.dp),
        ) {
            Text("Доступно обновление: v${s.info.versionName}", color = scheme.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = MetroFonts.text)
            Spacer(Modifier.height(6.dp))
            Text("Что нового:", color = scheme.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = MetroFonts.text)
            Text(s.info.changelog.take(600), color = scheme.text, fontSize = 13.sp, fontFamily = MetroFonts.text)
            Spacer(Modifier.height(10.dp))
            val sizeMb = "%.1f".format(s.info.apkSize / (1024f * 1024f))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.accent)
                    .metroClickable(targetScale = 0.96f) { scope.launch { updateRepo.downloadUpdate(s.info) } }
                    .padding(vertical = 12.dp),
            ) {
                Text("Скачать и обновить ($sizeMb МБ)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = MetroFonts.text)
            }
        }
        is UpdateState.Downloading -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radius))
                .background(scheme.glass)
                .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
                .padding(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Загрузка обновления…", color = scheme.text, fontSize = 14.sp, fontFamily = MetroFonts.text)
                Text("${(s.progress * 100).toInt()}%", color = scheme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = MetroFonts.text)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(scheme.glassHover),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(s.progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(scheme.accent),
                )
            }
        }
        is UpdateState.ReadyToInstall -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.accent)
                .metroClickable(targetScale = 0.96f) { updateRepo.installApk(s.apkFile) }
                .padding(vertical = 14.dp),
        ) {
            Text("Установить обновление сейчас", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = MetroFonts.text)
        }
        is UpdateState.Error -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MetroDimens.radius))
                .background(scheme.glass)
                .border(1.dp, scheme.red.copy(alpha = 0.5f), RoundedCornerShape(MetroDimens.radius))
                .padding(12.dp),
        ) {
            Text("Ошибка обновления: ${s.message}", color = scheme.red, fontSize = 13.sp, fontFamily = MetroFonts.text)
            Spacer(Modifier.height(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.glassHover)
                    .metroClickable(targetScale = 0.94f) { scope.launch { updateRepo.checkForUpdates() } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Повторить", color = scheme.text, fontSize = 12.sp, fontFamily = MetroFonts.text)
            }
        }
    }
}

@Composable
private fun IntervalChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(if (selected) scheme.accent.copy(alpha = 0.22f) else scheme.glass)
            .border(1.dp, if (selected) scheme.accent else scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
            .metroClickable(targetScale = 0.93f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.text)
    }
}

@Composable
private fun UpdateCard(text: String, border: Color) {
    val scheme = LocalMetroScheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radius))
            .background(scheme.glass)
            .border(1.dp, border, RoundedCornerShape(MetroDimens.radius))
            .padding(12.dp),
    ) {
        Text(text, color = scheme.text, fontSize = 14.sp, fontFamily = MetroFonts.text)
    }
}
