package dev.slurpdrome.ui.components

import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.slurpdrome.ui.LocalContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val MiniPlayerHeight = 64.dp
private const val QUEUE_PULL_PX = 140f

/**
 * State of the pull-up player. [progress] is 0 (mini player) … 1 (full screen) and follows the
 * finger 1:1 while dragging; on release it settles with a spring, honouring fling velocity.
 */
@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    val progress = Animatable(0f)
    var tab by mutableIntStateOf(0)
    /** Pixels between the collapsed and expanded position — set by the host on layout. */
    internal var rangePx = 1f
    var reduceMotion = false

    val isExpanded get() = progress.targetValue > 0.5f

    fun expand(tab: Int? = null) {
        tab?.let { this.tab = it }
        scope.launch { animateTo(1f) }
    }

    fun collapse() { scope.launch { animateTo(0f) } }

    private suspend fun animateTo(target: Float, velocity: Float = 0f) {
        if (reduceMotion) progress.snapTo(target)
        else progress.animateTo(target, dev.slurpdrome.ui.theme.Motion.sheet(), velocity)
    }

    /** Upward drag past "fully open" on the player tab — becomes "open the queue". */
    private var overscroll = 0f

    /** Finger moved by [dy] px (positive = down). */
    fun dragBy(dy: Float) {
        if (progress.value >= 0.999f && dy < 0 && tab == 0) { overscroll += dy; return }
        scope.launch { progress.snapTo((progress.value - dy / rangePx).coerceIn(0f, 1f)) }
    }

    /** Release with vertical [velocityY] px/s (positive = down). */
    fun settle(velocityY: Float) {
        val pulledUp = overscroll < -QUEUE_PULL_PX || (progress.value >= 0.999f && tab == 0 && velocityY < -1200f)
        overscroll = 0f
        if (pulledUp) { tab = 1; return }   // swipe up on the open player → queue
        val target = when {
            velocityY > 900f -> 0f
            velocityY < -900f -> 1f
            else -> if (progress.value > 0.5f) 1f else 0f
        }
        scope.launch { animateTo(target, -velocityY / rangePx) }
    }

    /**
     * Lets scrollable content (queue, lyrics) hand over to the sheet: dragging down when the list
     * is already at its top collapses the player; dragging up while partly collapsed expands it.
     */
    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y < 0 && progress.value < 1f) {
                dragBy(available.y); return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0) {
                // In queue/lyrics, pulling down at the top first goes back to the player tab.
                if (tab != 0 && progress.value >= 0.999f) { overscroll += available.y; return Offset(0f, available.y) }
                dragBy(available.y); return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (tab != 0 && overscroll > QUEUE_PULL_PX) { overscroll = 0f; tab = 0 }
            overscroll = 0f
            return Velocity.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (progress.value in 0.001f..0.999f) { settle(available.y); return available }
            return Velocity.Zero
        }
    }
}

val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetState> { error("no sheet") }

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    return remember { PlayerSheetState(scope) }
}

/** Vertical drag that moves the sheet; attach to headers / artwork / non-scrolling areas. */
@Composable
fun Modifier.sheetDrag(sheet: PlayerSheetState): Modifier = draggable(
    state = rememberDraggableState { sheet.dragBy(it) },
    orientation = Orientation.Vertical,
    onDragStopped = { v -> sheet.settle(v) },
)

/**
 * Hosts the sheet over the screen. Collapsed it's a floating card above the bottom bar; pulled up
 * it morphs (margins → 0, corners → 0) into the full-screen player. Every per-frame value is read
 * in layout/draw phases only, so dragging never recomposes the player.
 */
@Composable
fun PlayerSheetHost(sheet: PlayerSheetState, bottomInset: Dp, haze: dev.chrisbanes.haze.HazeState, full: @Composable () -> Unit) {
    val c = LocalContainer.current
    val state by c.player.state.collectAsState()
    if (state.current == null) return
    BackHandler(enabled = sheet.isExpanded) { sheet.collapse() }
    val showFull by remember { androidx.compose.runtime.derivedStateOf { sheet.progress.value > 0.004f } }
    val showMini by remember { androidx.compose.runtime.derivedStateOf { sheet.progress.value < 0.45f } }
    val scrim = MaterialTheme.colorScheme.scrim
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullH = constraints.maxHeight
        val miniH = with(density) { MiniPlayerHeight.roundToPx() }
        val inset = with(density) { bottomInset.roundToPx() }
        val margin = with(density) { MiniCardMargin.roundToPx() }
        val radius = 0f   // full-width bars: square edges like the top bar
        val parallax = with(density) { 48.dp.toPx() }
        val elevation = with(density) { 8.dp.toPx() }
        sheet.rangePx = (fullH - miniH - inset).toFloat().coerceAtLeast(1f)

        // Scrim behind the rising player.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = sheet.progress.value.coerceIn(0f, 1f) * 0.6f }.drawBehind { drawRect(scrim) })

        Box(
            Modifier
                .layout { measurable, cons ->
                    // Collapsed: a full-width bar whose glass also covers the system nav area.
                    val p = sheet.progress.value.coerceIn(0f, 1f)   // springs may overshoot; shapes/sizes must not
                    val collapsed = miniH + inset
                    val h = (collapsed + (fullH - collapsed) * p).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(cons.maxWidth, h))
                    layout(cons.maxWidth, cons.maxHeight) { placeable.place(0, cons.maxHeight - h) }
                }
                // Opaque only as it opens: collapsed, the card is frosted glass like the top bar.
                .drawBehind { drawRect(surface, alpha = sheet.progress.value.coerceIn(0f, 1f)) }
                // The drag lives on the surface itself: it must survive the mini bar leaving
                // composition mid-gesture, or the release (settle) would never arrive.
                .sheetDrag(sheet)
        ) {
            if (showMini) Box(
                Modifier.matchParentSize()
                    .graphicsLayer { alpha = 1f - sheet.progress.value.coerceIn(0f, 1f) }
                    .then(with(dev.slurpdrome.ui.nav.GlassScope) { Modifier.glassBar(haze) })
            )
            if (showFull) Box(
                Modifier
                    .layout { measurable, cons ->
                        val placeable = measurable.measure(Constraints.fixed(cons.maxWidth, fullH))
                        layout(cons.maxWidth, cons.maxHeight) {
                            placeable.placeWithLayer(0, 0) {
                                val p = sheet.progress.value.coerceIn(0f, 1f)   // springs may overshoot; shapes/sizes must not
                                alpha = ((p - 0.1f) / 0.5f).coerceIn(0f, 1f)
                                translationY = parallax * (1 - p)    // content settles in slightly after the surface
                            }
                        }
                    }
            ) { full() }
            if (showMini) Box(
                Modifier.fillMaxWidth().height(MiniPlayerHeight)
                    .graphicsLayer { alpha = (1f - sheet.progress.value.coerceIn(0f, 1f) * 3f).coerceIn(0f, 1f) }
            ) { MiniPlayerBar { sheet.expand(0) } }
        }
    }
}

val MiniCardMargin = 8.dp

@Composable
private fun MiniPlayerBar(onOpen: () -> Unit) {
    val c = LocalContainer.current
    val state by c.player.state.collectAsState()
    val song = state.current ?: return
    val position by c.player.position.collectAsState()
    val target = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    // Interpolate between 250 ms position ticks so the bar glides instead of stepping.
    val progress by androidx.compose.animation.core.animateFloatAsState(target, tween(260, easing = androidx.compose.animation.core.LinearEasing), label = "miniProgress")
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val edge = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Column(Modifier.drawBehind {
        // Progress as a fill across the whole bar, with a crisp leading edge.
        val x = size.width * progress
        drawRect(fill, size = androidx.compose.ui.geometry.Size(x, size.height))
        drawRect(edge, topLeft = Offset(x - 1.5.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(1.5.dp.toPx(), size.height))
    }) {
        Row(
            Modifier.fillMaxWidth().dpadClickable(RoundedCornerShape(0.dp), onClick = onOpen).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = song,
                contentKey = { it.coverArt },
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "miniArt",
            ) { s -> Artwork(s.coverArt, Modifier.size(44.dp), size = 120, shape = RoundedCornerShape(8.dp)) }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = song,
                contentKey = { it.id },
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "miniTitle",
            ) { s ->
                Column {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedContent(
                state.isPlaying, label = "miniPlay",
                transitionSpec = { (fadeIn(tween(180)) + androidx.compose.animation.scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(120)) + androidx.compose.animation.scaleOut(targetScale = 0.6f)) },
            ) { playing ->
                FIconButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.common_pause else R.string.common_play)) { c.player.togglePlay() }
            }
            FIconButton(Icons.Default.SkipNext, stringResource(R.string.common_next)) { c.player.next() }
        }
    }
}

