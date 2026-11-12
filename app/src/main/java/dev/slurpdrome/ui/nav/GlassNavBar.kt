package dev.slurpdrome.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.theme.Motion

/** One destination in the glass bar. */
data class GlassTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Lets non-nav code apply the same glass (kept in one place so every surface matches). */
object GlassScope {
    @Composable fun Modifier.glassOf(haze: HazeState, shape: RoundedCornerShape): Modifier = glass(haze, shape)

    /** Full-width bottom bar: same glass as the top bar, hairline on top. */
    @Composable fun Modifier.glassBar(haze: HazeState): Modifier {
        val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        return glass(haze, androidx.compose.ui.graphics.RectangleShape, tint = 0.78f, outlined = false)
            .drawWithContent { drawContent(); drawRect(hairline, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
    }
}

/** Frosted surface: blurs what scrolls underneath, tinted enough that text always reads. */
@Composable
fun Modifier.glass(haze: HazeState, shape: androidx.compose.ui.graphics.Shape, tint: Float = 0.66f, outlined: Boolean = true): Modifier {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    return this
        .clip(shape)
        .hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = MaterialTheme.colorScheme.background,
                tint = HazeTint(surface.copy(alpha = tint)),
                blurRadius = 22.dp,
                noiseFactor = 0.04f,
                fallbackTint = HazeTint(surface.copy(alpha = 0.94f)),   // < Android 12: no blur, stay readable
            ),
        ) {
            // Blur a 1/3-scale copy: visually identical after blurring, ~9× fewer pixels per frame.
            inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(0.33f)
        }
        .then(if (outlined) Modifier.border(1.dp, edge, shape) else Modifier)
}

/**
 * Floating top navigation: a frosted pill with a spring-animated selection highlight that slides
 * between tabs. On narrow screens only the active tab shows its label.
 */
@Composable
fun GlassNavBar(
    haze: HazeState,
    tabs: List<GlassTab>,
    selected: Int,
    compact: Boolean,
    onSelect: (Int) -> Unit,
    trailing: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val positions = remember(tabs.size) { mutableStateListOf<Pair<Dp, Dp>>().apply { repeat(tabs.size) { add(0.dp to 0.dp) } } }
    val target = positions.getOrNull(selected) ?: (0.dp to 0.dp)
    val indX by animateDpAsState(target.first, Motion.sheet(), label = "navX")
    val indW by animateDpAsState(target.second, Motion.sheet(), label = "navW")
    val shape = RoundedCornerShape(28.dp)
    val accent = MaterialTheme.colorScheme.primaryContainer

    // Full-width frosted bar under the status bar; a hairline separates it from content.
    val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(
        Modifier.fillMaxWidth()
            .glass(haze, androidx.compose.ui.graphics.RectangleShape, tint = 0.78f, outlined = false)
            .drawBehindHairline(hairline)
            .statusBarsPadding()
    ) {
    Row(
        Modifier.align(Alignment.Center).height(60.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight()) {
            // Sliding selection highlight behind the tabs.
            if (indW > 0.dp) Box(
                Modifier.offset(x = indX).width(indW).fillMaxHeight()
                    .background(accent, RoundedCornerShape(24.dp))
            )
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, t ->
                    val on = i == selected
                    val fg by animateColorAsState(
                        if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        Motion.follow(), label = "navFg",
                    )
                    Row(
                        Modifier
                            .fillMaxHeight()
                            .onGloballyPositioned { c ->
                                with(density) { positions[i] = c.positionInParent().x.toDp() to c.size.width.toDp() }
                            }
                            .dpadClickable(RoundedCornerShape(24.dp)) { onSelect(i) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (on) t.selectedIcon else t.icon, t.label, Modifier.size(22.dp), tint = fg)
                        AnimatedVisibility(
                            visible = on || !compact,
                            enter = fadeIn(Motion.enter()) + expandHorizontally(Motion.enter()),
                            exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.exit(Motion.MEDIUM)),
                        ) {
                            Row {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    t.label, color = fg, maxLines = 1,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
        trailing()
    }
    }
}

private fun Modifier.drawBehindHairline(color: Color) = this.then(
    Modifier.drawWithContent {
        drawContent()
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
    }
)

/** Soft fade under the status bar so content scrolling up doesn't collide with the clock. */
@Composable
fun StatusBarScrim(height: Dp, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier.fillMaxWidth().height(height).background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg.copy(alpha = 0.9f), bg.copy(alpha = 0.55f), Color.Transparent))
        )
    )
}

