package dev.merta.app.ui.theme

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.merta.app.data.wallpaper.WallpaperBitmaps

/**
 * Аппаратный блюр + vibrancy для оверлеев (порт из metro-anime).
 * На API < 31 возвращает модификатор без изменений.
 */
fun Modifier.metroBlurEffect(
    radiusPx: Float = 48f,
    saturationBoost: Float = 1.24f,
): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radiusPx <= 0f) {
        return this
    }
    return this.graphicsLayer {
        val blurEffect = android.graphics.RenderEffect.createBlurEffect(
            radiusPx,
            radiusPx,
            android.graphics.Shader.TileMode.CLAMP,
        )
        val cm = android.graphics.ColorMatrix().apply {
            setSaturation(saturationBoost)
        }
        val colorFilter = android.graphics.RenderEffect.createColorFilterEffect(
            android.graphics.ColorMatrixColorFilter(cm),
        )
        renderEffect = android.graphics.RenderEffect.createChainEffect(
            colorFilter,
            blurEffect,
        ).asComposeRenderEffect()
    }
}

/**
 * Слой обоев на весь экран (как в metro-anime): предблюренный битмап + димминг.
 * Без обоев — чистый чёрный фон.
 */
@Composable
fun WallpaperLayer(bitmaps: WallpaperBitmaps?, blurEnabled: Boolean, dimAlpha: Float) {
    if (bitmaps != null) {
        Image(
            bitmap = if (blurEnabled) bitmaps.blurred else bitmaps.sharp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}
