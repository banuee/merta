package dev.merta.app.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.merta.app.MertaActivity

/** Уведомления о релизах (та же система, что в metro-launcher). */
object AutoUpdateNotificationHelper {
    const val CHANNEL_ID = "merta_updates"
    const val NOTIFICATION_ID = 2001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновления",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Уведомления о доступных версиях Merta"
                enableLights(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showUpdateNotification(context: Context, info: ReleaseInfo) {
        createNotificationChannel(context)

        if (!canPostNotifications(context)) {
            return
        }

        val intent = Intent(context, MertaActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_updates", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val cleanChangelog = if (info.changelog.isNotBlank() && info.changelog != "Нет описания изменений.") {
            info.changelog.take(300)
        } else {
            "Нажмите, чтобы загрузить и установить обновление."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Доступно обновление Merta")
            .setContentText("Новая версия v${info.versionName} доступна для установки")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Версия v${info.versionName}:\n$cleanChangelog")
            )
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.stat_sys_download,
                "Обновить",
                pendingIntent,
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Разрешение отозвано в процессе
        }
    }
}
