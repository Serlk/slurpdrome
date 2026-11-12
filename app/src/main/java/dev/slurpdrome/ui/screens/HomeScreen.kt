package dev.slurpdrome.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.slurpdrome.data.db.AlbumEntity
import dev.slurpdrome.data.prefs.HomeSection
import dev.slurpdrome.data.repo.Mix
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.LocalNav
import dev.slurpdrome.ui.components.AlbumCard
import dev.slurpdrome.ui.components.EmptyState
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.MixCard
import dev.slurpdrome.ui.components.ScreenBar
import dev.slurpdrome.ui.components.SectionHeader
import dev.slurpdrome.ui.components.SongRow
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.focusRing
import dev.slurpdrome.ui.components.formatTotal
import dev.slurpdrome.ui.components.pluralTracks
import dev.slurpdrome.ui.nav.Route
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import dev.slurpdrome.R

@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val settings by c.settingsState.collectAsState()
    val sync by c.sync.state.collectAsState()
    val songCount by c.library.songCount().collectAsState(initial = -1)

    var mixesRefresh by remember { mutableIntStateOf(0) }
    val mixes by produceState<List<Mix>?>(null, mixesRefresh, songCount) {
        if (songCount > 0) value = c.mixes.mixes(force = mixesRefresh > 0)
    }

    Column(Modifier.fillMaxSize()) {
        val top = dev.slurpdrome.ui.LocalTopInset.current
        if (songCount == 0) {
            Box(Modifier.fillMaxSize().padding(top = top), contentAlignment = Alignment.Center) {
                if (sync.running) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.home_syncing_stage, sync.stage), style = MaterialTheme.typography.titleMedium)
                    if (sync.done > 0) Text("${sync.done}${if (sync.total > 0) " / ${sync.total}" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else EmptyState(sync.error?.let { stringResource(R.string.home_sync_error, it) } ?: stringResource(R.string.home_library_empty)) {
                    Button(onClick = { c.sync.request() }) { Text(stringResource(R.string.home_sync)) }
                }
            }
            return@Column
        }

        // Content starts under the glass bar and scrolls beneath it.
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            for (section in settings.homeSections) when (section) {
                HomeSection.MIXES -> {
                    item(key = "mixes-h") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader(stringResource(section.titleRes), Modifier.weight(1f))
                            FIconButton(Icons.Default.Refresh, stringResource(R.string.home_refresh_mixes), size = 40.dp) { mixesRefresh++ }
                        }
                    }
                    item(key = "mixes") {
                        val list = mixes
                        if (list == null) Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else {
                            val firstMix = remember { androidx.compose.ui.focus.FocusRequester() }
                            dev.slurpdrome.ui.components.AutoFocusForKeys(firstMix)
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                itemsIndexed(list, key = { _, m -> m.key }) { i, m ->
                                    MixCard(m, i, if (i == 0) Modifier.focusRequester(firstMix) else Modifier) { nav.navigate(Route.MixDetail(m.key)) }
                                }
                            }
                        }
                    }
                }
                HomeSection.CONTINUE -> continueListening()
                HomeSection.RECENTLY_PLAYED -> albumShelf("rp", section.titleRes) { c.library.recentlyPlayedAlbums() }
                HomeSection.RECENTLY_ADDED -> albumShelf("ra", section.titleRes) { c.library.recentlyAddedAlbums() }
                HomeSection.MOST_PLAYED -> albumShelf("mp", section.titleRes) { c.library.mostPlayedAlbums() }
                HomeSection.RANDOM_ALBUMS -> item(key = "random") { RandomAlbums(stringResource(section.titleRes)) }
                HomeSection.FAVORITES -> item(key = "fav") {
                    val fav by c.library.starredSongs().collectAsState(initial = emptyList())
                    if (fav.isNotEmpty()) Column {
                        SectionHeader("${stringResource(section.titleRes)} · ${fav.size}") { nav.navigate(Route.Library(4)) }
                        fav.take(5).forEachIndexed { i, s ->
                            SongRow(s, onMenu = { ui.songMenu(s) }) { c.player.play(fav, i) }
                        }
                    }
                }
                HomeSection.DOWNLOADED -> item(key = "dl") {
                    val downloaded by c.downloads.downloads.collectAsState()
                    val done = downloaded.values.count { it.state == 2 }
                    if (done > 0) Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                            .dpadClickable { nav.navigate(Route.Library(5)) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.CloudDone, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(section.titleRes), style = MaterialTheme.typography.titleMedium)
                            Text(pluralStringResource(R.plurals.home_tracks_on_device, done, done), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumShelf(
    key: String,
    @androidx.annotation.StringRes titleRes: Int,
    source: () -> kotlinx.coroutines.flow.Flow<List<AlbumEntity>>,
) {
    item(key = key) {
        val ui = LocalUi.current
        val nav = LocalNav.current
        val albums by remember(key) { source() }.collectAsState(initial = emptyList())
        if (albums.isNotEmpty()) Column {
            SectionHeader(stringResource(titleRes))
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(albums, key = { it.id }) { a ->
                    AlbumCard(a, width = 148.dp, onMenu = { ui.albumMenu(a) }) { nav.navigate(Route.Album(a.id)) }
                }
            }
        }
    }
}

@Composable
private fun RandomAlbums(title: String) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    var seed by remember { mutableIntStateOf(0) }
    val albums by produceState(emptyList<AlbumEntity>(), seed) { value = c.library.randomAlbums(16) }
    if (albums.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title, Modifier.weight(1f))
            FIconButton(Icons.Default.Refresh, stringResource(R.string.home_shuffle_albums), size = 40.dp) { seed++ }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(albums, key = { it.id }) { a ->
                AlbumCard(a, width = 148.dp, onMenu = { ui.albumMenu(a) }) { nav.navigate(Route.Album(a.id)) }
            }
        }
    }
}

@Composable
fun MixScreen(key: String) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val mix by produceState<Mix?>(null, key) { value = c.mixes.mixes().firstOrNull { it.key == key } }
    val songs by produceState(emptyList<dev.slurpdrome.data.db.SongEntity>(), mix) {
        value = mix?.let { c.library.songsByIds(it.songIds) }.orEmpty()
    }
    Column(Modifier.fillMaxSize()) {
        ScreenBar(mix?.title ?: stringResource(R.string.home_mix_fallback))
        val m = mix
        if (m == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(m.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${pluralTracks(songs.size)} • ${formatTotal(songs.sumOf { it.duration })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                    dev.slurpdrome.ui.components.AutoFocusForKeys(playFocus, m.key)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { c.radioEnabled.value = false; c.player.play(songs, context = dev.slurpdrome.player.PlayContext.Mix(m.key)) }, modifier = Modifier.focusRequester(playFocus).focusRing()) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.home_play))
                        }
                        FilledTonalButton(onClick = { c.radioEnabled.value = false; c.player.play(songs, shuffle = true, context = dev.slurpdrome.player.PlayContext.Mix(m.key)) }, modifier = Modifier.focusRing()) {
                            Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.home_shuffle))
                        }
                    }
                }
            }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                SongRow(s, onMenu = { ui.songMenu(s) }) { c.radioEnabled.value = false; c.player.play(songs, i, context = dev.slurpdrome.player.PlayContext.Mix(m.key)) }
            }
        }
    }
}

/** In-progress audiobooks / long albums, newest first. */
private fun androidx.compose.foundation.lazy.LazyListScope.continueListening() {
    item(key = "continue") {
        val c = LocalContainer.current
        val nav = LocalNav.current
        val points by remember { c.audiobooks.inProgress() }.collectAsState(initial = emptyList())
        val albums by produceState(emptyList<Pair<AlbumEntity, dev.slurpdrome.data.db.ResumePointEntity>>(), points) {
            value = points.mapNotNull { p -> c.db.albums().byId(p.contextKey.removePrefix("album:"))?.let { it to p } }
        }
        if (albums.isNotEmpty()) Column {
            SectionHeader(stringResource(R.string.home_section_continue))
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(albums, key = { it.first.id }) { (a, p) ->
                    Column(Modifier.width(148.dp)) {
                        AlbumCard(a, width = 148.dp) { nav.navigate(Route.Album(a.id)) }
                        val songs by produceState(emptyList<dev.slurpdrome.data.db.SongEntity>(), a.id) { value = c.library.albumSongsOnce(a.id) }
                        val idx = songs.indexOfFirst { it.id == p.songId }
                        if (idx >= 0) {
                            val done = songs.take(idx).sumOf { it.duration } * 1000L + p.positionMs
                            val all = songs.sumOf { it.duration } * 1000L
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { if (all > 0) (done.toFloat() / all).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
