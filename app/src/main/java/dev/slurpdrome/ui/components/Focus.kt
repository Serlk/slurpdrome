package dev.slurpdrome.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.slurpdrome.ui.theme.LocalExtraColors

/**
 * Strong, always-visible focus indicator. Material's default focus state layer is nearly
 * invisible on small low-DPI screens of keypad phones, so we draw a 2dp ring + tint.
 */
fun Modifier.focusRing(shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), width: Dp = 3.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = LocalExtraColors.current.focusRing
    val halo = MaterialTheme.colorScheme.background
    this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (!focused) Modifier
            else Modifier
                .background(ring.copy(alpha = 0.12f), shape)
                // Two-tone ring: a background-coloured halo under the accent stroke keeps it
                // readable on any fill — including accent-coloured buttons.
                .drawWithContent {
                    drawContent()
                    val outline = shape.createOutline(size, layoutDirection, this)
                    drawOutline(outline, halo, style = Stroke(width.toPx() * 2.4f))
                    drawOutline(outline, ring, style = Stroke(width.toPx()))
                }
        )
}

/**
 * Clickable that is first-class on D-pad / keyboard:
 *  - visible focus ring,
 *  - Menu key or "M" opens the context action (same as long-press on touch).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.dpadClickable(
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    onLongClick: (() -> Unit)? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = this
    .focusRing(shape)
    .onPreviewKeyEvent { e ->
        when {
            onLongClick != null && (e.key == Key.Menu || e.key == Key.M) -> {
                if (e.type == KeyEventType.KeyUp) onLongClick()
                true
            }
            // Hardware-keyboard Enter (Titan, cyberdecks) must activate like D-pad centre.
            e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar -> {
                if (e.type == KeyEventType.KeyUp) onClick()
                true
            }
            else -> false
        }
    }
    .pressScale()
    .clip(shape)   // ripple follows the element's shape (the focus ring is drawn outside, above)
    .combinedClickable(role = role, onLongClick = onLongClick, onClick = onClick)

/** Subtle physical response: the element sinks slightly while pressed. */
fun Modifier.pressScale(pressed: Float = 0.97f): Modifier = composed {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val reduce = dev.slurpdrome.ui.theme.LocalReduceMotion.current
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (isPressed && !reduce) pressed else 1f, dev.slurpdrome.ui.theme.Motion.pop(), label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInputPress(source)
}

@Composable
fun FIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    filled: Boolean = false,
    buttonModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = if (filled) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.iconButtonColors(contentColor = tint)
    Box(modifier) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = colors,
            modifier = buttonModifier.size(size).focusRing(androidx.compose.foundation.shape.CircleShape),
        ) {
            Icon(icon, contentDescription, Modifier.size(iconSize), tint = if (filled) MaterialTheme.colorScheme.onPrimary else tint)
        }
    }
}

/**
 * Compose single-line text fields swallow D-pad Up/Down (cursor moves to start/end instead),
 * which traps focus on keypad devices. This lets Up/Down leave the field like any other control.
 */
/** Set while any text field has focus: Enter must stay "Enter" there (IME actions). */
object TextFocus { @Volatile var active = false }

fun Modifier.dpadEscape(): Modifier = composed {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    onFocusChanged { TextFocus.active = it.isFocused }.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionUp -> fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
            Key.DirectionDown -> fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            else -> false
        }
    }
}

/**
 * Focus [requester] on first composition — but only when the user is driving the UI with keys.
 * Keypad users land on the primary action instead of the nav rail; touch users see no ring.
 */
@Composable
fun AutoFocusForKeys(requester: androidx.compose.ui.focus.FocusRequester, key: Any? = Unit) {
    val mode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    androidx.compose.runtime.LaunchedEffect(key) {
        if (mode == androidx.compose.ui.input.InputMode.Keyboard) {
            kotlinx.coroutines.delay(150)   // let the screen lay out first
            runCatching { requester.requestFocus() }
        }
    }
}

/** Feeds press interactions into [source] without consuming the gesture. */
private fun Modifier.pointerInputPress(source: androidx.compose.foundation.interaction.MutableInteractionSource): Modifier =
    pointerInput(source) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = androidx.compose.foundation.interaction.PressInteraction.Press(down.position)
            source.tryEmit(press)
            val up = waitForUpOrCancellation()
            source.tryEmit(if (up != null) androidx.compose.foundation.interaction.PressInteraction.Release(press) else androidx.compose.foundation.interaction.PressInteraction.Cancel(press))
        }
    }
