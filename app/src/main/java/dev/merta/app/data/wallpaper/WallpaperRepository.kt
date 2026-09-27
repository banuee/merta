package dev.merta.app.data.wallpaper

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

private val Context.wallpaperDataStore by preferencesDataStore("wallpaper")

data class WallpaperSettings(
    val blurEnabled: Boolean = true,
    val blurRadius: Int = 14,
    val backgroundDim: Float = 0.55f,
    val hasCustomWallpaper: Boolean = false,
)

data class WallpaperBitmaps(val sharp: ImageBitmap, val blurred: ImageBitmap)

/**
 * Обои как в metro-anime: картинка из галереи → файл `merta_wallpaper.jpg`,
 * кроп под экран, программный stackBlur + hyprland-фильтр. Настройки в DataStore.
 */
class WallpaperRepository(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "MertaWallpaper"
        private val KEY_BLUR_ENABLED = booleanPreferencesKey("blur_enabled")
        private val KEY_BLUR_RADIUS = intPreferencesKey("blur_radius")
        private val KEY_BG_DIM = floatPreferencesKey("background_dim")
        private val KEY_HAS_WALLPAPER = booleanPreferencesKey("has_wallpaper")
    }

    private val _settings = MutableStateFlow(WallpaperSettings())
    val settings: StateFlow<WallpaperSettings> = _settings.asStateFlow()

    private val _wallpaper = MutableStateFlow<WallpaperBitmaps?>(null)
    val wallpaper: StateFlow<WallpaperBitmaps?> = _wallpaper.asStateFlow()

    init {
        scope.launch {
            app.wallpaperDataStore.data.map { prefs ->
                WallpaperSettings(
                    blurEnabled = prefs[KEY_BLUR_ENABLED] ?: true,
                    blurRadius = prefs[KEY_BLUR_RADIUS] ?: 14,
                    backgroundDim = prefs[KEY_BG_DIM] ?: 0.55f,
                    hasCustomWallpaper = prefs[KEY_HAS_WALLPAPER] ?: false,
                )
            }.collect {
                val prev = _settings.value
                _settings.value = it
                if (it.blurRadius != prev.blurRadius || it.blurEnabled != prev.blurEnabled) {
                    reloadWallpaper()
                }
            }
        }
        reloadWallpaper()
    }

    fun setBlurEnabled(enabled: Boolean) {
        scope.launch {
            app.wallpaperDataStore.edit { it[KEY_BLUR_ENABLED] = enabled }
        }
    }

    fun setBlurRadius(radius: Int) {
        scope.launch {
            app.wallpaperDataStore.edit { it[KEY_BLUR_RADIUS] = radius.coerceIn(2, 30) }
        }
    }

    fun setBackgroundDim(dim: Float) {
        scope.launch {
            app.wallpaperDataStore.edit { it[KEY_BG_DIM] = dim.coerceIn(0f, 0.9f) }
        }
    }

    private fun wallpaperFile(): File = File(app.filesDir, "merta_wallpaper.jpg")

    fun setCustomWallpaper(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    wallpaperFile().outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                app.wallpaperDataStore.edit { prefs ->
                    prefs[KEY_HAS_WALLPAPER] = true
                }
                reloadWallpaper()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save wallpaper: ${e.message}")
            }
        }
    }

    fun clearWallpaper() {
        scope.launch(Dispatchers.IO) {
            try {
                val file = wallpaperFile()
                if (file.exists()) file.delete()
            } catch (_: Exception) {
            }
            app.wallpaperDataStore.edit { prefs ->
                prefs[KEY_HAS_WALLPAPER] = false
            }
            _wallpaper.value = null
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = app.getSystemService(android.view.WindowManager::class.java)
                    .currentWindowMetrics.bounds
                metrics.width() to metrics.height()
            } catch (_: Exception) {
                fallbackSize()
            }
        } else {
            fallbackSize()
        }
    }

    private fun fallbackSize(): Pair<Int, Int> {
        val dm = app.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun reloadWallpaper() {
        scope.launch(Dispatchers.IO) {
            val file = wallpaperFile()
            if (!file.exists()) {
                _wallpaper.value = null
                return@launch
            }
            try {
                val (sw, sh) = screenSize()
                val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@launch
                val scaledSharp = WallpaperFx.centerCrop(rawBitmap, sw, sh) ?: return@launch

                val radius = _settings.value.blurRadius
                val blurred: Bitmap = if (_settings.value.blurEnabled) {
                    val scaleFactor = 2
                    val w = (sw / scaleFactor).coerceAtLeast(1)
                    val h = (sh / scaleFactor).coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(scaledSharp, w, h, true)
                    val blurredSmall = WallpaperFx.stackBlur(small, radius.coerceIn(2, 30))
                    val enhanced = WallpaperFx.applyHyprlandColorFilter(blurredSmall)
                    if (blurredSmall !== small) blurredSmall.recycle()
                    small.recycle()
                    Bitmap.createScaledBitmap(enhanced, sw, sh, true)
                } else {
                    scaledSharp.copy(Bitmap.Config.ARGB_8888, true)
                }

                _wallpaper.value = WallpaperBitmaps(
                    sharp = scaledSharp.asImageBitmap(),
                    blurred = blurred.asImageBitmap(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wallpaper: ${e.message}")
                _wallpaper.value = null
            }
        }
    }
}
