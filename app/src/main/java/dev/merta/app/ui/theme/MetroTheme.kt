package dev.merta.app.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Живой акцент из обоев — аналог metro-colors/matugen на десктопе.
 * На API < 27 WallpaperColors нет — остаемся на фолбэке.
 */
@Composable
fun rememberWallpaperAccent(): MutableState<Color> {
    val context = LocalContext.current
    val accent = remember { mutableStateOf(MetroDefaults.accentFallback) }

    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            onDispose { }
        } else {
            val wm = context.getSystemService(Context.WALLPAPER_SERVICE) as WallpaperManager
            fun pull() {
                val primary = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor
                if (primary != null) accent.value = Color(primary.toArgb())
            }
            pull()
            val listener = WallpaperManager.OnColorsChangedListener { _, _ -> pull() }
            wm.addOnColorsChangedListener(
                listener,
                Handler(Looper.getMainLooper()),
            )
            onDispose { wm.removeOnColorsChangedListener(listener) }
        }
    }
    return accent
}

@Composable
fun MetroTheme(
    /** Явный акцент (настройки). null = живой цвет обоев. Фаза 2: привязка к DataStore. */
    accentOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val wpAccent = rememberWallpaperAccent()
    val scheme = MetroScheme(accent = accentOverride ?: wpAccent.value)
    CompositionLocalProvider(LocalMetroScheme provides scheme) {
        // Segoe UI глобально: все M3-тексты (диалоги, поля, кнопки) — нашим шрифтом.
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = scheme.accent,
                onPrimary = scheme.text,
                secondary = scheme.accent,
                surface = Color(0xFF161616),
                onSurface = scheme.text,
                surfaceVariant = Color(0xFF1E1E1E),
                onSurfaceVariant = scheme.textDim,
                background = Color.Black,
                onBackground = scheme.text,
            ),
            typography = metroTypography(),
            content = content,
        )
    }
}

/** Вся M3-типографика на Segoe UI Variable (text — как fontFamily в шелле). */
@Composable
private fun metroTypography(): Typography {
    val f = MetroFonts.text
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = f),
        displayMedium = d.displayMedium.copy(fontFamily = f),
        displaySmall = d.displaySmall.copy(fontFamily = f),
        headlineLarge = d.headlineLarge.copy(fontFamily = f),
        headlineMedium = d.headlineMedium.copy(fontFamily = f),
        headlineSmall = d.headlineSmall.copy(fontFamily = f),
        titleLarge = d.titleLarge.copy(fontFamily = f),
        titleMedium = d.titleMedium.copy(fontFamily = f),
        titleSmall = d.titleSmall.copy(fontFamily = f),
        bodyLarge = d.bodyLarge.copy(fontFamily = f),
        bodyMedium = d.bodyMedium.copy(fontFamily = f),
        bodySmall = d.bodySmall.copy(fontFamily = f),
        labelLarge = d.labelLarge.copy(fontFamily = f),
        labelMedium = d.labelMedium.copy(fontFamily = f),
        labelSmall = d.labelSmall.copy(fontFamily = f),
    )
}
