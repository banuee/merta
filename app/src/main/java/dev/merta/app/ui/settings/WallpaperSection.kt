package dev.merta.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.data.wallpaper.WallpaperRepository
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable
import kotlin.math.roundToInt

/**
 * Обои как в metro-anime: превью, выбор из галереи, сброс,
 * переключалка блюра, слайдеры радиуса и затемнения.
 */
@Composable
fun WallpaperSection(repo: WallpaperRepository) {
    val scheme = LocalMetroScheme.current
    val wp by repo.wallpaper.collectAsState()
    val settings by repo.settings.collectAsState()

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) repo.setCustomWallpaper(uri)
    }

    // Превью.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(MetroDimens.radius))
            .background(scheme.glass)
            .border(1.dp, scheme.strokeStrong, RoundedCornerShape(MetroDimens.radius)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmaps = wp
        if (bitmaps != null) {
            Image(
                bitmap = if (settings.blurEnabled) bitmaps.blurred else bitmaps.sharp,
                contentDescription = "Текущие обои",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = settings.backgroundDim)),
            )
        } else {
            Text(
                "Стандартный тёмный фон Metro",
                fontFamily = MetroFonts.text,
                fontSize = 13.sp,
                color = scheme.textDim,
            )
        }
    }
    Spacer(Modifier.height(10.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                .background(scheme.accent.copy(alpha = 0.85f))
                .metroClickable(targetScale = 0.97f) {
                    pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                .padding(vertical = 12.dp),
        ) {
            Text("ВЫБРАТЬ ОБОИ", fontFamily = MetroFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp, color = Color.White)
        }
        if (settings.hasCustomWallpaper) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.glassHover)
                    .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                    .metroClickable(targetScale = 0.97f, onClick = { repo.clearWallpaper() })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("Сбросить", fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.text)
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    // Блюр вкл/выкл.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BlurChip("Блюр вкл", settings.blurEnabled) { repo.setBlurEnabled(true) }
        BlurChip("Блюр выкл", !settings.blurEnabled) { repo.setBlurEnabled(false) }
    }
    Spacer(Modifier.height(6.dp))

    // Радиус блюра.
    Text("Радиус: ${settings.blurRadius}", fontFamily = MetroFonts.text, fontSize = 13.sp, color = scheme.textDim)
    Slider(
        value = settings.blurRadius.toFloat(),
        onValueChange = { repo.setBlurRadius(it.roundToInt()) },
        valueRange = 2f..30f,
        steps = 27,
        colors = SliderDefaults.colors(
            thumbColor = scheme.accent,
            activeTrackColor = scheme.accent,
            inactiveTrackColor = scheme.glassHover,
        ),
    )

    // Затемнение.
    Text(
        "Затемнение: ${(settings.backgroundDim * 100).roundToInt()}%",
        fontFamily = MetroFonts.text,
        fontSize = 13.sp,
        color = scheme.textDim,
    )
    Slider(
        value = settings.backgroundDim,
        onValueChange = { repo.setBackgroundDim(it) },
        valueRange = 0f..0.9f,
        colors = SliderDefaults.colors(
            thumbColor = scheme.accent,
            activeTrackColor = scheme.accent,
            inactiveTrackColor = scheme.glassHover,
        ),
    )
}

@Composable
private fun BlurChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = LocalMetroScheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(if (selected) scheme.accent.copy(alpha = 0.22f) else scheme.glass)
            .border(1.dp, if (selected) scheme.accent else scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
            .metroClickable(targetScale = 0.93f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.text)
    }
}
