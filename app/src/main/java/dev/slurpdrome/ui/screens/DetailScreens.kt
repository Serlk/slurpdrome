package dev.slurpdrome.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.repo.FieldType
import dev.slurpdrome.data.repo.MixSeed
import dev.slurpdrome.data.repo.PinKind
import dev.slurpdrome.data.repo.SmartField
import dev.slurpdrome.data.repo.SmartOp
import dev.slurpdrome.data.repo.SmartPlaylistRepository
import dev.slurpdrome.data.repo.SmartRule
import dev.slurpdrome.data.repo.SmartSpec
import dev.slurpdrome.data.repo.SongSort
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.components.dpadEscape
import dev.slurpdrome.ui.LocalNav
import dev.slurpdrome.ui.components.AlbumCard
import dev.slurpdrome.ui.components.ArtistCircle
import dev.slurpdrome.ui.components.Artwork
import dev.slurpdrome.ui.components.EmptyState
import dev.slurpdrome.ui.components.ExtraAction
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.ScreenBar
import dev.slurpdrome.ui.components.SectionHeader
import dev.slurpdrome.ui.components.SongRow
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.focusRing
import dev.slurpdrome.ui.components.formatTotal
import dev.slurpdrome.ui.components.pluralTracks
import dev.slurpdrome.ui.nav.Route
import dev.slurpdrome.ui.theme.LocalExtraColors
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import dev.slurpdrome.R

data class HeaderButton(val icon: ImageVector, val label: String, val tint: androidx.compose.ui.graphics.Color? = null, val onClick: () -> Unit)

/** Shared header: big art + title + Play/Shuffle + secondary icon actions. Goes side-by-side on wide screens. */
@Composable
fun DetailHeader(
    coverArt: String?,
    title: String,
    subtitle: String?,
    meta: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    secondary: List<HeaderButton> = emptyList(),
    circle: Boolean = false,
    onSubtitleClick: (() -> Unit)? = null,
) {
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    dev.slurpdrome.ui.components.AutoFocusForKeys(playFocus, title)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
        val wide = maxWidth > 520.dp
        val art = if (wide) 180.dp else (maxWidth * 0.55f).coerceAtMost(240.dp)
        val shape = if (circle) CircleShape else MaterialTheme.shapes.large
        val info: @Composable () -> Unit = {
            Column(horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = if (onSubtitleClick != null) Modifier.dpadClickable(onClick = onSubtitleClick).padding(horizontal = 4.dp, vertical = 2.dp) else Modifier,
                    )
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onPlay, modifier = Modifier.focusRequester(playFocus).focusRing()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.detail_play)) }
                    FilledTonalButton(onClick = onShuffle, modifier = Modifier.focusRing()) { Icon(Icons.Default.Shuffle, null) }
                    secondary.forEach { b -> FIconButton(b.icon, b.label, tint = b.tint ?: MaterialTheme.colorScheme.onSurfaceVariant, onClick = b.onClick) }
                }
            }
        }
        if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(coverArt, Modifier.size(art), size = 600, shape = shape, fallback = if (circle) Icons.Default.Person else Icons.Default.PlayArrow)
            Spacer(Modifier.width(20.dp)); info()
        } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(coverArt, Modifier.size(art), size = 600, shape = shape, fallback = if (circle) Icons.Default.Person else Icons.Default.PlayArrow)
            Spacer(Modifier.height(16.dp)); info()
        }
    }
}

@Composable
fun pinButtonFor(kind: String, id: String): HeaderButton = pinButton(kind, id)

@Composable
private fun pinButton(kind: String, id: String): HeaderButton {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ui = LocalUi.current
    val pins by c.downloads.pins.collectAsState()
    val pinned = (kind to id) in pins
    return HeaderButton(
        if (pinned) Icons.Default.DownloadDone else Icons.Default.Download,
        stringResource(if (pinned) R.string.detail_unpin_offline else R.string.detail_keep_offline),
        if (pinned) LocalExtraColors.current.offline else null,
    ) {
        c.scope.launch { c.downloads.setPinned(kind, id, !pinned) }
        ui.toast(ctx.getString(if (pinned) R.string.detail_toast_unpinned else R.string.detail_toast_pinned))
    }
}

@Composable
fun AlbumScreen(id: String) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val album by c.library.album(id).collectAsState(initial = null)
    val songs by c.library.albumSongs(id).collectAsState(initial = emptyList())
    val a = album
    Column(Modifier.fillMaxSize()) {
        ScreenBar(a?.name ?: "") { if (a != null) FIconButton(Icons.Default.MoreVert, stringResource(R.string.common_actions)) { ui.albumMenu(a) } }
        if (a == null) return@Column
        val multiDisc = songs.map { it.disc }.distinct().size > 1
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item {
                DetailHeader(
                    a.coverArt, a.name, a.artist,
                    listOfNotNull(
                        a.year.takeIf { it > 0 }?.toString(), a.genre, pluralTracks(songs.size), formatTotal(songs.sumOf { it.duration }),
                        c.sources.sources.value.takeIf { it.size > 1 }?.let { c.sources.source(a.sourceId)?.name },
                    ).joinToString(" • "),
                    onPlay = { c.player.play(songs) }, onShuffle = { c.player.play(songs, shuffle = true) },
                    secondary = listOf(
                        HeaderButton(if (a.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.detail_favorite), if (a.starred) LocalExtraColors.current.favorite else null) { c.library.toggleAlbumStar(a) },
                        pinButton(PinKind.ALBUM, a.id),
                        HeaderButton(Icons.Default.AutoAwesome, stringResource(R.string.detail_mix)) { ui.instantMix(MixSeed.Album(a.id)) },
                    ),
                    onSubtitleClick = a.artistId?.let { aid -> { nav.navigate(Route.Artist(aid)) } },
                )
            }
            if (songs.any { c.audiobooks.isAudiobook(it) }) item { ContinueBookCard(a.id, songs) }
            var lastDisc = -1
            songs.forEachIndexed { i, s ->
                if (multiDisc && s.disc != lastDisc) {
                    lastDisc = s.disc
                    item(key = "disc-${s.disc}") { Text(stringResource(R.string.detail_disc_n, s.disc), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)) }
                }
                item(key = s.id) {
                    SongRow(
                        s, index = s.track.takeIf { it > 0 } ?: (i + 1), showArt = false,
                        subtitle = if (s.artist != a.artist) s.artist else (s.suffix?.uppercase() ?: "") + (if (s.bitRate > 0) " ${s.bitRate}k" else ""),
                        onMenu = { ui.songMenu(s) },
                    ) { c.player.play(songs, i) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistScreen(id: String) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val artist by c.library.artist(id).collectAsState(initial = null)
    val albums by c.library.artistAlbums(id).collectAsState(initial = emptyList())
    val top by c.library.artistTopSongs(id).collectAsState(initial = emptyList())
    val info by produceState<dev.slurpdrome.data.api.ArtistInfo?>(null, id) { value = c.library.artistInfo(id) }
    val similar by produceState(emptyList<dev.slurpdrome.data.db.ArtistEntity>(), info) {
        value = info?.similarArtist?.map { it.id }?.let { c.db.artists().byIds(it) }.orEmpty()
    }
    var bioExpanded by remember { mutableStateOf(false) }
    val a = artist
    Column(Modifier.fillMaxSize()) {
        ScreenBar(a?.name ?: "") { if (a != null) FIconButton(Icons.Default.MoreVert, stringResource(R.string.common_actions)) { ui.artistMenu(a) } }
        if (a == null) return@Column
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item {
                DetailHeader(
                    a.coverArt, a.name, null, stringResource(R.string.artist_album_count, albums.size),
                    onPlay = { scope.launch { c.player.play(c.library.artistSongsOnce(id)) } },
                    onShuffle = { scope.launch { c.player.play(c.library.artistSongsOnce(id), shuffle = true) } },
                    secondary = listOf(
                        HeaderButton(if (a.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.detail_favorite), if (a.starred) LocalExtraColors.current.favorite else null) { c.library.toggleArtistStar(a) },
                        HeaderButton(Icons.Default.Radio, stringResource(R.string.detail_radio)) { ui.instantMix(MixSeed.Artist(id), radio = true) },
                        pinButton(PinKind.ARTIST, id),
                    ),
                    circle = true,
                )
            }
            info?.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                item {
                    val text = remember(bio) { HtmlCompat.fromHtml(bio, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }
                    Text(
                        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (bioExpanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp).animateContentSize().dpadClickable { bioExpanded = !bioExpanded }.padding(8.dp),
                    )
                }
            }
            if (top.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.detail_top_tracks)) }
                itemsIndexed(top.take(5), key = { _, s -> "top-" + s.id }) { i, s -> SongRow(s, onMenu = { ui.songMenu(s) }) { c.player.play(top, i) } }
            }
            item { SectionHeader(stringResource(R.string.detail_albums)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    items(albums, key = { it.id }) { al -> AlbumCard(al, width = 148.dp, onMenu = { ui.albumMenu(al) }) { nav.navigate(Route.Album(al.id)) } }
                }
            }
            if (similar.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.detail_similar_artists)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(similar, key = { it.id }) { s -> ArtistCircle(s) { nav.navigate(Route.Artist(s.id)) } }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(id: String) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val playlist by c.playlists.byId(id).collectAsState(initial = null)
    val songs by c.playlists.songs(id).collectAsState(initial = emptyList())
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val p = playlist
    fun report(r: Result<Unit>, ok: String? = null) { r.onSuccess { ok?.let(ui::toast) }.onFailure { ui.toast(ctx.getString(R.string.detail_no_server_connection)) } }

    Column(Modifier.fillMaxSize()) {
        ScreenBar(p?.name ?: "") {
            Box {
                FIconButton(Icons.Default.MoreVert, stringResource(R.string.common_actions)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.detail_rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.detail_copy_to_device)) }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = {
                        menu = false
                        val name = p?.name ?: return@DropdownMenuItem
                        scope.launch { nav.navigate(Route.LocalPlaylist(c.localPlaylists.importServerPlaylist(id, name))); ui.toast(ctx.getString(R.string.detail_copy_created)) }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.detail_delete_playlist)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        if (p == null) return@Column
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item {
                DetailHeader(
                    p.coverArt, p.name, p.owner?.let { stringResource(R.string.detail_by_owner, it) },
                    "${pluralTracks(songs.size)} • ${formatTotal(songs.sumOf { it.duration })}" + (p.comment?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                    onPlay = { c.player.play(songs, context = dev.slurpdrome.player.PlayContext.Playlist) }, onShuffle = { c.player.play(songs, shuffle = true, context = dev.slurpdrome.player.PlayContext.Playlist) },
                    secondary = listOf(pinButton(PinKind.PLAYLIST, id)),
                )
            }
            if (songs.isEmpty()) item { EmptyState(stringResource(R.string.detail_playlist_empty)) }
            itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, s ->
                SongRow(s, onMenu = {
                    ui.songMenu(s, listOf(
                        ExtraAction(ctx.getString(R.string.detail_remove_from_playlist), Icons.Default.RemoveCircleOutline) { scope.launch { report(c.playlists.removeAt(id, listOf(i)), ctx.getString(R.string.detail_removed)) } },
                        ExtraAction(ctx.getString(R.string.detail_move_up), Icons.Default.ArrowUpward) { scope.launch { report(c.playlists.move(id, i, i - 1)) } },
                        ExtraAction(ctx.getString(R.string.detail_move_down), Icons.Default.ArrowDownward) { scope.launch { report(c.playlists.move(id, i, i + 1)) } },
                    ))
                }) { c.player.play(songs, i, context = dev.slurpdrome.player.PlayContext.Playlist) }
            }
        }
    }
    if (rename && p != null) {
        var name by remember { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(stringResource(R.string.detail_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.dpadEscape()) },
            confirmButton = { TextButton(onClick = { rename = false; scope.launch { report(c.playlists.rename(id, name.trim())) } }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (confirmDelete && p != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.detail_delete_q, p.name)) },
        text = { Text(stringResource(R.string.detail_delete_playlist_msg)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { c.playlists.delete(id).onSuccess { nav.popBackStack() }.onFailure { ui.toast(ctx.getString(R.string.detail_no_server_connection)) } } }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun SmartPlaylistScreen(id: Long) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val entity by c.smartPlaylists.byId(id).collectAsState(initial = null)
    val songs by remember(id) { c.smartPlaylists.observeSongs(id) }.collectAsState(initial = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    val e = entity
    Column(Modifier.fillMaxSize()) {
        ScreenBar(e?.name ?: "") {
            FIconButton(Icons.Default.Edit, stringResource(R.string.detail_edit_rules)) { nav.navigate(Route.SmartEditor(id)) }
            FIconButton(Icons.Default.Delete, stringResource(R.string.common_delete)) { confirmDelete = true }
        }
        if (e == null) return@Column
        val spec = remember(e) { c.smartPlaylists.decode(e) }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item {
                DetailHeader(
                    songs.firstOrNull()?.coverArt, e.name, stringResource(R.string.detail_smart_playlist),
                    "${pluralTracks(songs.size)} • ${pluralStringResource(R.plurals.detail_rules_count, spec.rules.size, spec.rules.size)} • ${stringResource(R.string.detail_auto_updated)}",
                    onPlay = { c.player.play(songs, context = dev.slurpdrome.player.PlayContext.Playlist) }, onShuffle = { c.player.play(songs, shuffle = true, context = dev.slurpdrome.player.PlayContext.Playlist) },
                    secondary = listOf(pinButton(PinKind.SMART, id.toString())),
                )
            }
            if (songs.isEmpty()) item { EmptyState(stringResource(R.string.detail_smart_no_match)) }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, onMenu = { ui.songMenu(s) }) { c.player.play(songs, i, context = dev.slurpdrome.player.PlayContext.Playlist) } }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.detail_delete_smart_q)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                c.scope.launch { c.downloads.setPinned(PinKind.SMART, id.toString(), false); c.smartPlaylists.delete(id) }
                nav.popBackStack()
            }) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartEditorScreen(id: Long, template: Int) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var matchAll by remember { mutableStateOf(true) }
    val rules = remember { mutableStateListOf<SmartRule>() }
    var sort by remember { mutableStateOf("RANDOM") }
    var limit by remember { mutableStateOf("100") }

    LaunchedEffect(id, template) {
        val existing = if (id > 0) c.db.smartPlaylists().byId(id) else null
        val (n, spec) = when {
            existing != null -> existing.name to c.smartPlaylists.decode(existing)
            template in SmartPlaylistRepository.templates.indices -> SmartPlaylistRepository.templates[template].let { (res, sp) -> ctx.getString(res) to sp }
            else -> ctx.getString(R.string.detail_new_smart_playlist) to SmartSpec(rules = listOf(SmartRule()))
        }
        name = n; matchAll = spec.matchAll; rules.clear(); rules.addAll(spec.rules); sort = spec.sort; limit = spec.limit.toString()
        loaded = true
    }
    val spec = SmartSpec(matchAll, rules.toList(), sort, limit.toIntOrNull() ?: 0)
    val preview by remember(spec) { c.smartPlaylists.observe(spec) }.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        ScreenBar(stringResource(if (id > 0) R.string.detail_rules else R.string.detail_smart_playlist)) {
            TextButton(enabled = loaded && name.isNotBlank(), onClick = {
                scope.launch {
                    val newId = c.smartPlaylists.save(id.takeIf { it > 0 }, name.trim(), spec)
                    if (c.downloads.isPinned(PinKind.SMART, newId.toString())) c.downloads.reconcile()
                    nav.popBackStack()
                    if (id <= 0) nav.navigate(Route.SmartPlaylist(newId))
                }
            }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.common_save)) }
        }
        if (!loaded) return@Column
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + dev.slurpdrome.ui.LocalBottomInset.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.detail_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.detail_match))
                    FilterChip(matchAll, { matchAll = true }, label = { Text(stringResource(R.string.detail_match_all)) }, modifier = Modifier.focusRing())
                    FilterChip(!matchAll, { matchAll = false }, label = { Text(stringResource(R.string.detail_match_any)) }, modifier = Modifier.focusRing())
                }
            }
            itemsIndexed(rules) { i, r ->
                RuleEditor(r, onChange = { rules[i] = it }, onRemove = { rules.removeAt(i) })
            }
            item {
                OutlinedButton(onClick = { rules.add(SmartRule()) }, modifier = Modifier.focusRing()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.detail_add_rule))
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Choice(stringResource(R.string.detail_sort), sort, listOf("RANDOM") + SongSort.entries.map { it.name }, { stringResource(if (it == "RANDOM") R.string.detail_sort_random else SongSort.valueOf(it).labelRes) }) { sort = it }
                    OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.detail_limit)) }, singleLine = true, modifier = Modifier.width(150.dp))
                }
            }
            item { HorizontalDivider() }
            item { Text(stringResource(R.string.detail_preview, pluralTracks(preview.size)), style = MaterialTheme.typography.titleSmall) }
            items(preview.take(20), key = { it.id }) { s ->
                Text("${s.artist} — ${s.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, value: T, options: List<T>, text: @Composable (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.focusRing()) {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text(value))
            }
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(rule: SmartRule, onChange: (SmartRule) -> Unit, onRemove: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.detail_field), rule.field, SmartField.entries, { stringResource(it.labelRes) }) { f ->
                val ops = SmartOp.forType(f.type)
                onChange(rule.copy(field = f, op = if (rule.op in ops) rule.op else ops.first()))
            }
            Choice(stringResource(R.string.detail_condition), rule.op, SmartOp.forType(rule.field.type), { stringResource(it.labelRes) }) { onChange(rule.copy(op = it)) }
            val needsValue = rule.field.type != FieldType.BOOL && rule.op != SmartOp.NEVER
            if (needsValue) OutlinedTextField(
                rule.value, { onChange(rule.copy(value = it)) },
                label = { Text(stringResource(if (rule.op == SmartOp.BETWEEN) R.string.smart_op_between else if (rule.field.type == FieldType.DAYS) R.string.detail_days else R.string.detail_value)) },
                singleLine = true, modifier = Modifier.width(160.dp),
            )
            FIconButton(Icons.Default.Delete, stringResource(R.string.detail_remove_rule), onClick = onRemove)
        }
    }
}

@Composable
fun GenreScreen(name: String) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val songs by c.library.genreSongs(name).collectAsState(initial = emptyList())
    val albums by c.library.genreAlbums(name).collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        ScreenBar(name)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.player.play(songs, shuffle = true) }, modifier = Modifier.focusRing()) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.detail_shuffle)) }
                    FilledTonalButton(onClick = { ui.instantMix(MixSeed.Genre(name), radio = true) }, modifier = Modifier.focusRing()) { Icon(Icons.Default.Radio, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.detail_genre_radio)) }
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionHeader("${stringResource(R.string.detail_albums)} · ${albums.size}") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(albums, key = { it.id }) { a -> AlbumCard(a, width = 140.dp, onMenu = { ui.albumMenu(a) }) { nav.navigate(Route.Album(a.id)) } }
                    }
                }
            }
            item { SectionHeader("${stringResource(R.string.detail_tracks)} · ${songs.size}") }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, onMenu = { ui.songMenu(s) }) { c.player.play(songs, i) } }
        }
    }
}


/** "Continue chapter N at mm:ss" for audiobooks; resume points also sync via Navidrome bookmarks. */
@Composable
private fun ContinueBookCard(albumId: String, songs: List<SongEntity>) {
    val c = LocalContainer.current
    val resume by remember(albumId) { c.audiobooks.resume(albumId) }.collectAsState(initial = null)
    val r = resume
    val idx = r?.let { p -> songs.indexOfFirst { it.id == p.songId } } ?: -1
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (r?.finished == true) stringResource(R.string.detail_book_finished) else if (idx >= 0) stringResource(R.string.detail_chapter_n_of, idx + 1, songs.size) else stringResource(R.string.detail_audiobook), style = MaterialTheme.typography.titleSmall)
                if (idx >= 0 && r != null && !r.finished) {
                    val total = songs.take(idx).sumOf { it.duration } * 1000L + r.positionMs
                    val all = songs.sumOf { it.duration } * 1000L
                    Text("${songs[idx].title} · ${dev.slurpdrome.ui.components.formatMs(r.positionMs)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (all > 0) (total.toFloat() / all).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (idx >= 0 && r != null && !r.finished) {
                Button(onClick = { c.player.play(songs, idx, startPositionMs = r.positionMs) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.detail_continue)) }
            } else {
                Button(onClick = { c.player.play(songs) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.detail_play)) }
            }
        }
    }
}
