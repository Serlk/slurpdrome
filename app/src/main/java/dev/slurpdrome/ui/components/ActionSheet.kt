package dev.slurpdrome.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.AppContainer
import dev.slurpdrome.R
import dev.slurpdrome.data.db.AlbumEntity
import dev.slurpdrome.data.db.ArtistEntity
import dev.slurpdrome.data.db.DownloadState
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.repo.MixSeed
import dev.slurpdrome.data.repo.PinKind
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class ExtraAction(val label: String, val icon: ImageVector, val run: () -> Unit)

sealed interface MenuTarget {
    data class Song(val song: SongEntity, val extra: List<ExtraAction> = emptyList()) : MenuTarget
    data class Album(val album: AlbumEntity) : MenuTarget
    data class Artist(val artist: ArtistEntity) : MenuTarget
}

/** Screen-independent UI actions: context menus, snackbars, playlist picker, navigation. */
@Stable
class UiActions(
    val container: AppContainer,
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    val navigate: (Route) -> Unit,
) {
    var menu by mutableStateOf<MenuTarget?>(null)
    var playlistPicker by mutableStateOf<List<String>?>(null)

    /** Resolves a string resource against the app context (for toasts fired from lambdas/coroutines). */
    fun str(@StringRes id: Int, vararg args: Any): String = container.app.getString(id, *args)

    fun toast(msg: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }

    fun songMenu(song: SongEntity, extra: List<ExtraAction> = emptyList()) { menu = MenuTarget.Song(song, extra) }
    fun albumMenu(album: AlbumEntity) { menu = MenuTarget.Album(album) }
    fun artistMenu(artist: ArtistEntity) { menu = MenuTarget.Artist(artist) }

    fun instantMix(seed: MixSeed, radio: Boolean = false) {
        toast(str(if (radio) R.string.act_toast_starting_radio else R.string.act_toast_building_mix))
        scope.launch {
            val ids = container.mixes.instantMix(seed)
            if (ids.isEmpty()) { toast(str(R.string.act_toast_not_enough_data)); return@launch }
            container.radioEnabled.value = radio
            container.player.playIds(ids)
        }
    }

    fun download(songIds: List<String>) {
        scope.launch { container.downloads.download(songIds); toast(str(R.string.act_toast_added_to_downloads, songIds.size)) }
    }
}

val LocalUi = staticCompositionLocalOf<UiActions> { error("no ui") }

@Composable
fun rememberUiActions(container: AppContainer, snackbar: SnackbarHostState, navigate: (Route) -> Unit): UiActions {
    val scope = rememberCoroutineScope()
    return remember { UiActions(container, snackbar, scope, navigate) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheetHost(ui: UiActions) {
    val target = ui.menu
    if (target != null) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { ui.menu = null }, sheetState = sheet) {
            val close: () -> Unit = { ui.menu = null }
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
                when (target) {
                    is MenuTarget.Song -> SongActions(target, ui, close)
                    is MenuTarget.Album -> AlbumActions(target.album, ui, close)
                    is MenuTarget.Artist -> ArtistActions(target.artist, ui, close)
                }
            }
        }
    }
    ui.playlistPicker?.let { ids -> PlaylistPickerDialog(ids, ui) { ui.playlistPicker = null } }
}

@Composable
private fun SheetHeader(coverArt: String?, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(coverArt, Modifier.size(56.dp), size = 150)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
fun ActionItem(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp).dpadClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SongActions(t: MenuTarget.Song, ui: UiActions, close: () -> Unit) {
    val c = ui.container
    val s = t.song
    val live by c.library.song(s.id).collectAsState(initial = s)
    val song = live ?: s
    val dl = LocalDownloads.current[song.id]
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    SheetHeader(song.coverArt, song.title, "${song.artist} • ${song.album}")
    ActionItem(Icons.Default.SkipNext, stringResource(R.string.act_play_next), Modifier.focusRequester(focus)) {
        c.player.playNext(listOf(song)); ui.toast(ui.str(R.string.act_toast_playing_next)); close()
    }
    ActionItem(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.act_add_to_queue)) { c.player.addToQueue(listOf(song)); ui.toast(ui.str(R.string.act_toast_added_to_queue)); close() }
    ActionItem(Icons.Default.AutoAwesome, stringResource(R.string.act_song_mix)) { ui.instantMix(MixSeed.Song(song.id)); close() }
    ActionItem(Icons.Default.Radio, stringResource(R.string.act_endless_radio)) { ui.instantMix(MixSeed.Song(song.id), radio = true); close() }
    ActionItem(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.act_add_to_playlist_ellipsis)) { ui.playlistPicker = listOf(song.id); close() }
    ActionItem(
        if (song.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
        if (song.starred) stringResource(R.string.act_remove_from_favorites) else stringResource(R.string.act_add_to_favorites),
    ) { c.library.toggleSongStar(song) }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.act_rating), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp, end = 12.dp))
        RatingBar(song.rating) { c.library.setRating(song.id, "song", it) }
    }
    when (dl?.state) {
        DownloadState.DONE -> ActionItem(Icons.Default.DownloadDone, stringResource(R.string.act_remove_from_device)) {
            ui.toast(ui.str(R.string.act_toast_removed_from_device)); close()
            c.scope.launch { c.downloads.remove(listOf(song.id)) }
        }
        null, DownloadState.FAILED -> ActionItem(Icons.Default.Download, stringResource(R.string.act_download)) { ui.download(listOf(song.id)); close() }
        else -> ActionItem(Icons.Default.Download, stringResource(R.string.act_downloading_cancel)) {
            close(); c.scope.launch { c.downloads.remove(listOf(song.id)) }
        }
    }
    song.albumId?.let { id -> ActionItem(Icons.Default.Album, stringResource(R.string.act_go_to_album)) { ui.navigate(Route.Album(id)); close() } }
    song.artistId?.let { id -> ActionItem(Icons.Default.Person, stringResource(R.string.act_go_to_artist)) { ui.navigate(Route.Artist(id)); close() } }
    t.extra.forEach { a -> ActionItem(a.icon, a.label) { a.run(); close() } }
}

@Composable
private fun AlbumActions(album: AlbumEntity, ui: UiActions, close: () -> Unit) {
    val c = ui.container
    val scope = rememberCoroutineScope()
    val pins by c.downloads.pins.collectAsState()
    val pinned = (PinKind.ALBUM to album.id) in pins
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    SheetHeader(album.coverArt, album.name, album.artist)
    ActionItem(Icons.Default.PlayArrow, stringResource(R.string.act_play), Modifier.focusRequester(focus)) {
        scope.launch { c.player.play(c.library.albumSongsOnce(album.id)) }; close()
    }
    ActionItem(Icons.Default.Shuffle, stringResource(R.string.act_shuffle)) { scope.launch { c.player.play(c.library.albumSongsOnce(album.id), shuffle = true) }; close() }
    ActionItem(Icons.Default.SkipNext, stringResource(R.string.act_play_next)) { scope.launch { c.player.playNext(c.library.albumSongsOnce(album.id)); ui.toast(ui.str(R.string.act_toast_playing_next)) }; close() }
    ActionItem(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.act_add_to_queue)) { scope.launch { c.player.addToQueue(c.library.albumSongsOnce(album.id)); ui.toast(ui.str(R.string.act_toast_added_to_queue)) }; close() }
    ActionItem(Icons.Default.AutoAwesome, stringResource(R.string.act_album_mix)) { ui.instantMix(MixSeed.Album(album.id)); close() }
    ActionItem(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.act_add_to_playlist_ellipsis)) {
        scope.launch { ui.playlistPicker = c.library.albumSongsOnce(album.id).map { it.id } }; close()
    }
    ActionItem(if (album.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (album.starred) stringResource(R.string.act_remove_from_favorites) else stringResource(R.string.act_add_to_favorites)) {
        c.library.toggleAlbumStar(album); close()
    }
    ActionItem(if (pinned) Icons.Default.DownloadDone else Icons.Default.Download, if (pinned) stringResource(R.string.act_unkeep_offline) else stringResource(R.string.act_keep_offline)) {
        c.scope.launch { c.downloads.setPinned(PinKind.ALBUM, album.id, !pinned) }
        ui.toast(ui.str(if (pinned) R.string.act_toast_album_unpinned else R.string.act_toast_album_pinned)); close()
    }
    album.artistId?.let { id -> ActionItem(Icons.Default.Person, stringResource(R.string.act_go_to_artist)) { ui.navigate(Route.Artist(id)); close() } }
}

@Composable
private fun ArtistActions(artist: ArtistEntity, ui: UiActions, close: () -> Unit) {
    val c = ui.container
    val scope = rememberCoroutineScope()
    val pins by c.downloads.pins.collectAsState()
    val pinned = (PinKind.ARTIST to artist.id) in pins
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    SheetHeader(artist.coverArt, artist.name, stringResource(R.string.artist_album_count, artist.albumCount))
    ActionItem(Icons.Default.Shuffle, stringResource(R.string.act_shuffle_all), Modifier.focusRequester(focus)) {
        scope.launch { c.player.play(c.library.artistSongsOnce(artist.id), shuffle = true) }; close()
    }
    ActionItem(Icons.Default.Radio, stringResource(R.string.act_artist_radio)) { ui.instantMix(MixSeed.Artist(artist.id), radio = true); close() }
    ActionItem(if (artist.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (artist.starred) stringResource(R.string.act_remove_from_favorites) else stringResource(R.string.act_add_to_favorites)) {
        c.library.toggleArtistStar(artist); close()
    }
    ActionItem(if (pinned) Icons.Default.DownloadDone else Icons.Default.Download, if (pinned) stringResource(R.string.act_unkeep_offline) else stringResource(R.string.act_keep_all_offline)) {
        c.scope.launch { c.downloads.setPinned(PinKind.ARTIST, artist.id, !pinned) }; close()
    }
}

@Composable
fun PlaylistPickerDialog(songIds: List<String>, ui: UiActions, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val all by c.playlists.all().collectAsState(initial = emptyList())
    val local by c.localPlaylists.all().collectAsState(initial = emptyList())
    // Navidrome playlists can only hold songs from their own server; device playlists take anything.
    val songSources = remember(songIds) { songIds.map(dev.slurpdrome.data.source.Gid::source).toSet() }
    val serverPlaylists = all.filter { it.sourceId in songSources }
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.act_add_to_playlist)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(R.string.act_new_playlist)) }, singleLine = true, modifier = Modifier.weight(1f).dpadEscape())
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        scope.launch { c.localPlaylists.create(newName.trim(), songIds); ui.toast(ui.str(R.string.act_toast_playlist_created, newName.trim())) }
                        onDismiss()
                    }) { Text(stringResource(R.string.common_create)) }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (local.isNotEmpty()) item { PickerHeader(stringResource(R.string.act_on_device)) }
                    items(local, key = { "l" + it.id }) { p ->
                        PickerRow(null, p.name, stringResource(R.string.act_any_sources)) {
                            scope.launch { c.localPlaylists.add(p.id, songIds); ui.toast(ui.str(R.string.act_toast_added_to, p.name)) }
                            onDismiss()
                        }
                    }
                    if (serverPlaylists.isNotEmpty()) item { PickerHeader(stringResource(R.string.act_on_server)) }
                    items(serverPlaylists, key = { it.id }) { p ->
                        PickerRow(p.coverArt, p.name, pluralTracks(p.songCount) + (c.sources.source(p.sourceId)?.name?.let { " • $it" } ?: "")) {
                            scope.launch {
                                c.playlists.add(p.id, songIds)
                                    .onSuccess { ui.toast(ui.str(R.string.act_toast_added_to, p.name)) }
                                    .onFailure { ui.toast(it.message ?: ui.str(R.string.act_toast_no_server_connection)) }
                            }
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
 },
    )
}

@Composable
private fun PickerHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun PickerRow(coverArt: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().dpadClickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(coverArt, Modifier.size(40.dp), size = 100, fallback = Icons.AutoMirrored.Filled.QueueMusic)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
