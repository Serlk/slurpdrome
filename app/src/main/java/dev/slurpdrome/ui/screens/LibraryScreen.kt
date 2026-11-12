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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.slurpdrome.data.db.DownloadState
import dev.slurpdrome.data.repo.AlbumSort
import dev.slurpdrome.data.repo.SongSort
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.LocalNav
import dev.slurpdrome.ui.components.AlbumCard
import dev.slurpdrome.ui.components.AlbumRow
import dev.slurpdrome.ui.components.ArtistRow
import dev.slurpdrome.ui.components.EmptyState
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.Pill
import dev.slurpdrome.ui.components.ScreenBar
import dev.slurpdrome.ui.components.SongRow
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.focusRing
import dev.slurpdrome.ui.nav.Route
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import dev.slurpdrome.R

private val libraryTabs = listOf(R.string.lib_tab_albums, R.string.lib_tab_artists, R.string.lib_tab_tracks, R.string.lib_tab_genres, R.string.lib_tab_favorites, R.string.lib_tab_downloads)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(initialTab: Int) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, libraryTabs.lastIndex)) }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(dev.slurpdrome.ui.LocalTopInset.current))
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            libraryTabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }, modifier = Modifier.focusRing())
            }
        }
        when (tab) {
            0 -> AlbumsTab()
            1 -> ArtistsTab()
            2 -> SongsTab()
            3 -> GenresTab()
            4 -> FavoritesTab()
            5 -> DownloadsTab()
        }
    }
}

@Composable
private fun <T> SortMenu(current: T, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FIconButton(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.lib_sort)) { open = true }
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(label(o), color = if (o == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { onPick(o); open = false },
                )
            }
        }
    }
}

/** A–Z jump: the D-pad-friendly substitute for a touch fast-scroller. */
@Composable
private fun AlphabetJump(names: List<String>, onJump: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FIconButton(Icons.Default.SortByAlpha, stringResource(R.string.lib_jump_to_letter)) { open = true }
    if (!open) return
    val letters = remember(names) {
        names.mapIndexedNotNull { i, n -> n.firstOrNull()?.uppercaseChar()?.let { ch -> (if (ch.isLetter()) ch else '#') to i } }
            .distinctBy { it.first }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.lib_jump_to_letter)) },
        text = {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(GridCells.Adaptive(48.dp)) {
                items(letters) { (ch, idx) ->
                    Box(
                        Modifier.size(48.dp).dpadClickable { onJump(idx); open = false },
                        contentAlignment = Alignment.Center,
                    ) { Text("$ch", style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun AlbumsTab() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by c.settingsState.collectAsState()
    var sort by rememberSaveable { mutableStateOf(AlbumSort.NAME) }
    val albums by remember(sort, settings.offlineMode) { c.library.albums(sort, settings.offlineMode) }.collectAsState(initial = null)
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.lib_albums_count, albums?.size ?: 0, albums?.size ?: 0), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (sort == AlbumSort.NAME || sort == AlbumSort.ARTIST) AlphabetJump(albums.orEmpty().map { if (sort == AlbumSort.NAME) it.name else it.artist }) { i ->
            scope.launch { if (settings.albumGrid) gridState.scrollToItem(i) else listState.scrollToItem(i) }
        }
        SortMenu(sort, AlbumSort.entries, { ctx.getString(it.labelRes) }) { sort = it }
        FIconButton(if (settings.albumGrid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, stringResource(R.string.lib_view)) {
            c.scope.launch { c.settings.update { it.copy(albumGrid = !it.albumGrid) } }
        }
    }
    val list = albums ?: return
    if (list.isEmpty()) { EmptyState(stringResource(if (settings.offlineMode) R.string.lib_no_downloaded_albums else R.string.lib_no_albums)); return }
    if (settings.albumGrid) {
        LazyVerticalGrid(
            GridCells.Adaptive(if (settings.gridColumns > 0) 400.dp / settings.gridColumns else 140.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.id }) { a -> AlbumCard(a, onMenu = { ui.albumMenu(a) }) { nav.navigate(Route.Album(a.id)) } }
        }
    } else {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            items(list, key = { it.id }) { a -> AlbumRow(a, onMenu = { ui.albumMenu(a) }) { nav.navigate(Route.Album(a.id)) } }
        }
    }
}

@Composable
private fun ArtistsTab() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val artists by c.library.artists().collectAsState(initial = null)
    val state = rememberLazyListState()
    val list = artists ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.lib_artists_count, list.size, list.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
        AlphabetJump(list.map { it.name }) { i -> scope.launch { state.scrollToItem(i) } }
    }
    if (list.isEmpty()) { EmptyState(stringResource(R.string.lib_no_artists)); return }
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
        items(list, key = { it.id }) { a -> ArtistRow(a, onMenu = { ui.artistMenu(a) }) { nav.navigate(Route.Artist(a.id)) } }
    }
}

@Composable
private fun SongsTab() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val settings by c.settingsState.collectAsState()
    var sort by rememberSaveable { mutableStateOf(SongSort.TITLE) }
    val songs by remember(sort, settings.offlineMode) { c.library.songs(sort, settings.offlineMode) }.collectAsState(initial = null)
    val state = rememberLazyListState()
    val list = songs ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.tracks, list.size, list.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
        FIconButton(Icons.Default.Shuffle, stringResource(R.string.lib_shuffle_all)) { c.player.play(list, shuffle = true) }
        if (sort == SongSort.TITLE || sort == SongSort.ARTIST || sort == SongSort.ALBUM) AlphabetJump(list.map {
            when (sort) { SongSort.ARTIST -> it.artist; SongSort.ALBUM -> it.album; else -> it.title }
        }) { i -> scope.launch { state.scrollToItem(i) } }
        SortMenu(sort, SongSort.entries, { ctx.getString(it.labelRes) }) { sort = it }
    }
    if (list.isEmpty()) { EmptyState(stringResource(R.string.lib_no_tracks)); return }
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            // For huge libraries queue a window around the tapped song instead of 50k items.
            SongRow(s, onMenu = { ui.songMenu(s) }) {
                val from = (i - 200).coerceAtLeast(0)
                c.player.play(list.subList(from, (i + 800).coerceAtMost(list.size)), i - from)
            }
        }
    }
}

@Composable
private fun GenresTab() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val genres by c.library.genres().collectAsState(initial = null)
    val list = genres ?: return
    if (list.isEmpty()) { EmptyState(stringResource(R.string.lib_no_genres), Icons.Default.Tag); return }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
        items(list, key = { it.genre }) { g ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).dpadClickable { nav.navigate(Route.Genre(g.genre)) }.padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Tag, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(g.genre, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Pill(stringResource(R.string.lib_genre_counts, g.albumCount, g.songCount))
            }
        }
    }
}

@Composable
private fun FavoritesTab() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.lib_tab_tracks, R.string.lib_tab_albums, R.string.lib_tab_artists).forEachIndexed { i, t ->
            FilterChip(selected = sub == i, onClick = { sub = i }, label = { Text(stringResource(t)) }, modifier = Modifier.focusRing())
        }
    }
    when (sub) {
        0 -> {
            val songs by c.library.starredSongs().collectAsState(initial = emptyList())
            if (songs.isEmpty()) { EmptyState(stringResource(R.string.lib_no_fav_tracks)); return }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
                item {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { c.player.play(songs) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.lib_play_all)) }
                        TextButton(onClick = { c.player.play(songs, shuffle = true) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.lib_shuffle)) }
                    }
                }
                itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, onMenu = { ui.songMenu(s) }) { c.player.play(songs, i) } }
            }
        }
        1 -> {
            val albums by c.library.starredAlbums().collectAsState(initial = emptyList())
            if (albums.isEmpty()) { EmptyState(stringResource(R.string.lib_no_fav_albums)); return }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
                items(albums, key = { it.id }) { a -> AlbumRow(a, onMenu = { ui.albumMenu(a) }) { nav.navigate(Route.Album(a.id)) } }
            }
        }
        else -> {
            val artists by c.library.starredArtists().collectAsState(initial = emptyList())
            if (artists.isEmpty()) { EmptyState(stringResource(R.string.lib_no_fav_artists)); return }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
                items(artists, key = { it.id }) { a -> ArtistRow(a, onMenu = { ui.artistMenu(a) }) { nav.navigate(Route.Artist(a.id)) } }
            }
        }
    }
}

@Composable
private fun DownloadsTab() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val downloads by c.downloads.downloads.collectAsState()
    val songs by remember { c.db.downloads().observeDownloadedSongs() }.collectAsState(initial = emptyList())
    val pending = downloads.values.count { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
    val failed = downloads.values.count { it.state == DownloadState.FAILED }
    val bytes = downloads.values.filter { it.state == DownloadState.DONE }.sumOf { it.bytes }
    var confirmClear by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.lib_downloads_summary, songs.size, songs.size, "%.1f".format(bytes / 1_048_576.0)), style = MaterialTheme.typography.titleSmall)
            if (pending > 0 || failed > 0) Text(
                listOfNotNull(pending.takeIf { it > 0 }?.let { stringResource(R.string.lib_queued_n, it) }, failed.takeIf { it > 0 }?.let { stringResource(R.string.lib_failed_n, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (failed > 0 || pending > 0) FIconButton(Icons.Default.Refresh, stringResource(R.string.lib_retry)) { c.scope.launch { c.downloads.retryFailed() } }
        if (songs.isNotEmpty()) FIconButton(Icons.Default.Shuffle, stringResource(R.string.lib_shuffle)) { c.player.play(songs, shuffle = true) }
        if (downloads.isNotEmpty()) FIconButton(Icons.Default.DeleteSweep, stringResource(R.string.lib_delete_all)) { confirmClear = true }
    }
    val net by c.network.state.collectAsState()
    if (c.downloads.waitingForWifi(pending, net.unmetered)) {
        androidx.compose.material3.Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.lib_waiting_wifi, pending), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.lib_on_mobile_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { c.scope.launch { c.downloads.schedule(forceMobile = true) } }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.lib_download_now)) }
            }
        }
    }
    if (songs.isEmpty()) {
        EmptyState(stringResource(R.string.lib_downloads_empty))
    } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, onMenu = { ui.songMenu(s) }) { c.player.play(songs, i) } }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.lib_delete_all_downloads_q)) },
        text = { Text(stringResource(R.string.lib_delete_all_downloads_msg)) },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                c.scope.launch {
                    c.db.pins().all().forEach { c.db.pins().delete(it.kind, it.targetId) }
                    c.downloads.clearAll()
                }
            }) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
