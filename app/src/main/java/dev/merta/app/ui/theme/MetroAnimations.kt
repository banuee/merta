package dev.merta.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Кривые портированы 1:1 из metro-launcher (hyprland.lua / Quickshell).
 * Скорости синхронизированы с hyprland speed 3.5 (~180-260мс).
 */
object MetroAnimations {
    /** Hyprland "Open": Snappy pop-in / window open curve {0, 0, 0.2, 1} */
    val OpenEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    /** Hyprland "Move": Snappy motion with slight organic overshoot {0.80, 0, 0.12, 1.4} */
    val MoveEasing = CubicBezierEasing(0.80f, 0.0f, 0.12f, 1.4f)

    /** Hyprland "Close": Smooth quick close {0.46, 1.0, 0.29, 0.99} */
    val CloseEasing = CubicBezierEasing(0.46f, 1.0f, 0.29f, 0.99f)

    /** Hyprland "Tag": Slide transition curve {0.4, 0, 0.2, 1} */
    val TagEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Hyprland "Bouncy": Bouncy spring-like bezier {0.34, 1.56, 0.64, 1.0} */
    val BouncyEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

    const val DURATION_FAST = 180
    const val DURATION_NORMAL = 240
    const val DURATION_SLOW = 280

    /** hyprland windowsIn: style = "popin 70%", bezier = "Open" */
    fun popIn(
        initialScale: Float = 0.70f,
        durationMillis: Int = DURATION_NORMAL,
    ) = scaleIn(
        initialScale = initialScale,
        animationSpec = tween(durationMillis, easing = OpenEasing),
    ) + fadeIn(
        animationSpec = tween(durationMillis - 40, easing = OpenEasing),
    )

    /** hyprland windowsOut: style = "popin 70%", bezier = "Close" */
    fun popOut(
        targetScale: Float = 0.70f,
        durationMillis: Int = DURATION_FAST,
    ) = scaleOut(
        targetScale = targetScale,
        animationSpec = tween(durationMillis, easing = CloseEasing),
    ) + fadeOut(
        animationSpec = tween(durationMillis - 40, easing = CloseEasing),
    )

    /** hyprland workspaces/layers: style = "slide", bezier = "Tag" */
    fun slideIn(
        durationMillis: Int = DURATION_SLOW,
    ) = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(durationMillis, easing = TagEasing),
    ) + fadeIn(
        animationSpec = tween(durationMillis - 60, easing = OpenEasing),
    )

    fun slideOut(
        durationMillis: Int = DURATION_NORMAL,
    ) = slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(durationMillis, easing = CloseEasing),
    ) + fadeOut(
        animationSpec = tween(durationMillis - 60, easing = CloseEasing),
    )

    /** Плавный физический spring без дребезга для синхронных перестановок и раскрытия */
    fun <T> smoothSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
