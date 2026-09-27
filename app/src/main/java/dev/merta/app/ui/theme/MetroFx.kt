package dev.merta.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/**
 * Аппаратный блюр + vibrancy для фоновых слоёв (порт из metro-anime).
 * На API < 31 возвращает модификатор без изменений (там спасает димминг).
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
 * Фон из системных обоев (как в metro-anime): кроп на весь экран + блюр + димминг.
 * null (нет доступа к обоям) = чистый чёрный фон.
 */
@Composable
fun WallpaperBackground(dimAlpha: Float = 0.55f) {
    val context = LocalContext.current
    val bmp = remember {
        try {
            val wm = context.getSystemService(android.content.Context.WALLPAPER_SERVICE) as android.app.WallpaperManager
            val drawable = wm.drawable ?: return@remember null
            val intrinsic = (drawable as? BitmapDrawable)?.bitmap?.let { src ->
                val scale = (512f / src.width).coerceAtMost(1f)
                if (scale >= 1f) src
                else Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
            } ?: run {
                val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
                val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(Canvas(it))
                }
            }
            intrinsic
        } catch (_: Exception) {
            null
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().metroBlurEffect(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}
