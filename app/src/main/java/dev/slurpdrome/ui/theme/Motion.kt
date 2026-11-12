package dev.slurpdrome.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One motion language for the whole app (Material 3 "emphasized" curves + critically damped
 * springs). Consistent timing is what makes an interface feel expensive rather than busy.
 */
object Motion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 450

    /** Big surfaces (player sheet): quick, no overshoot. */
    fun <T> sheet(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 520f)
    /** Small affordances (press, toggles): snappy with a hint of life. */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    /** Values that track something continuous (progress, colours). */
    fun <T> follow(ms: Int = MEDIUM): FiniteAnimationSpec<T> = tween(ms, easing = Standard)

    fun <T> enter(ms: Int = MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, EmphasizedDecelerate)
    fun <T> exit(ms: Int = SHORT): FiniteAnimationSpec<T> = tween(ms, easing = EmphasizedAccelerate)
    fun <T> none(): FiniteAnimationSpec<T> = snap()
}

/** True when the user asked for reduced motion (settings) — animations then snap. */
val LocalReduceMotion = staticCompositionLocalOf { false }
