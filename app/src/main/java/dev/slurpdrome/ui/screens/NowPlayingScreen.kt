package dev.slurpdrome.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.zIndex
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.togetherWith
import dev.slurpdrome.ui.components.sheetDrag
import dev.slurpdrome.ui.components.pressScale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.repo.Lyrics
import dev.slurpdrome.player.PlayerUiState
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.components.Artwork
import dev.slurpdrome.ui.components.EmptyState
import dev.slurpdrome.ui.components.ExtraAction
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalDownloads
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.Pill
import dev.slurpdrome.ui.components.RatingBar
import dev.slurpdrome.ui.components.SongRow
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.focusRing
import dev.slurpdrome.ui.components.formatMs
import dev.slurpdrome.ui.nav.Route
import dev.slurpdrome.ui.LocalNav
import dev.slurpdrome.ui.components.LocalPlayerSheet
import dev.slurpdrome.ui.theme.LocalExtraColors
import dev.slurpdrome.ui.theme.MonoStyle
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingContent(sheet: dev.slurpdrome.ui.components.PlayerSheetState) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val state by c.player.state.collectAsState()
    val song = state.current
    val accent = rememberArtworkColor(song?.coverArt)
    val bgTop by animateColorAsState(accent ?: MaterialTheme.colorScheme.surfaceContainerHighest, tween(600), label = "bg")
    val drag = Modifier.sheetDrag(sheet)
    val lyrics = sheet.tab == 2

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(Brush.verticalGradient(listOf(bgTop.copy(alpha = 0.55f), MaterialTheme.colorScheme.background)))
            .safeDrawingPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // Grab handle + header: dragging here moves the sheet.
            Column(drag) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(50)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FIconButton(Icons.Default.KeyboardArrowDown, stringResource(R.string.np_collapse)) { sheet.collapse() }
                    Spacer(Modifier.weight(1f))
                    if (song != null) PlayerActions(song)
                    // Lyrics replace the artwork in place (no tabs): one toggle. Icon-only on narrow screens.
                    if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 480) androidx.compose.material3.FilterChip(
                        selected = lyrics, onClick = { sheet.tab = if (lyrics) 0 else 2 },
                        label = { Text(stringResource(R.string.np_show_lyrics)) },
                        leadingIcon = { Icon(Icons.Default.Lyrics, null, Modifier.size(18.dp)) },
                        modifier = Modifier.padding(start = 4.dp).focusRing(RoundedCornerShape(8.dp)),
                    ) else FIconButton(
                        Icons.Default.Lyrics, stringResource(R.string.np_show_lyrics),
                        tint = if (lyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { sheet.tab = if (lyrics) 0 else 2 }
                    dev.slurpdrome.ui.components.CastButton()
                    if (song != null) FIconButton(Icons.Default.MoreVert, stringResource(R.string.common_actions)) { ui.songMenu(song) }
                }
            }
            if (song == null) {
                EmptyState(stringResource(R.string.np_nothing_playing))
                return@Column
            }
            Box(Modifier.weight(1f).padding(bottom = QueuePeek)) {
                androidx.compose.animation.Crossfade(lyrics, animationSpec = tween(280), label = "npLyrics") { showLyrics ->
                    if (showLyrics) Box(Modifier.nestedScroll(sheet.nestedScroll)) { LyricsTab(song) }
                    else Box(drag) { PlayerTab(state, song) }
                }
            }
        }
        if (song != null) QueuePanel(sheet, state)
    }
}

private val QueuePeek = 60.dp

/**
 * The queue lives in a panel that slides up from the bottom of the player: a peek bar shows the
 * next track; drag/tap (or swipe up on the player) opens it, drag down or Back closes it.
 */
@Composable
private fun QueuePanel(sheet: dev.slurpdrome.ui.components.PlayerSheetState, state: PlayerUiState) {
    val c = LocalContainer.current
    val open = sheet.tab == 1
    val p by androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f, dev.slurpdrome.ui.theme.Motion.sheet(), label = "queuePanel")
    val queue by c.player.queue.collectAsState()
    val next = queue.getOrNull(state.index + 1)
    var dragged by remember { mutableFloatStateOf(0f) }
    androidx.activity.compose.BackHandler(enabled = open) { sheet.tab = 0 }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = QueuePeek + (maxHeight * 0.86f - QueuePeek) * p
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Row(
                Modifier.fillMaxWidth().height(QueuePeek)
                    .draggable(
                        rememberDraggableState { dragged += it },
                        androidx.compose.foundation.gestures.Orientation.Vertical,
                        onDragStopped = { v ->
                            if (dragged < -40f || v < -600f) sheet.tab = 1 else if (dragged > 40f || v > 600f) sheet.tab = 0
                            dragged = 0f
                        },
                    )
                    .dpadClickable(RoundedCornerShape(24.dp)) { sheet.tab = if (open) 0 else 1 }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (open) stringResource(R.string.np_tab_queue)
                        else next?.let { stringResource(R.string.np_up_next_title, it.title) } ?: stringResource(R.string.np_queue_end),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (!open && next != null) Text(next.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(
                    Icons.Default.KeyboardArrowUp, null,
                    Modifier.graphicsLayer { rotationZ = 180f * p }, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (p > 0.02f) Box(Modifier.weight(1f).graphicsLayer { alpha = p }.nestedScroll(sheet.nestedScroll)) { QueueTab(state) }
        }
    }
}

/**
 * Artwork you can play with: drag sideways to skip (follows the finger, commits past a threshold
 * or on a fling), double-tap to favourite with a heart burst.
 */
@Composable
private fun GestureArtwork(song: SongEntity, state: PlayerUiState, offset: androidx.compose.animation.core.Animatable<Float, *>, modifier: Modifier) {
    val c = LocalContainer.current
    var burst by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val burstAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (state.isPlaying) 1f else 0.88f, androidx.compose.animation.core.spring(dampingRatio = 0.6f), label = "artScale")
    LaunchedEffect(burst) {
        if (burst == 0) return@LaunchedEffect
        burstAnim.snapTo(0f)
        burstAnim.animateTo(1f, tween(650, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }
    Box(
        modifier
            .pointerInput(song.id, song.starred) {
                detectTapGestures(onDoubleTap = {
                    if (!song.starred) c.library.toggleSongStar(song)
                    burst++
                })
            }
            .graphicsLayer {
                val w = size.width.coerceAtLeast(1f)
                translationX = offset.value
                rotationZ = offset.value / w * 5f
                alpha = 1f - (kotlin.math.abs(offset.value) / w * 0.7f).coerceIn(0f, 0.7f)
                scaleX = scale; scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(
            song.coverArt,
            transitionSpec = { androidx.compose.animation.fadeIn(tween(350)) togetherWith androidx.compose.animation.fadeOut(tween(350)) },
            label = "art",
        ) { art -> Artwork(art, Modifier.fillMaxSize(), size = 800, shape = RoundedCornerShape(18.dp)) }
        if (burstAnim.value in 0.001f..0.999f) {
            val t = burstAnim.value
            Icon(
                Icons.Default.Favorite, null,
                Modifier.size(120.dp).graphicsLayer {
                    val s = if (t < 0.3f) t / 0.3f * 1.15f else 1.15f - (t - 0.3f) * 0.2f
                    scaleX = s; scaleY = s; alpha = if (t < 0.6f) 1f else 1f - (t - 0.6f) / 0.4f
                },
                tint = Color.White,
            )
        }
    }
}

/**
 * Horizontal swipe anywhere on the player switches tracks: the artwork follows the finger
 * ([offset]), commits past a quarter of the width or on a fling. Children that drag sideways
 * themselves (the seek bar) consume first, so they keep working.
 */
@Composable
private fun Modifier.trackSwipe(offset: androidx.compose.animation.core.Animatable<Float, *>): Modifier {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onDragEnd = {
                val v = tracker.calculateVelocity().x
                val w = size.width.toFloat()
                scope.launch {
                    val o = offset.value
                    when {
                        o < -w * 0.25f || v < -1500f -> { offset.animateTo(-w, tween(160)); c.player.next(); offset.snapTo(w * 0.5f); offset.animateTo(0f, dev.slurpdrome.ui.theme.Motion.sheet()) }
                        o > w * 0.25f || v > 1500f -> { offset.animateTo(w, tween(160)); c.player.previousTrack(); offset.snapTo(-w * 0.5f); offset.animateTo(0f, dev.slurpdrome.ui.theme.Motion.sheet()) }
                        else -> offset.animateTo(0f, dev.slurpdrome.ui.theme.Motion.pop())
                    }
                }
            },
            onDragCancel = { scope.launch { offset.animateTo(0f) } },
        ) { change, dx ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            scope.launch { offset.snapTo(offset.value + dx) }
        }
    }
}

/** Dominant colour of the cover for the backdrop gradient (Palette on a small bitmap). */
@Composable
private fun rememberArtworkColor(coverArt: String?): Color? {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val color by produceState<Color?>(null, coverArt) {
        val url = c.sources.artUrl(coverArt, 120) ?: run { value = null; return@produceState }
        val result = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).size(120).build())
        val bmp = (result as? SuccessResult)?.drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
        value = bmp?.let { b ->
            val p = Palette.from(b).generate()
            (p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb?.let { Color(it) }
        }
    }
    return color
}

@Composable
private fun PlayerTab(state: PlayerUiState, song: SongEntity) {
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().trackSwipe(offset)) {
        val h = maxHeight
        val w = maxWidth
        when {
            // Real landscape: art left, capped so the controls keep a usable width.
            w > h * 1.3f -> Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val art = minOf(h - 32.dp, w * 0.45f)
                GestureArtwork(song, state, offset, Modifier.size(art))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) { ControlsBlock(state, song, compact = h < 360.dp) }
            }
            // Square / small screens (Titan, cyberdecks): art moves into the title row.
            h < 560.dp -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                ControlsBlock(state, song, compact = true, inlineArt = true)
            }
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    GestureArtwork(song, state, offset, Modifier.sizeIn(maxWidth = 520.dp).fillMaxWidth().aspectRatio(1f, matchHeightConstraintsFirst = true))
                }
                ControlsBlock(state, song, compact = false)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ControlsBlock(state: PlayerUiState, song: SongEntity, compact: Boolean, inlineArt: Boolean = false) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val sheet = LocalPlayerSheet.current
    val radio by c.radioEnabled.collectAsState()
    val sleep by c.sleepTimer.state.collectAsState()
    val settings by c.settingsState.collectAsState()
    val download = LocalDownloads.current[song.id]
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (inlineArt) {
                Artwork(song.coverArt, Modifier.size(96.dp), size = 300, shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                var lastIndex by remember { androidx.compose.runtime.mutableIntStateOf(state.index) }
                val forward = state.index >= lastIndex
                LaunchedEffect(state.index) { lastIndex = state.index }
                androidx.compose.animation.AnimatedContent(
                    song.title,
                    transitionSpec = {
                        val d = if (forward) 1 else -1
                        (androidx.compose.animation.slideInVertically(dev.slurpdrome.ui.theme.Motion.enter()) { it * d / 2 } + androidx.compose.animation.fadeIn(dev.slurpdrome.ui.theme.Motion.enter(220))) togetherWith
                            (androidx.compose.animation.slideOutVertically(dev.slurpdrome.ui.theme.Motion.exit(200)) { -it * d / 2 } + androidx.compose.animation.fadeOut(dev.slurpdrome.ui.theme.Motion.exit(120)))
                    },
                    label = "title",
                ) { t ->
                    Text(t, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.dpadClickable { song.artistId?.let { sheet.collapse(); nav.navigate(Route.Artist(it)) } }.padding(vertical = 2.dp),
                )
                Text(
                    song.album, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.dpadClickable { song.albumId?.let { sheet.collapse(); nav.navigate(Route.Album(it)) } }.padding(vertical = 2.dp),
                )
            }
            val heart by androidx.compose.animation.core.animateFloatAsState(
                if (song.starred) 1.15f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 600f), label = "heart",
            )
            FIconButton(
                if (song.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                stringResource(if (song.starred) R.string.act_remove_from_favorites else R.string.act_add_to_favorites),
                modifier = Modifier.graphicsLayer { scaleX = heart; scaleY = heart },
                tint = if (song.starred) LocalExtraColors.current.favorite else MaterialTheme.colorScheme.onSurfaceVariant,
            ) { c.library.toggleSongStar(song) }
        }
        if (!compact) RatingBar(song.rating, size = 32.dp) { c.library.setRating(song.id, "song", it) }

        SeekBar(state)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(listOfNotNull(song.suffix?.uppercase(), song.bitRate.takeIf { it > 0 }?.let { "${it}k" }).joinToString(" "))
            if (download?.state == 2) Pill(stringResource(R.string.np_pill_offline))
            if (radio) Pill(stringResource(R.string.np_pill_radio))
            val casting by c.castActive.collectAsState()
            if (casting) Pill("CHROMECAST")
            if (settings.playbackSpeed != 1f) Pill("×${settings.playbackSpeed}")
            if (sleep.active) Pill(stringResource(if (sleep.endOfTrack) R.string.np_pill_sleep_end_of_track else R.string.np_pill_sleep))
        }

        Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            FIconButton(Icons.Default.Shuffle, stringResource(R.string.np_shuffle), tint = if (state.shuffled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) { c.player.toggleShuffle() }
            FIconButton(Icons.Default.SkipPrevious, stringResource(R.string.np_previous), size = 56.dp, iconSize = 34.dp) { c.player.previous() }
            Box(contentAlignment = Alignment.Center) {
                val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                dev.slurpdrome.ui.components.AutoFocusForKeys(playFocus)
                // Rounded square while playing, circle when paused — a small morph that reads as "state".
                val corner by androidx.compose.animation.core.animateIntAsState(if (state.isPlaying) 30 else 50, dev.slurpdrome.ui.theme.Motion.pop(), label = "playShape")
                androidx.compose.material3.FilledIconButton(
                    onClick = { c.player.togglePlay() },
                    shape = RoundedCornerShape(corner),
                    modifier = Modifier.focusRequester(playFocus).size(if (compact) 60.dp else 76.dp)
                        .focusRing(RoundedCornerShape(corner)).pressScale(0.92f),
                ) {
                    androidx.compose.animation.AnimatedContent(
                        state.isPlaying,
                        transitionSpec = {
                            (androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.scaleIn(dev.slurpdrome.ui.theme.Motion.pop(), initialScale = 0.5f)) togetherWith
                                (androidx.compose.animation.fadeOut(tween(100)) + androidx.compose.animation.scaleOut(targetScale = 0.5f))
                        },
                        label = "playIcon",
                    ) { playing ->
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.common_pause else R.string.common_play), Modifier.size(if (compact) 34.dp else 42.dp))
                    }
                }
                if (state.isBuffering) CircularProgressIndicator(Modifier.size(if (compact) 64.dp else 80.dp), strokeWidth = 2.dp)
            }
            FIconButton(Icons.Default.SkipNext, stringResource(R.string.common_next), size = 56.dp, iconSize = 34.dp) { c.player.next() }
            FIconButton(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                stringResource(R.string.np_repeat),
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) { c.player.cycleRepeat() }
        }
    }
}

/** Secondary actions (sleep, speed, radio, playlist/bookmarks) — live in the player header. */
@Composable
private fun PlayerActions(song: SongEntity) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val radio by c.radioEnabled.collectAsState()
    val sleep by c.sleepTimer.state.collectAsState()
    val settings by c.settingsState.collectAsState()
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
            FIconButton(Icons.Default.Bedtime, stringResource(R.string.np_sleep_timer), tint = if (sleep.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) { showSleep = true }
            FIconButton(Icons.Default.Speed, stringResource(R.string.np_speed), tint = if (settings.playbackSpeed != 1f || settings.skipSilence) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) { showSpeed = true }
            FIconButton(Icons.Default.Radio, stringResource(R.string.act_endless_radio), tint = if (radio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
                c.radioEnabled.value = !radio
                ui.toast(ui.str(if (!radio) R.string.np_toast_radio_on else R.string.np_toast_radio_off))
            }
            if (c.audiobooks.isAudiobook(song)) {
                FIconButton(Icons.Default.Bookmarks, stringResource(R.string.np_bookmarks), tint = MaterialTheme.colorScheme.onSurfaceVariant) { showBookmarks = true }
            } else {
                FIconButton(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.np_to_playlist), tint = MaterialTheme.colorScheme.onSurfaceVariant) { ui.playlistPicker = listOf(song.id) }
            }
    }
    if (showSleep) SleepDialog { showSleep = false }
    if (showSpeed) SpeedDialog(audiobook = c.audiobooks.isAudiobook(song)) { showSpeed = false }
    if (showBookmarks) BookmarksDialog(song) { showBookmarks = false }
}

/** Seek bar: drag on touch; Left/Right on D-pad seek by the configured step. */
@Composable
private fun SeekBar(state: PlayerUiState) {
    val c = LocalContainer.current
    val position by c.player.position.collectAsState()
    val step = c.settingsState.collectAsState().value.seekStepSec * 1000L
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val raw = (position.toFloat() / duration).coerceIn(0f, 1f)
    // Glide between 250 ms ticks; jumps (seeks, track change) snap instead of sweeping.
    val anim = remember { androidx.compose.animation.core.Animatable(raw) }
    LaunchedEffect(raw) {
        if (kotlin.math.abs(raw - anim.value) * duration > 1500) anim.snapTo(raw)
        else anim.animateTo(raw, tween(260, easing = androidx.compose.animation.core.LinearEasing))
    }
    val shown = if (dragging) dragValue else anim.value
    val file by c.trackFile.collectAsState()
    val mine = file.takeIf { it.mediaId == state.currentId }
    val wave = mine?.waveform
    val loaded by androidx.compose.animation.core.animateFloatAsState(mine?.progress ?: 0f, tween(300), label = "loaded")
    // The waveform "grows" out of the plain bar once the whole file is local.
    val grow by androidx.compose.animation.core.animateFloatAsState(
        if (wave != null) 1f else 0f, tween(700, easing = dev.slurpdrome.ui.theme.Motion.EmphasizedDecelerate), label = "wave",
    )
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val buffered = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Column {
        Box(
            Modifier.fillMaxWidth().height(56.dp)
                .focusRing(RoundedCornerShape(12.dp))
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { c.player.seekBy(-step); true }
                        Key.DirectionRight -> { c.player.seekBy(step); true }
                        else -> false
                    }
                }
                .focusable()
                .pointerInput(duration) {
                    detectTapGestures { o -> c.player.seekTo(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragValue = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { c.player.seekTo((dragValue * duration).toLong()); dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> change.consume(); dragValue = (change.position.x / size.width).coerceIn(0f, 1f) }
                }
                .drawBehind {
                    val w = size.width; val h = size.height; val mid = h / 2
                    val px = w * shown
                    // Plain bar: track, how much of the file is local, how much is played.
                    if (grow < 1f) {
                        val a = 1f - grow
                        val bh = 5.dp.toPx(); val r = androidx.compose.ui.geometry.CornerRadius(bh / 2)
                        drawRoundRect(track.copy(alpha = track.alpha * a), androidx.compose.ui.geometry.Offset(0f, mid - bh / 2), androidx.compose.ui.geometry.Size(w, bh), r)
                        drawRoundRect(buffered.copy(alpha = buffered.alpha * a), androidx.compose.ui.geometry.Offset(0f, mid - bh / 2), androidx.compose.ui.geometry.Size(w * loaded, bh), r)
                        drawRoundRect(primary.copy(alpha = a), androidx.compose.ui.geometry.Offset(0f, mid - bh / 2), androidx.compose.ui.geometry.Size(px, bh), r)
                        drawCircle(primary.copy(alpha = a), radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(px, mid))
                    }
                    // Waveform: one rounded bar per bin, played part in the accent colour.
                    if (wave != null && grow > 0f) {
                        val n = wave.size
                        val slot = w / n
                        val bw = (slot * 0.62f).coerceAtLeast(1.5f)
                        val minH = 3.dp.toPx()
                        for (i in 0 until n) {
                            val bh = (minH + (h - minH) * wave[i] * 0.92f) * grow
                            val x = i * slot + (slot - bw) / 2
                            val color = if (x + bw / 2 <= px) primary else rest
                            drawRoundRect(color.copy(alpha = color.alpha * grow), androidx.compose.ui.geometry.Offset(x, mid - bh / 2), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bw / 2))
                        }
                        drawRect(primary.copy(alpha = grow), androidx.compose.ui.geometry.Offset(px - 1.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(2.dp.toPx(), h))
                    }
                }
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(if (dragging) (dragValue * duration).toLong() else position), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("-" + formatMs((duration - position).coerceAtLeast(0)), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueueTab(state: PlayerUiState) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val queue by c.player.queue.collectAsState()
    val radio by c.radioEnabled.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (state.index > 0) listState.scrollToItem((state.index - 1).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val remaining = (queue.size - state.index - 1).coerceAtLeast(0)
            Text(stringResource(R.string.np_up_next, remaining), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            FilterChip(radio, { c.radioEnabled.value = !radio }, label = { Text(stringResource(R.string.np_radio)) }, leadingIcon = { Icon(Icons.Default.Radio, null, Modifier.size(16.dp)) }, modifier = Modifier.focusRing())
            FIconButton(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.np_save_queue_as_playlist)) {
                val ids = queue.map { it.id }
                c.scope.launch {
                    val name = ui.str(R.string.np_queue_playlist_name, java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
                    c.localPlaylists.create(name, ids)
                    ui.toast(ui.str(R.string.np_toast_saved, name))
                }
            }
            FIconButton(Icons.Default.ClearAll, stringResource(R.string.np_clear_upcoming)) { c.player.clearUpcoming() }
        }
        val nowPlayingPrefix = stringResource(R.string.np_now_playing_prefix)
        val lblRemove = stringResource(R.string.np_remove_from_queue)
        val lblUp = stringResource(R.string.np_move_up)
        val lblDown = stringResource(R.string.np_move_down)
        // Local copy while dragging; stable keys (song id + occurrence) so rows keep identity when moved.
        var order by remember(queue) { mutableStateOf(queue.withIndex().toList()) }
        val keys = remember(queue) {
            val seen = HashMap<String, Int>()
            queue.map { s -> val n = seen.merge(s.id, 1, Int::plus)!!; "${s.id}#$n" }
        }
        var draggingKey by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

        fun dropDrag() {
            val k = draggingKey ?: return
            val from = keys.indexOf(k)
            val to = order.indexOfFirst { keys[it.index] == k }
            draggingKey = null; dragOffset = 0f
            if (from >= 0 && to >= 0 && from != to) c.player.moveQueueItem(from, to)
        }
        /** Swap with the neighbour the dragged row's centre has crossed. */
        fun checkSwap() {
            val k = draggingKey ?: return
            val info = listState.layoutInfo.visibleItemsInfo
            val me = info.firstOrNull { it.key == k } ?: return
            val centre = me.offset + dragOffset + me.size / 2
            val target = info.firstOrNull { it.key != k && centre.toInt() in it.offset..(it.offset + it.size) } ?: return
            val fromIdx = order.indexOfFirst { keys[it.index] == k }
            val toIdx = order.indexOfFirst { keys[it.index] == target.key }
            if (fromIdx < 0 || toIdx < 0) return
            order = order.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            dragOffset += me.offset - target.offset
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
        // Auto-scroll while a row is held near the top/bottom edge.
        LaunchedEffect(draggingKey) {
            while (draggingKey != null) {
                val info = listState.layoutInfo
                val me = info.visibleItemsInfo.firstOrNull { it.key == draggingKey }
                if (me != null) {
                    val top = me.offset + dragOffset
                    val bottom = top + me.size
                    val edge = 120f
                    val dy = when {
                        top < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - top) / 6f).coerceAtMost(30f)
                        bottom > info.viewportEndOffset - edge -> ((bottom - info.viewportEndOffset + edge) / 6f).coerceAtMost(30f)
                        else -> 0f
                    }
                    if (dy != 0f) { val used = listState.scrollBy(dy); dragOffset += used; checkSwap() }
                }
                kotlinx.coroutines.delay(16)
            }
        }

        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(order, key = { _, e -> keys[e.index] }) { i, entry ->
                val s = entry.value
                val key = keys[entry.index]
                val dragging = key == draggingKey
                val past = i < state.index
                val lift by androidx.compose.animation.core.animateFloatAsState(if (dragging) 1f else 0f, label = "lift")
                Row(
                    (if (dragging) Modifier else Modifier.animateItem())
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            scaleX = 1f + 0.02f * lift; scaleY = scaleX
                            shadowElevation = 12.dp.toPx() * lift
                            shape = RoundedCornerShape(12.dp); clip = lift > 0f
                        }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = lift))
                        // Keyboard: Shift+Up/Down moves the focused row.
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || !e.isShiftPressed) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionUp -> { if (entry.index > 0) c.player.moveQueueItem(entry.index, entry.index - 1); true }
                                Key.DirectionDown -> { if (entry.index < queue.lastIndex) c.player.moveQueueItem(entry.index, entry.index + 1); true }
                                else -> false
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.DragHandle, stringResource(R.string.np_drag_to_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (past) 0.3f else 0.7f),
                        modifier = Modifier.padding(start = 6.dp).size(36.dp).padding(6.dp)
                            .pointerInput(key) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingKey = key; dragOffset = 0f
                                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = { dropDrag() },
                                    onDragCancel = { dropDrag() },
                                ) { change, amount -> change.consume(); dragOffset += amount.y; checkSwap() }
                            },
                    )
                    SongRow(
                        s,
                        modifier = Modifier.weight(1f).then(if (past) Modifier.alpha(0.5f) else Modifier),
                        subtitle = if (entry.index == state.index) "$nowPlayingPrefix • ${s.artist}" else s.artist,
                        onMenu = {
                            val idx = entry.index
                            ui.songMenu(s, listOf(
                                ExtraAction(lblRemove, Icons.Default.Close) { c.player.removeQueueItem(idx) },
                                ExtraAction(lblUp, Icons.Default.ArrowUpward) { c.player.moveQueueItem(idx, idx - 1) },
                                ExtraAction(lblDown, Icons.Default.ArrowDownward) { c.player.moveQueueItem(idx, idx + 1) },
                            ))
                        },
                    ) { c.player.skipTo(entry.index) }
                }
            }
        }
    }
}

@Composable
private fun LyricsTab(song: SongEntity) {
    val c = LocalContainer.current
    val position by c.player.position.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val lyrics by produceState<Lyrics?>(null, song.id, refresh) { value = null; value = c.lyrics.get(song, forceRefresh = refresh > 0) ?: Lyrics(false, emptyList()) }
    val listState = rememberLazyListState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    val l = lyrics
    if (l == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (l.isEmpty) {
        EmptyState(stringResource(R.string.np_lyrics_not_found)) {
            TextButton(onClick = { refresh++ }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.np_try_again)) }
        }
        return
    }
    val active = l.activeIndex(position)
    LaunchedEffect(active) {
        // Follow the song unless the user scrolled by hand in the last few seconds.
        if (l.synced && active >= 0 && System.currentTimeMillis() - lastUserScroll > 4000) {
            listState.animateScrollToItem((active - 2).coerceAtLeast(0))   // default spring is already eased
        }
    }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) lastUserScroll = System.currentTimeMillis() }

    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 24.dp, horizontal = 12.dp)) {
        itemsIndexed(l.lines) { i, line ->
            val isActive = i == active
            val color by animateColorAsState(
                when {
                    !l.synced -> MaterialTheme.colorScheme.onSurface
                    isActive -> MaterialTheme.colorScheme.primary
                    i < active -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                }, dev.slurpdrome.ui.theme.Motion.follow(400), label = "lyric",
            )
            val text = line.v.ifBlank { "♪" }
            val lineScale by androidx.compose.animation.core.animateFloatAsState(
                if (isActive || !l.synced) 1f else 0.94f, dev.slurpdrome.ui.theme.Motion.follow(400), label = "lyricScale",
            )
            val mod = if (l.synced && line.t != null) Modifier.dpadClickable { c.player.seekTo(line.t) } else Modifier
            Text(
                text,
                color = color,
                fontSize = if (l.synced) 22.sp else 17.sp,
                lineHeight = if (l.synced) 30.sp else 25.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = lineScale; scaleY = lineScale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                    .then(mod).padding(horizontal = 8.dp, vertical = if (l.synced) 8.dp else 2.dp),
            )
        }
    }
}

@Composable
private fun SleepDialog(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val sleep by c.sleepTimer.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.np_sleep_timer)) },
        text = {
            Column {
                sleep.endsAt?.let {
                    val mins = ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                    Text(pluralStringResource(R.plurals.np_sleep_stops_in, mins.toInt(), mins.toInt()), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                }
                listOf(10, 15, 30, 45, 60, 90).forEach { m ->
                    Text(pluralStringResource(R.plurals.np_minutes, m, m), Modifier.fillMaxWidth().dpadClickable { c.sleepTimer.start(m); onDismiss() }.padding(12.dp))
                }
                Text(stringResource(R.string.np_until_end_of_track), Modifier.fillMaxWidth().dpadClickable { c.sleepTimer.startEndOfTrack(); onDismiss() }.padding(12.dp))
            }
        },
        confirmButton = { if (sleep.active) TextButton(onClick = { c.sleepTimer.cancel(); onDismiss() }) { Text(stringResource(R.string.np_turn_off)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun SpeedDialog(audiobook: Boolean = false, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings by c.settingsState.collectAsState()
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (audiobook) R.string.np_speed_title_books else R.string.np_speed_title, "%.2f".format(if (audiobook) settings.audiobookSpeed else settings.playbackSpeed))) },
        text = {
            Column {
                Slider(
                    value = if (audiobook) settings.audiobookSpeed else settings.playbackSpeed, valueRange = 0.5f..3f, steps = 9,
                    onValueChange = { v ->
                        val q = (v * 4).toInt() / 4f
                        scope.launch { c.settings.update { if (audiobook) it.copy(audiobookSpeed = q) else it.copy(playbackSpeed = q) } }
                    },
                    modifier = Modifier.focusRing(RoundedCornerShape(50)),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.np_skip_silence), Modifier.weight(1f))
                    Switch(settings.skipSilence, { v -> scope.launch { c.settings.update { it.copy(skipSilence = v) } } }, modifier = Modifier.focusRing(RoundedCornerShape(50)))
                }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { c.settings.update { if (audiobook) it.copy(audiobookSpeed = 1f) else it.copy(playbackSpeed = 1f) } } }) { Text(stringResource(R.string.np_reset)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

@Composable
private fun BookmarksDialog(song: SongEntity, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val marks by remember(song.id) { c.audiobooks.bookmarks(song.id) }.collectAsState(initial = emptyList())
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.np_bookmarks)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(note, { note = it }, placeholder = { Text(stringResource(R.string.np_note)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val pos = c.player.position.value
                        scope.launch { c.audiobooks.addBookmark(song.id, pos, note.trim()) }
                        note = ""
                    }, modifier = Modifier.focusRing()) { Text("+ " + formatMs(c.player.position.value)) }
                }
                if (marks.isEmpty()) Text(stringResource(R.string.np_no_bookmarks), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                marks.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().dpadClickable { c.player.seekTo(m.positionMs); onDismiss() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(formatMs(m.positionMs), style = MonoStyle, modifier = Modifier.width(64.dp))
                        Text(m.note.ifBlank { "—" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FIconButton(Icons.Default.Close, stringResource(R.string.common_delete),
 size = 36.dp) { scope.launch { c.audiobooks.deleteBookmark(m.id) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
