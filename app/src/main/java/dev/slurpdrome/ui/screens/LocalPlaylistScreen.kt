package dev.slurpdrome.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.slurpdrome.data.repo.PinKind
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.components.dpadEscape
import dev.slurpdrome.ui.LocalNav
import dev.slurpdrome.ui.components.ActionItem
import dev.slurpdrome.ui.components.EmptyState
import dev.slurpdrome.ui.components.ExtraAction
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.ScreenBar
import dev.slurpdrome.ui.components.SongRow
import dev.slurpdrome.ui.components.formatTotal
import dev.slurpdrome.ui.components.pluralTracks
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R

@Composable
fun LocalPlaylistScreen(id: Long) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ui = LocalUi.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val playlist by c.localPlaylists.byId(id).collectAsState(initial = null)
    val songs by remember(id) { c.localPlaylists.songs(id) }.collectAsState(initial = emptyList())
    val sources by c.sources.sources.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    val p = playlist
    val multi = sources.size > 1

    Column(Modifier.fillMaxSize()) {
        ScreenBar(p?.name ?: "") {
            Box {
                FIconButton(Icons.Default.MoreVert, stringResource(R.string.common_actions)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.lp_rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; rename = true })
                    if (c.sources.servers.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.lp_export_to_server_ellipsis)) }, leadingIcon = { Icon(Icons.Default.CloudUpload, null) }, onClick = { menu = false; export = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        if (p == null) return@Column
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item {
                val origins = songs.map { it.sourceId }.distinct().mapNotNull { c.sources.source(it)?.name }
                DetailHeader(
                    songs.firstOrNull()?.coverArt, p.name, stringResource(R.string.lp_on_device),
                    "${pluralTracks(songs.size)} • ${formatTotal(songs.sumOf { it.duration })}" +
                        if (origins.size > 1) "\n" + stringResource(R.string.lp_sources, origins.joinToString()) else "",
                    onPlay = { c.player.play(songs, context = dev.slurpdrome.player.PlayContext.Playlist) }, onShuffle = { c.player.play(songs, shuffle = true, context = dev.slurpdrome.player.PlayContext.Playlist) },
                    secondary = listOf(pinButtonFor(PinKind.LOCAL_PLAYLIST, id.toString())),
                )
            }
            if (songs.isEmpty()) item { EmptyState(stringResource(R.string.lp_empty)) }
            itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, s ->
                SongRow(
                    s,
                    modifier = Modifier.animateItem(),
                    subtitle = if (multi) "${s.artist} • ${c.sources.source(s.sourceId)?.name ?: s.sourceId}" else "${s.artist} • ${s.album}",
                    onMenu = {
                        ui.songMenu(s, listOf(
                            ExtraAction(ctx.getString(R.string.lp_remove_from_playlist), Icons.Default.RemoveCircleOutline) { scope.launch { c.localPlaylists.removeAt(id, i) } },
                            ExtraAction(ctx.getString(R.string.lp_move_up), Icons.Default.ArrowUpward) { scope.launch { c.localPlaylists.move(id, i, i - 1) } },
                            ExtraAction(ctx.getString(R.string.lp_move_down), Icons.Default.ArrowDownward) { scope.launch { c.localPlaylists.move(id, i, i + 1) } },
                        ))
                    },
                ) { c.player.play(songs, i, context = dev.slurpdrome.player.PlayContext.Playlist) }
            }
        }
    }

    if (rename && p != null) {
        var name by remember { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(stringResource(R.string.lp_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.dpadEscape()) },
            confirmButton = { TextButton(onClick = { rename = false; scope.launch { c.localPlaylists.rename(id, name.trim()) } }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (confirmDelete && p != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.lp_delete_q, p.name)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                c.scope.launch { c.downloads.setPinned(PinKind.LOCAL_PLAYLIST, id.toString(), false); c.localPlaylists.delete(id) }
                nav.popBackStack()
            }) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (export) AlertDialog(
        onDismissRequest = { export = false },
        title = { Text(stringResource(R.string.lp_export_to_server)) },
        text = {
            Column {
                Text(stringResource(R.string.lp_export_hint))
                c.sources.servers.forEach { s ->
                    ActionItem(Icons.Default.CloudUpload, s.name) {
                        export = false
                        scope.launch {
                            runCatching { c.localPlaylists.exportToServer(id, s.id, c.playlists) }
                                .onSuccess { r -> ui.toast(ctx.getString(R.string.lp_export_added, s.name, r.added) + if (r.missing > 0) ctx.getString(R.string.lp_export_missing, r.missing) else "") }
                                .onFailure { ui.toast(ctx.getString(R.string.lp_failed, it.message)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { export = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
