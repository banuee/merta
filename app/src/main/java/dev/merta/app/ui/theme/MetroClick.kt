package dev.merta.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

/**
 * Тактильный клик-модификатор для элементов списков и кнопок.
 * Портирован 1:1 из metro-launcher: упругий отскок при тапе,
 * плавное сжатие при зажатии. Никаких M3-ripples.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.metroClickable(
    enabled: Boolean = true,
    targetScale: Float = 0.95f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    val pulse = remember { Animatable(1f) }
    val heldScale by animateFloatAsState(
        targetValue = if (pressed) targetScale else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "metro-click-held",
    )
    val scale = if (pressed) heldScale else pulse.value

    return this
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .then(
            if (onLongClick != null) {
                Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    onClick = {
                        scope.launch {
                            pulse.animateTo(targetScale, tween(60, easing = FastOutSlowInEasing))
                            pulse.animateTo(
                                1f,
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        }
                        onClick()
                    },
                )
            } else {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                ) {
                    scope.launch {
                        pulse.animateTo(targetScale, tween(60, easing = FastOutSlowInEasing))
                        pulse.animateTo(
                            1f,
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                    onClick()
                }
            }
        )
}
