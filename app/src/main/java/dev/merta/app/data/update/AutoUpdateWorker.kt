package dev.merta.app.data.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.merta.app.data.settings.MertaSettings
import java.util.concurrent.TimeUnit

/**
 * Фоновая проверка релизов на GitHub (та же система, что в metro-launcher).
 * Уведомляет один раз на версию, затем перепланируется через [AutoUpdateManager].
 */
class AutoUpdateWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "merta_auto_update_worker"
        private const val TAG = "AutoUpdateWorker"
    }

    override suspend fun doWork(): Result {
        val settings = MertaSettings(context)
        val interval = settings.loadUpdate().intervalMinutes

        if (interval <= 0) {
            Log.d(TAG, "Автопроверка отключена (interval=0)")
            return Result.success()
        }

        try {
            val updateRepo = UpdateRepository(context)
            val release = updateRepo.fetchLatestRelease()
            if (release != null) {
                if (release.versionName != settings.loadUpdate().lastNotifiedVersion) {
                    Log.i(TAG, "Обнаружена новая версия: ${release.versionName}, отправляем уведомление")
                    AutoUpdateNotificationHelper.showUpdateNotification(context, release)
                    settings.saveUpdate(settings.loadUpdate().copy(lastNotifiedVersion = release.versionName))
                } else {
                    Log.d(TAG, "Уведомление для версии ${release.versionName} уже показывалось ранее")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка автосканирования: ${e.message}")
        } finally {
            if (interval > 0) {
                AutoUpdateManager.scheduleNext(context, interval)
            }
        }

        return Result.success()
    }
}

object AutoUpdateManager {
    const val WORK_NAME = AutoUpdateWorker.WORK_NAME

    fun schedule(context: Context, intervalMinutes: Int) {
        val wm = WorkManager.getInstance(context)
        if (intervalMinutes <= 0) {
            wm.cancelUniqueWork(WORK_NAME)
        } else {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setInitialDelay(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            wm.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    fun scheduleNext(context: Context, intervalMinutes: Int) {
        val wm = WorkManager.getInstance(context)
        if (intervalMinutes <= 0) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
            .setInitialDelay(intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        wm.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
