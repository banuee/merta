package dev.merta.app.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Токены портированы 1:1 из metro-launcher (Quickshell Metro Style Guide).
 * Сетка чата на тех же юнитах: unit 84dp, gap 8dp.
 */
object MetroDimens {
    val unit: Dp = 84.dp
    val gap: Dp = 8.dp
    val radius: Dp = 10.dp
    val radiusSmall: Dp = 8.dp
    val radiusLarge: Dp = 14.dp
    val panelRadius: Dp = 16.dp
}

/** Монохромное стекло + акцент (НЕ радуга, НЕ Material-пилюли). */
data class MetroScheme(
    val accent: Color,
    val glass: Color = Color.White.copy(alpha = 0.07f),
    val glassHover: Color = Color.White.copy(alpha = 0.12f),
    val glassDeep: Color = Color(0xFF101010).copy(alpha = 0.90f),
    val stroke: Color = Color.White.copy(alpha = 0.08f),
    val strokeStrong: Color = Color.White.copy(alpha = 0.15f),
    val text: Color = Color(0xFFF7F7F7),
    val textDim: Color = Color.White.copy(alpha = 0.60f),
    val red: Color = Color(0xFFE51400),
)

val LocalMetroScheme: ProvidableCompositionLocal<MetroScheme> =
    compositionLocalOf { MetroScheme(accent = MetroDefaults.accentFallback) }

object MetroDefaults {
    /** Дефолт как в quickshell: teal #00aba9, пока не пришел цвет обоев. */
    val accentFallback = Color(0xFF00ABA9)
}
