package dev.merta.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dev.merta.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA-обновления через GitHub Releases (та же система, что в metro-launcher).
 * Проверка latest-релиза, сравнение версий, скачивание APK с прогрессом,
 * установка через FileProvider. Репозиторий: banuee/merta.
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkSize: Long,
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val info: ReleaseInfo) : UpdateState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateRepository(private val context: Context) {
    private val app = context.applicationContext

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    init {
        cleanOldApks(BuildConfig.VERSION_NAME)
    }

    fun cleanOldApks(activeVersion: String) {
        try {
            val updatesDir = File(app.cacheDir, "updates")
            if (updatesDir.exists()) {
                val apks = updatesDir.listFiles { _, name -> name.endsWith(".apk", ignoreCase = true) }
                apks?.forEach { apk ->
                    val apkVer = apk.name.removePrefix("merta-").removeSuffix(".apk")
                    if (!isNewerVersion(apkVer, activeVersion) || apkVer == activeVersion) {
                        apk.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean old update APKs: ${e.message}")
        }
    }

    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/banuee/merta/releases/latest"
        private const val TAG = "MertaUpdate"

        /** Чистое сравнение версий — покрыто JVM-тестами. */
        fun isNewerVersion(remote: String, current: String): Boolean {
            val rClean = remote.removePrefix("v").trim().substringBefore('-').substringBefore('+')
            val cClean = current.removePrefix("v").trim().substringBefore('-').substringBefore('+')
            val rParts = rClean.split(".").mapNotNull { it.toIntOrNull() }
            val cParts = cClean.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "Merta-App/${BuildConfig.VERSION_NAME}")
            }

            if (conn.responseCode != 200) return@withContext null

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)

            val tagName = root.optString("tag_name", "")
            val body = root.optString("body", "Нет описания изменений.")
            val rawVersion = tagName.removePrefix("v").trim()

            // Поиск APK в assets
            val assets = root.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isBlank()) return@withContext null

            val currentVersion = BuildConfig.VERSION_NAME
            if (isNewerVersion(rawVersion, currentVersion)) {
                ReleaseInfo(
                    tagName = tagName,
                    versionName = rawVersion,
                    changelog = body,
                    apkDownloadUrl = apkUrl,
                    apkSize = apkSize,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка проверки обновлений: ${e.message}")
            null
        }
    }

    suspend fun checkForUpdates() {
        cleanOldApks(BuildConfig.VERSION_NAME)
        _updateState.value = UpdateState.Checking
        try {
            val release = fetchLatestRelease()
            if (release != null) {
                _updateState.value = UpdateState.Available(release)
            } else {
                _updateState.value = UpdateState.UpToDate
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка проверки обновлений: ${e.message}")
            _updateState.value = UpdateState.Error(e.localizedMessage ?: "Сетевая ошибка")
        }
    }

    suspend fun downloadUpdate(info: ReleaseInfo) {
        _updateState.value = UpdateState.Downloading(0f, 0L, info.apkSize)
        withContext(Dispatchers.IO) {
            try {
                val updatesDir = File(app.cacheDir, "updates").apply { mkdirs() }
                val targetFile = File(updatesDir, "merta-${info.versionName}.apk")
                if (targetFile.exists()) targetFile.delete()

                var currentUrl = info.apkDownloadUrl
                var conn: HttpURLConnection
                var redirects = 0

                while (true) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 20000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Merta-App/${BuildConfig.VERSION_NAME}")
                    }
                    val code = conn.responseCode
                    if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308) && redirects < 5) {
                        val location = conn.getHeaderField("Location")
                        if (!location.isNullOrBlank()) {
                            currentUrl = location
                            redirects++
                            conn.disconnect()
                            continue
                        }
                    }
                    break
                }

                val totalLength = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.apkSize
                var downloaded = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(16384)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val progress = if (totalLength > 0) downloaded.toFloat() / totalLength else 0f
                            _updateState.value = UpdateState.Downloading(progress, downloaded, totalLength)
                        }
                    }
                }

                _updateState.value = UpdateState.ReadyToInstall(targetFile)
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка скачивания APK: ${e.message}")
                _updateState.value = UpdateState.Error("Ошибка скачивания: ${e.localizedMessage}")
            }
        }
    }

    fun installApk(file: File) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!app.packageManager.canRequestPackageInstalls()) {
                    val permIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${app.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(permIntent)
                    return
                }
            }

            val contentUri: Uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                file,
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска установщика: ${e.message}")
            _updateState.value = UpdateState.Error("Не удалось запустить установщик: ${e.message}")
        }
    }
}
