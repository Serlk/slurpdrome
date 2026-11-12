package dev.slurpdrome.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import dev.slurpdrome.data.repo.SearchResults
import dev.slurpdrome.data.repo.SmartPlaylistRepository
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.LocalNav
import dev.slurpdrome.ui.components.AlbumCard
import dev.slurpdrome.ui.components.ActionItem
import dev.slurpdrome.ui.components.ArtistRow
import dev.slurpdrome.ui.components.Artwork
import dev.slurpdrome.ui.components.EmptyState
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.ScreenBar
import dev.slurpdrome.ui.components.SectionHeader
import dev.slurpdrome.ui.components.SongRow
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.dpadEscape
import dev.slurpdrome.ui.components.pluralTracks
import dev.slurpdrome.ui.nav.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val focusManager = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        delay(180)   // debounce keystrokes; the search itself is local and fast
        results = c.library.search(query)
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(dev.slurpdrome.ui.LocalTopInset.current))
        OutlinedTextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus).dpadEscape(),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) FIconButton(Icons.Default.Clear, stringResource(R.string.search_clear)) { query = "" } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // Enter jumps into results so D-pad users can continue without touching the screen.
            keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
        )
        val r = results
        if (query.isBlank()) { EmptyState(stringResource(R.string.search_hint), Icons.Default.Search); return@Column }
        if (r.artists.isEmpty() && r.albums.isEmpty() && r.songs.isEmpty()) { EmptyState(stringResource(R.string.search_nothing_found)); return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            if (r.artists.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_artists)) }
                items(r.artists.take(5), key = { "ar-" + it.id }) { a -> ArtistRow(a, onMenu = { ui.artistMenu(a) }) { nav.navigate(Route.Artist(a.id)) } }
            }
            if (r.albums.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.search_albums)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(r.albums, key = { "al-" + it.id }) { a -> AlbumCard(a, width = 140.dp, onMenu = { ui.albumMenu(a) }) { nav.navigate(Route.Album(a.id)) } }
                    }
                }
            }
            if (r.songs.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.search_tracks)) }
                itemsIndexed(r.songs, key = { _, s -> "s-" + s.id }) { i, s -> SongRow(s, onMenu = { ui.songMenu(s) }) { c.player.play(r.songs, i) } }
            }
        }
    }
}

@Composable
fun PlaylistsScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val playlists by c.playlists.all().collectAsState(initial = emptyList())
    val smart by c.smartPlaylists.all().collectAsState(initial = emptyList())
    val pins by c.downloads.pins.collectAsState()
    var createSmart by remember { mutableStateOf(false) }
    var createLocal by remember { mutableStateOf(false) }
    val local by c.localPlaylists.all().collectAsState(initial = emptyList())
    val localCounts by remember { c.localPlaylists.counts() }.collectAsState(initial = emptyList()).let { st ->
        androidx.compose.runtime.derivedStateOf { st.value.associate { it.id to it.n } }
    }
    var createPlain by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(top = dev.slurpdrome.ui.LocalTopInset.current, bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(R.string.search_my_playlists), Modifier.weight(1f))
                    FIconButton(Icons.Default.Add, stringResource(R.string.search_new_local_playlist)) { createLocal = true }
                }
            }
            if (local.isEmpty()) item {
                Text(
                    stringResource(R.string.search_local_playlists_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(local, key = { "lp-" + it.id }) { p ->
                PlaylistLine(null, p.name, pluralTracks(localCounts[p.id] ?: 0) + " • " + stringResource(R.string.search_on_device_suffix), pinned = ("localplaylist" to p.id.toString()) in pins) {
                    nav.navigate(Route.LocalPlaylist(p.id))
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(R.string.search_smart_playlists), Modifier.weight(1f))
                    FIconButton(Icons.Default.Add, stringResource(R.string.search_new_smart_playlist)) { createSmart = true }
                }
            }
            if (smart.isEmpty()) item {
                Text(
                    stringResource(R.string.search_smart_playlists_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(smart, key = { "sm-" + it.id }) { s ->
                PlaylistLine(null, s.name, stringResource(R.string.search_smart_playlist), pinned = ("smart" to s.id.toString()) in pins, smart = true) { nav.navigate(Route.SmartPlaylist(s.id)) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(if (c.sources.servers.size > 1) R.string.search_on_servers else R.string.search_on_server), Modifier.weight(1f))
                    if (c.sources.servers.isNotEmpty()) FIconButton(Icons.Default.Add, stringResource(R.string.search_new_playlist)) { createPlain = true }
                }
            }
            if (playlists.isEmpty()) item { EmptyState(stringResource(R.string.search_no_playlists)) }
            items(playlists, key = { it.id }) { p ->
                val origin = if (c.sources.servers.size > 1) c.sources.source(p.sourceId)?.name else p.owner
                PlaylistLine(p.coverArt, p.name, pluralTracks(p.songCount) + (origin?.let { " • $it" } ?: ""), pinned = ("playlist" to p.id) in pins) {
                    nav.navigate(Route.Playlist(p.id))
                }
            }
        }
    }

    if (createSmart) AlertDialog(
        onDismissRequest = { createSmart = false },
        title = { Text(stringResource(R.string.search_new_smart_playlist)) },
        text = {
            LazyColumn {
                item { ActionItem(Icons.Default.Add, stringResource(R.string.search_from_scratch)) { createSmart = false; nav.navigate(Route.SmartEditor()) } }
                itemsIndexed(SmartPlaylistRepository.templates) { i, (name, _) ->
                    ActionItem(Icons.Default.AutoAwesome, stringResource(name)) { createSmart = false; nav.navigate(Route.SmartEditor(template = i)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { createSmart = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (createLocal) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createLocal = false },
            title = { Text(stringResource(R.string.search_new_local_playlist)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.search_name)) }, singleLine = true, modifier = Modifier.dpadEscape()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    createLocal = false
                    scope.launch { nav.navigate(Route.LocalPlaylist(c.localPlaylists.create(name.trim()))) }
                }) { Text(stringResource(R.string.common_create)) }
            },
            dismissButton = { TextButton(onClick = { createLocal = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (createPlain) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createPlain = false },
            title = { Text(stringResource(R.string.search_new_playlist)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.search_name)) }, singleLine = true, modifier = Modifier.dpadEscape()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    createPlain = false
                    scope.launch {
                        c.playlists.create(name.trim(), emptyList())
                            .onSuccess { id -> id?.let { nav.navigate(Route.Playlist(it)) } }
                            .onFailure { ui.toast(ui.str(R.string.act_toast_no_server_connection)) }
                    }
                }) { Text(stringResource(R.string.common_create)) }
            },
            dismissButton = { TextButton(onClick = { createPlain = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun PlaylistLine(coverArt: String?, name: String, subtitle: String, pinned: Boolean, smart: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).dpadClickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(coverArt, Modifier.size(52.dp), size = 150, fallback = if (smart) Icons.Default.AutoAwesome else Icons.AutoMirrored.Filled.QueueMusic)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (pinned) Icon(Icons.Default.CloudDone, stringResource(R.string.search_offline),
 tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
    }
}

