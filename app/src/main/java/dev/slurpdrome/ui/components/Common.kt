package dev.slurpdrome.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import dev.slurpdrome.player.eq.EqPresets
import dev.slurpdrome.player.eq.EqProfile
import coil.compose.SubcomposeAsyncImage
import dev.slurpdrome.data.db.AlbumEntity
import dev.slurpdrome.data.db.ArtistEntity
import dev.slurpdrome.data.db.DownloadEntity
import dev.slurpdrome.data.db.DownloadState
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.repo.Mix
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.theme.LocalExtraColors
import dev.slurpdrome.ui.theme.MonoStyle

/** Download states, provided once at the scaffold level instead of per-row flow collection. */
val LocalDownloads = compositionLocalOf<Map<String, DownloadEntity>> { emptyMap() }
val LocalCurrentSongId = compositionLocalOf<String?> { null }

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatMs(ms: Long) = formatDuration((ms / 1000).toInt())

@Composable
fun formatTotal(seconds: Int): String = formatTotal(LocalContext.current, seconds)

fun formatTotal(ctx: Context, seconds: Int): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60
    return if (h > 0) ctx.getString(R.string.duration_h_min, h, m) else ctx.getString(R.string.duration_min, m)
}

@Composable
fun pluralTracks(n: Int): String = pluralStringResource(R.plurals.tracks, n, n)

fun pluralTracks(ctx: Context, n: Int): String = ctx.resources.getQuantityString(R.plurals.tracks, n, n)

/** Display name of an EQ profile: built-in presets are localized, others show their stored name. */
@Composable
fun eqProfileName(p: EqProfile): String = EqPresets.nameRes(p)?.let { stringResource(it) } ?: p.name

fun eqProfileName(ctx: Context, p: EqProfile): String = EqPresets.nameRes(p)?.let { ctx.getString(it) } ?: p.name

@Composable
fun Artwork(
    coverArt: String?,
    modifier: Modifier = Modifier,
    size: Int = 300,
    shape: Shape = RoundedCornerShape(10.dp),
    fallback: ImageVector = Icons.Default.Album,
) {
    val url = LocalContainer.current.sources.artUrl(coverArt, size)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (url == null) {
            Icon(fallback, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxSize(0.45f))
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(fallback, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxSize(0.45f))
                    }
                },
            )
        }
    }
}

@Composable
fun DownloadBadge(songId: String, modifier: Modifier = Modifier) {
    val d = LocalDownloads.current[songId] ?: return
    val (icon, tint) = when (d.state) {
        DownloadState.DONE -> Icons.Default.CloudDone to LocalExtraColors.current.offline
        DownloadState.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        else -> Icons.Default.Downloading to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, null, tint = tint, modifier = modifier.size(16.dp))
}

@Composable
fun SongRow(
    song: SongEntity,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    subtitle: String = "${song.artist} • ${song.album}",
    onMenu: () -> Unit,
    onClick: () -> Unit,
) {
    val isCurrent = LocalCurrentSongId.current == song.id
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .dpadClickable(onLongClick = onMenu, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            showArt -> Box {
                Artwork(song.coverArt, Modifier.size(48.dp), size = 120, shape = RoundedCornerShape(8.dp), fallback = Icons.Default.MusicNote)
                if (isCurrent) Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.GraphicEq, null, tint = Color.White) }
            }
            index != null -> Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) Icon(Icons.Default.GraphicEq, null, tint = accent, modifier = Modifier.size(20.dp))
                else Text("$index", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (song.starred) Icon(Icons.Default.Favorite, null, tint = LocalExtraColors.current.favorite, modifier = Modifier.size(12.dp))
                DownloadBadge(song.id, Modifier.size(12.dp))
                Text(
                    subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(formatDuration(song.duration), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Touch affordance only — keyboard users press Menu/M on the row instead of an extra focus stop.
        FIconButton(
            Icons.Default.MoreVert, stringResource(R.string.common_actions),
            modifier = Modifier.focusProperties { canFocus = false },
            size = 40.dp, iconSize = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onMenu,
        )
    }
}

@Composable
fun AlbumCard(album: AlbumEntity, modifier: Modifier = Modifier, width: Dp? = null, onMenu: () -> Unit = {}, onClick: () -> Unit) {
    Column(
        (if (width != null) modifier.width(width) else modifier)
            .dpadClickable(onLongClick = onMenu, onClick = onClick)
            .padding(6.dp)
    ) {
        Artwork(album.coverArt, Modifier.fillMaxWidth().aspectRatio(1f), size = 400)
        Spacer(Modifier.height(6.dp))
        Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString()).joinToString(" • "),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AlbumRow(album: AlbumEntity, modifier: Modifier = Modifier, onMenu: () -> Unit = {}, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp).dpadClickable(onLongClick = onMenu, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(album.coverArt, Modifier.size(56.dp), size = 150)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString(), pluralTracks(album.songCount)).joinToString(" • "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (album.starred) Icon(Icons.Default.Favorite, null, tint = LocalExtraColors.current.favorite, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun ArtistRow(artist: ArtistEntity, modifier: Modifier = Modifier, onMenu: () -> Unit = {}, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp).dpadClickable(onLongClick = onMenu, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artist.coverArt, Modifier.size(48.dp), size = 150, shape = CircleShape, fallback = Icons.Default.Person)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.artist_album_count, artist.albumCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (artist.starred) Icon(Icons.Default.Favorite, null, tint = LocalExtraColors.current.favorite, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun ArtistCircle(artist: ArtistEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.width(104.dp).dpadClickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artist.coverArt, Modifier.size(88.dp), size = 200, shape = CircleShape, fallback = Icons.Default.Person)
        Spacer(Modifier.height(6.dp))
        Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

private val mixGradients = listOf(
    listOf(Color(0xFFFF6B35), Color(0xFFB8135A)),
    listOf(Color(0xFF1F9D8A), Color(0xFF1B3B8F)),
    listOf(Color(0xFF7B5CFF), Color(0xFF1E9BD7)),
    listOf(Color(0xFFE0A100), Color(0xFFD1453B)),
    listOf(Color(0xFF3F8F3F), Color(0xFF1F5F7A)),
    listOf(Color(0xFF8C3FB0), Color(0xFFE0457B)),
)

/** Mix tile: 2×2 artwork collage under a brand gradient, Spotify "Daily Mix" style. */
@Composable
fun MixCard(mix: Mix, index: Int, modifier: Modifier = Modifier, width: Dp = 156.dp, onClick: () -> Unit) {
    val g = mixGradients[index % mixGradients.size]
    Column(modifier.width(width).dpadClickable(onClick = onClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))) {
            val covers = mix.coverArts
            Column(Modifier.fillMaxSize()) {
                for (r in 0..1) Row(Modifier.weight(1f)) {
                    for (cIdx in 0..1) {
                        val art = covers.getOrNull(r * 2 + cIdx) ?: covers.firstOrNull()
                        Artwork(art, Modifier.weight(1f).fillMaxSize(), size = 200, shape = RoundedCornerShape(0.dp))
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(g.map { it.copy(alpha = 0.72f) })))
            Text(
                mix.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(mix.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onMore != null) FIconButton(Icons.Default.ArrowForward, stringResource(R.string.common_show_all), size = 40.dp, onClick = onMore)
    }
}

@Composable
fun EmptyState(text: String, icon: ImageVector = Icons.AutoMirrored.Filled.QueueMusic, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(12.dp)); action() }
    }
}

@Composable
fun RatingBar(rating: Int, modifier: Modifier = Modifier, size: Dp = 36.dp, onRate: (Int) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        for (i in 1..5) {
            FIconButton(
                if (i <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                stringResource(R.string.rating_n_of_5, i),
                size = size, iconSize = size * 0.6f,
                tint = if (i <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) { onRate(if (i == rating) 0 else i) }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text, style = MonoStyle, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

val ListBottomPadding = PaddingValues(bottom = 24.dp)
