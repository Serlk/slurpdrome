package dev.slurpdrome.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.slurpdrome.data.api.ServerCredentials
import dev.slurpdrome.data.api.SubsonicApi
import dev.slurpdrome.data.api.SubsonicException
import dev.slurpdrome.data.source.LocalScanner
import dev.slurpdrome.data.source.Source
import dev.slurpdrome.data.source.SourceKind
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.ScreenBar
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.dpadEscape
import dev.slurpdrome.ui.components.focusRing
import kotlinx.coroutines.launch

/** First run: pick where the music comes from. More sources can be added later in Settings. */
@Composable
fun LoginScreen() {
    var local by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.GraphicEq, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Slurpdrome", style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.login_where_from), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!local, { local = false }, label = { Text(stringResource(R.string.login_navidrome_server)) }, leadingIcon = { Icon(Icons.Default.Dns, null) }, modifier = Modifier.focusRing())
                FilterChip(local, { local = true }, label = { Text(stringResource(R.string.login_phone_music)) }, leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) }, modifier = Modifier.focusRing())
            }
            Spacer(Modifier.height(16.dp))
            if (local) LocalSourceForm(null) {} else ServerForm(null) {}
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.login_sources_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ServerForm(existing: Source?, onDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var url by rememberSaveable { mutableStateOf(existing?.baseUrl ?: "") }
    var user by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    fun submit() {
        if (busy || url.isBlank() || user.isBlank() || (existing == null && pass.isEmpty())) return
        busy = true; error = null
        scope.launch {
            try {
                val creds = if (pass.isEmpty() && existing != null) existing.credentials()!!.copy(baseUrl = ServerCredentials.normalizeUrl(url))
                    else ServerCredentials.create(url, user, pass)
                SubsonicApi(c.http, { creds }).ping(creds)
                val id = existing?.id ?: c.sourceStore.nextServerId()
                c.saveSource(
                    Source(
                        id = id, kind = SourceKind.SERVER,
                        name = name.ifBlank { creds.baseUrl.substringAfter("://") },
                        baseUrl = creds.baseUrl, username = creds.username, token = creds.token, salt = creds.salt,
                        enabled = existing?.enabled ?: true,
                    )
                )
                onDone()
            } catch (e: SubsonicException) {
                error = when (e.code) {
                    40 -> ctx.getString(R.string.login_err_bad_credentials)
                    41 -> ctx.getString(R.string.login_err_no_token_auth)
                    else -> e.message
                }
            } catch (e: Exception) {
                error = ctx.getString(R.string.login_err_unreachable, e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    val fieldMod = Modifier.fillMaxWidth().widthIn(max = 480.dp).dpadEscape()
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            url, { url = it }, label = { Text(stringResource(R.string.login_server_url)) }, placeholder = { Text("https://music.example.com") },
            singleLine = true, modifier = fieldMod, keyboardActions = next,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            user, { user = it }, label = { Text(stringResource(R.string.login_user)) }, singleLine = true, modifier = fieldMod, keyboardActions = next,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pass, { pass = it }, singleLine = true, modifier = fieldMod,
            label = { Text(if (existing != null) stringResource(R.string.login_password_keep) else stringResource(R.string.login_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next), keyboardActions = next,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            name, { name = it }, label = { Text(stringResource(R.string.login_name_optional)) }, singleLine = true, modifier = fieldMod,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).height(52.dp).focusRing()) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (existing != null) stringResource(R.string.common_save) else stringResource(R.string.login_connect))
        }
    }
}

/** Grant audio permission, pick folders (none = everything), save the LOCAL source. */
@Composable
fun LocalSourceForm(existing: Source?, onDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(c.localScanner.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var folders by remember { mutableStateOf<List<LocalScanner.Folder>?>(null) }
    var selected by remember { mutableStateOf(existing?.folders?.toSet() ?: emptySet()) }

    val ctx = LocalContext.current
    LaunchedEffect(granted) { if (granted) folders = c.localScanner.folders() }

    Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!granted) {
            Text(stringResource(R.string.login_need_permission), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(c.localScanner.permission) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.login_grant)) }
            return@Column
        }
        val list = folders
        if (list == null) { CircularProgressIndicator(); return@Column }
        Text(
            if (selected.isEmpty()) list.sumOf { it.songs }.let { n -> pluralStringResource(R.plurals.login_all_music, n, n) } else stringResource(R.string.login_folders_selected, selected.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth()) {
            list.take(200).forEach { f ->
                val on = f.path in selected
                Row(
                    Modifier.fillMaxWidth().dpadClickable { selected = if (on) selected - f.path else selected + f.path }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, null)
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(f.path.trimEnd('/'), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.songs}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    c.saveSource(Source(Source.LOCAL_ID, SourceKind.LOCAL, ctx.getString(R.string.login_this_device), folders = selected.toList(), enabled = existing?.enabled ?: true))
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp).focusRing(),
        ) { Text(if (existing != null) stringResource(R.string.common_save) else stringResource(R.string.login_add_phone_music)) }
    }
}

/** Settings → Sources: all servers + local folders, merged into one library. */
@Composable
fun SourcesScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val sources by c.sources.sources.collectAsState()
    var editing by remember { mutableStateOf<Source?>(null) }
    var adding by remember { mutableStateOf<SourceKind?>(null) }
    var removing by remember { mutableStateOf<Source?>(null) }

    val settings by c.settingsState.collectAsState()
    // Effective priority: explicit order first, then local, then the rest as added.
    val ordered = remember(sources, settings.sourcePriority) {
        sources.sortedBy { s -> settings.sourcePriority.indexOf(s.id).let { if (it >= 0) it else if (!s.isServer) 50 else 100 + sources.indexOf(s) } }
    }
    val dupes by androidx.compose.runtime.produceState(0, sources, settings.mergeDuplicates) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            c.db.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM songs WHERE isPrimary = 0"), null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }
    }
    fun move(i: Int, delta: Int) {
        val ids = ordered.map { it.id }.toMutableList()
        val j = i + delta
        if (j !in ids.indices) return
        ids.add(j, ids.removeAt(i))
        c.scope.launch { c.settings.update { it.copy(sourcePriority = ids) } }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenBar(stringResource(R.string.login_sources_title))
        LazyColumn(Modifier.weight(1f)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        .dpadClickable { c.scope.launch { c.settings.update { it.copy(mergeDuplicates = !it.mergeDuplicates) } } }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.login_merge_dupes), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.mergeDuplicates) stringResource(R.string.login_merge_dupes_on) + (if (dupes > 0) " · " + pluralStringResource(R.plurals.login_hidden_copies, dupes, dupes) else "")
                            else stringResource(R.string.login_merge_dupes_off),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(settings.mergeDuplicates, null)
                }
            }
            if (ordered.size > 1) item {
                Text(
                    stringResource(R.string.login_priority_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            items(ordered, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().animateItem().padding(horizontal = 8.dp).dpadClickable { editing = s }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (s.isServer) Icons.Default.Dns else Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (s.isServer) "${s.username} @ ${s.baseUrl}" else if (s.folders.isEmpty()) stringResource(R.string.login_all_device_music) else s.folders.joinToString(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(s.enabled, { on -> scope.launch { c.saveSource(s.copy(enabled = on)) } }, modifier = Modifier.focusRing())
                    if (ordered.size > 1) {
                        val i = ordered.indexOf(s)
                        FIconButton(Icons.Default.KeyboardArrowUp, stringResource(R.string.settings_move_up), enabled = i > 0) { move(i, -1) }
                        FIconButton(Icons.Default.KeyboardArrowDown, stringResource(R.string.settings_move_down), enabled = i < ordered.lastIndex) { move(i, 1) }
                    }
                    FIconButton(Icons.Default.Edit, stringResource(R.string.login_edit)) { editing = s }
                    FIconButton(Icons.Default.Delete, stringResource(R.string.common_delete)) { removing = s }
                }
            }
        }
        // Stays above the mini player / navigation bar.
        Row(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = dev.slurpdrome.ui.LocalBottomInset.current), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = SourceKind.SERVER }, modifier = Modifier.weight(1f).focusRing()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.login_server))
            }
            if (sources.none { !it.isServer }) OutlinedButton(onClick = { adding = SourceKind.LOCAL }, modifier = Modifier.weight(1f).focusRing()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.login_phone_music))
            }
        }
    }

    val dialogKind = adding ?: editing?.kind
    if (dialogKind != null) AlertDialog(
        onDismissRequest = { adding = null; editing = null },
        title = { Text(if (editing != null) stringResource(R.string.login_edit_source) else if (dialogKind == SourceKind.SERVER) stringResource(R.string.login_new_server) else stringResource(R.string.login_phone_music)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val done = { adding = null; editing = null }
                if (dialogKind == SourceKind.SERVER) ServerForm(editing, done) else LocalSourceForm(editing, done)
            }
        },
        confirmButton = { TextButton(onClick = { adding = null; editing = null }) { Text(stringResource(R.string.common_cancel)) } },
    )
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.login_remove_title, s.name)) },
            text = { Text(stringResource(R.string.login_remove_text)) },
            confirmButton = { TextButton(onClick = { removing = null; c.scope.launch { c.removeSource(s.id) } }) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
