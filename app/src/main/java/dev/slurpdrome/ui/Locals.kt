package dev.slurpdrome.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import dev.slurpdrome.AppContainer

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("no nav") }

/** Height covered by the floating top bar (status bar + glass nav); screens pad their content by it. */
val LocalTopInset = androidx.compose.runtime.compositionLocalOf { androidx.compose.ui.unit.Dp(0f) }
/** Shared blur source for every glass surface (top bar, mini player). */
val LocalHaze = staticCompositionLocalOf { dev.chrisbanes.haze.HazeState() }
/** Height covered at the bottom by the floating mini player + system bar; lists pad by it. */
val LocalBottomInset = androidx.compose.runtime.compositionLocalOf { androidx.compose.ui.unit.Dp(0f) }
