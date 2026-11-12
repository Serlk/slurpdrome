package dev.slurpdrome.ui.screens

import android.content.Context
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import dev.slurpdrome.ui.components.eqProfileName
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.slurpdrome.data.prefs.HomeSection
import dev.slurpdrome.data.prefs.KeypadMode
import dev.slurpdrome.data.prefs.ReplayGainMode
import dev.slurpdrome.data.prefs.Settings
import dev.slurpdrome.data.prefs.ThemeMode
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.ScreenBar
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.focusRing
import dev.slurpdrome.ui.theme.AppThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val bitrates = listOf(0, 96, 128, 192, 256, 320)
private fun bitrateLabel(ctx: Context, b: Int) = if (b == 0) ctx.getString(R.string.settings_bitrate_original) else ctx.getString(R.string.settings_bitrate_kbps, b)
private val formats = listOf<Pair<String, String?>>("raw" to null, "opus" to "Opus", "mp3" to "MP3", "aac" to "AAC")
private fun formatLabel(ctx: Context, k: String): String = formats.first { it.first == k }.second ?: ctx.getString(R.string.settings_format_raw)

@Composable
fun SettingsScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val s by c.settingsState.collectAsState()
    val sourceList by c.sources.sources.collectAsState()
    val nav = dev.slurpdrome.ui.LocalNav.current
    val lastSync by c.settings.lastSync.collectAsState(initial = 0L)
    val sync by c.sync.state.collectAsState()
    val pending by c.library.pendingCount().collectAsState(initial = 0)
    val events by c.db.playEvents().observeCount().collectAsState(initial = 0)
    var editHome by remember { mutableStateOf(false) }
    val storage by produceState(0L to 0L, sync.running) {
        value = withContext(Dispatchers.IO) { c.downloads.usedBytes() to runCatching { c.streamCache.cacheSpace }.getOrDefault(0L) }
    }
    fun set(t: (Settings) -> Settings) { c.scope.launch { c.settings.update(t) } }
    val ctx = LocalContext.current
    val bitrateLabel: (Int) -> String = { bitrateLabel(ctx, it) }

    Column(Modifier.fillMaxSize()) {
        ScreenBar(stringResource(R.string.settings_title))
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item { Group(stringResource(R.string.settings_group_sources)) }
            item {
                Action(stringResource(R.string.settings_servers_folders), sourceList.joinToString { if (!it.enabled) ctx.getString(R.string.settings_source_disabled, it.name) else it.name }.ifBlank { stringResource(R.string.settings_no_sources) }) {
                    nav.navigate(dev.slurpdrome.ui.nav.Route.Sources)
                }
            }
            item {
                Action(
                    stringResource(R.string.settings_sync_now),
                    when {
                        sync.running -> stringResource(R.string.settings_sync_running, sync.stage)
                        sync.error != null -> stringResource(R.string.settings_sync_error, sync.error!!)
                        lastSync > 0 -> stringResource(R.string.settings_sync_last, DateUtils.getRelativeTimeSpanString(lastSync).toString())
                        else -> stringResource(R.string.settings_sync_never)
                    },
                ) { c.sync.request() }
            }
            item {
                Choice(stringResource(R.string.settings_auto_sync), s.autoSyncHours, listOf(0, 1, 6, 12, 24, 72), { if (it == 0) ctx.getString(R.string.settings_manual_only) else ctx.getString(R.string.settings_every_n_hours, it) }) { v -> set { it.copy(autoSyncHours = v) } }
            }
            if (pending > 0) item { Info(stringResource(R.string.settings_pending_title), pluralStringResource(R.plurals.settings_pending_actions, pending, pending)) }

            item { Group(stringResource(R.string.settings_group_appearance)) }
            item { Choice(stringResource(R.string.settings_theme), s.themeMode, ThemeMode.entries, { ctx.getString(when (it) { ThemeMode.SYSTEM -> R.string.settings_theme_system; ThemeMode.LIGHT -> R.string.settings_theme_light; ThemeMode.DARK -> R.string.settings_theme_dark }) }) { v -> set { it.copy(themeMode = v) } } }
            item { Choice(stringResource(R.string.settings_color_scheme), s.themeId, AppThemes.all.map { it.id }, { AppThemes.byId(it).name }) { v -> set { it.copy(themeId = v) } } }
            if (Build.VERSION.SDK_INT >= 31) item { Toggle("Material You", stringResource(R.string.settings_material_you_desc), s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } } }
            item { AccentPicker(s.accentSeed) { v -> set { it.copy(accentSeed = v, dynamicColor = if (v != 0L) false else it.dynamicColor) } } }
            item { Toggle(stringResource(R.string.settings_pure_black), stringResource(R.string.settings_pure_black_desc), s.pureBlack) { v -> set { it.copy(pureBlack = v) } } }
            item { Toggle(stringResource(R.string.settings_reduce_motion), stringResource(R.string.settings_reduce_motion_desc), s.reduceMotion) { v -> set { it.copy(reduceMotion = v) } } }
            item { Toggle(stringResource(R.string.settings_album_grid), null, s.albumGrid) { v -> set { it.copy(albumGrid = v) } } }
            item { Choice(stringResource(R.string.settings_grid_columns), s.gridColumns, listOf(0, 2, 3, 4, 5), { if (it == 0) ctx.getString(R.string.settings_auto) else "$it" }) { v -> set { it.copy(gridColumns = v) } } }
            item { Action(stringResource(R.string.settings_home_sections), s.homeSections.joinToString(", ") { ctx.getString(it.titleRes) }) { editHome = true } }

            item { Group(stringResource(R.string.settings_group_playback)) }
            item {
                val eqActive by c.eq.active.collectAsState()
                Action(stringResource(R.string.settings_equalizer), if (s.eqEnabled) stringResource(R.string.settings_eq_on, eqActive?.let { eqProfileName(it) } ?: "null") else stringResource(R.string.settings_off)) { nav.navigate(dev.slurpdrome.ui.nav.Route.Equalizer) }
            }
            item { Toggle(stringResource(R.string.settings_scrobble), stringResource(R.string.settings_scrobble_desc), s.scrobble) { v -> set { it.copy(scrobble = v) } } }
            item { Choice("ReplayGain", s.replayGain, ReplayGainMode.entries, { ctx.getString(when (it) { ReplayGainMode.OFF -> R.string.settings_off; ReplayGainMode.TRACK -> R.string.settings_rg_track; ReplayGainMode.ALBUM -> R.string.settings_rg_album }) }) { v -> set { it.copy(replayGain = v) } } }
            item { Toggle(stringResource(R.string.settings_skip_silence), null, s.skipSilence) { v -> set { it.copy(skipSilence = v) } } }
            item { Toggle(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_desc), s.autoplay) { v -> set { it.copy(autoplay = v) } } }
            item { Toggle(stringResource(R.string.settings_resume_queue), stringResource(R.string.settings_resume_queue_desc), s.resumeQueue) { v -> set { it.copy(resumeQueue = v) } } }
            item { Toggle(stringResource(R.string.settings_pause_disconnect), stringResource(R.string.settings_pause_disconnect_desc), s.pauseOnDisconnect) { v -> set { it.copy(pauseOnDisconnect = v) } } }
            item { Choice(stringResource(R.string.settings_seek_step), s.seekStepSec, listOf(5, 10, 15, 30, 60), { ctx.getString(R.string.settings_seconds, it) }) { v -> set { it.copy(seekStepSec = v) } } }

            item { Group(stringResource(R.string.settings_group_audiobooks)) }
            item { Toggle(stringResource(R.string.settings_long_tracks), stringResource(R.string.settings_long_tracks_desc), s.audiobookLongTracks) { v -> set { it.copy(audiobookLongTracks = v) } } }
            item { Choice(stringResource(R.string.settings_audiobook_speed), s.audiobookSpeed, listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f), { "×$it" }) { v -> set { it.copy(audiobookSpeed = v) } } }
            item { Choice(stringResource(R.string.settings_rewind), s.audiobookRewindSec, listOf(0, 5, 10, 15, 30, 60), { if (it == 0) ctx.getString(R.string.settings_none) else ctx.getString(R.string.settings_seconds, it) }) { v -> set { it.copy(audiobookRewindSec = v) } } }
            item {
                var editing by remember { mutableStateOf(false) }
                Action(stringResource(R.string.settings_audiobook_genres), s.audiobookGenres) { editing = true }
                if (editing) {
                    var text by remember { mutableStateOf(s.audiobookGenres) }
                    AlertDialog(
                        onDismissRequest = { editing = false },
                        title = { Text(stringResource(R.string.settings_audiobook_genres)) },
                        text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, supportingText = { Text(stringResource(R.string.settings_genres_hint)) }) },
                        confirmButton = { TextButton(onClick = { set { it.copy(audiobookGenres = text) }; editing = false }) { Text(stringResource(R.string.common_save)) } },
                        dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.common_cancel)) } },
                    )
                }
            }

            item { Group(stringResource(R.string.settings_group_streaming)) }
            item { Choice(stringResource(R.string.settings_quality_wifi), s.streamBitrateWifi, bitrates, bitrateLabel) { v -> set { it.copy(streamBitrateWifi = v) } } }
            item { Choice(stringResource(R.string.settings_quality_mobile), s.streamBitrateMobile, bitrates, bitrateLabel) { v -> set { it.copy(streamBitrateMobile = v) } } }
            item { Choice(stringResource(R.string.settings_transcode_format), s.streamFormat, formats.map { it.first }, { k -> formatLabel(ctx, k) }) { v -> set { it.copy(streamFormat = v) } } }
            item {
                CacheSizeSlider(s.streamCacheMb) { v ->
                    set { it.copy(streamCacheMb = v) }; ui.toast(ctx.getString(R.string.settings_cache_restart_toast))
                }
            }
            item {
                Action(stringResource(R.string.settings_clear_cache), stringResource(R.string.settings_used_mb, "%.0f".format(storage.second / 1_048_576.0))) {
                    c.scope.launch(Dispatchers.IO) { runCatching { c.streamCache.keys.toList().forEach { c.streamCache.removeResource(it) } } }
                    ui.toast(ctx.getString(R.string.settings_cache_cleared))
                }
            }

            item { Group(stringResource(R.string.settings_group_offline)) }
            item { Toggle(stringResource(R.string.settings_offline_mode), stringResource(R.string.settings_offline_mode_desc), s.offlineMode) { v -> set { it.copy(offlineMode = v) } } }
            item {
                Toggle(stringResource(R.string.settings_auto_fav), stringResource(R.string.settings_auto_fav_desc), s.autoDownloadFavorites) { v ->
                    set { it.copy(autoDownloadFavorites = v) }
                    if (v) ui.toast(ctx.getString(R.string.settings_auto_fav_toast))
                }
            }
            item {
                Choice(stringResource(R.string.settings_mobile_downloads), s.downloadNetwork, dev.slurpdrome.data.prefs.DownloadNetwork.entries, {
                    when (it) {
                        dev.slurpdrome.data.prefs.DownloadNetwork.WIFI_ONLY -> ctx.getString(R.string.settings_wifi_only)
                        dev.slurpdrome.data.prefs.DownloadNetwork.MOBILE_SMALL -> ctx.getString(R.string.settings_mobile_small, s.downloadMobileMaxMb)
                        dev.slurpdrome.data.prefs.DownloadNetwork.ANY -> ctx.getString(R.string.settings_always)
                    }
                }) { v -> set { it.copy(downloadNetwork = v) }; c.scope.launch { kotlinx.coroutines.delay(200); c.downloads.schedule() } }
            }
            if (s.downloadNetwork == dev.slurpdrome.data.prefs.DownloadNetwork.MOBILE_SMALL) item {
                Choice(stringResource(R.string.settings_mobile_threshold), s.downloadMobileMaxMb, listOf(10, 25, 50, 100), { ctx.getString(R.string.settings_mb_per_file, it) }) { v -> set { it.copy(downloadMobileMaxMb = v) } }
            }
            item { Toggle(stringResource(R.string.settings_download_originals), stringResource(R.string.settings_download_originals_desc), s.downloadOriginal) { v -> set { it.copy(downloadOriginal = v) } } }
            if (!s.downloadOriginal) {
                item { Choice(stringResource(R.string.settings_download_bitrate), s.downloadBitrate, bitrates.drop(1), bitrateLabel) { v -> set { it.copy(downloadBitrate = v) } } }
                item { Choice(stringResource(R.string.settings_download_format), s.downloadFormat, formats.drop(1).map { it.first }, { k -> formatLabel(ctx, k) }) { v -> set { it.copy(downloadFormat = v) } } }
            }
            item { Info(stringResource(R.string.settings_downloads_used), stringResource(R.string.unit_mb, "%.1f".format(storage.first / 1_048_576.0))) }

            item { Group(stringResource(R.string.settings_group_mixes)) }
            item {
                SliderSetting(stringResource(R.string.settings_mix_discovery), s.mixDiscovery, 0f..0.8f, { "${(it * 100).toInt()}%" }) { v -> set { it.copy(mixDiscovery = v) } }
            }
            item { Choice(stringResource(R.string.settings_exclude_recent), s.mixExcludeRecentHours, listOf(0, 12, 24, 48, 96, 168), { if (it == 0) ctx.getString(R.string.settings_none) else ctx.getString(R.string.settings_hours, it) }) { v -> set { it.copy(mixExcludeRecentHours = v) } } }
            item { Choice(stringResource(R.string.settings_mix_size), s.mixSize, listOf(25, 50, 100, 200), { ctx.resources.getQuantityString(R.plurals.tracks, it, it) }) { v -> set { it.copy(mixSize = v) }; c.mixes.invalidate() } }
            item { Info(stringResource(R.string.settings_history), pluralStringResource(R.plurals.settings_history_events, events, events)) }

            item { Group(stringResource(R.string.settings_group_controls)) }
            item {
                Choice(stringResource(R.string.settings_keypad_mode), s.keypadMode, KeypadMode.entries, {
                    ctx.getString(when (it) { KeypadMode.AUTO -> R.string.settings_keypad_auto; KeypadMode.ON -> R.string.settings_on; KeypadMode.OFF -> R.string.settings_off })
                }) { v -> set { it.copy(keypadMode = v) } }
            }
            item { Info(stringResource(R.string.settings_hotkeys), stringResource(R.string.settings_hotkeys_desc)) }

            item { Group(stringResource(R.string.settings_group_about)) }
            item { Info("Slurpdrome 0.1.0", stringResource(R.string.settings_about_desc)) }
        }
    }

    if (editHome) HomeSectionsDialog(s.homeSections, onDismiss = { editHome = false }) { v -> set { it.copy(homeSections = v) } }
}

@Composable
private fun Group(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun Info(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Action(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).dpadClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).dpadClickable { onChange(!value) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        // The row is the focus target; the switch only mirrors state.
        Switch(value, null)
    }
}

@Composable
private fun <T> Choice(title: String, value: T, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Action(title, label(value)) { open = true }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().dpadClickable { onPick(o); open = false }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(o == value, null)
                        Spacer(Modifier.width(8.dp))
                        Text(label(o))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SliderSetting(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(local), color = MaterialTheme.colorScheme.primary)
        }
        Slider(local, { local = it }, valueRange = range, onValueChangeFinished = { onChange(local) }, modifier = Modifier.focusRing(RoundedCornerShape(50)))
    }
}

@Composable
private fun HomeSectionsDialog(current: List<HomeSection>, onDismiss: () -> Unit, onSave: (List<HomeSection>) -> Unit) {
    // Visible sections in order, followed by hidden ones.
    var order by remember { mutableStateOf(current + HomeSection.entries.filter { it !in current }) }
    var visible by remember { mutableStateOf(current.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_home_sections)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                order.forEachIndexed { i, sec ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FIconButton(if (sec in visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, stringResource(R.string.settings_show), size = 40.dp) {
                            visible = if (sec in visible) visible - sec else visible + sec
                        }
                        Text(stringResource(sec.titleRes), Modifier.weight(1f), color = if (sec in visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        FIconButton(Icons.Default.ArrowUpward, stringResource(R.string.settings_move_up), size = 40.dp, enabled = i > 0) {
                            order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                        }
                        FIconButton(Icons.Default.ArrowDownward, stringResource(R.string.settings_move_down), size = 40.dp, enabled = i < order.lastIndex) {
                            order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(order.filter { it in visible }); onDismiss() }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Custom accent: swatches + a hue slider; "auto" returns to the theme's own colours. */
@Composable
private fun AccentPicker(seed: Long, onPick: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.bodyLarge)
        Text(if (seed == 0L) stringResource(R.string.settings_accent_theme) else stringResource(R.string.settings_accent_custom), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            val ring = MaterialTheme.colorScheme.onSurface
            androidx.compose.foundation.layout.Box(
                Modifier.padding(2.dp).size(36.dp)
                    .border(if (seed == 0L) 3.dp else 1.dp, ring, CircleShape)
                    .dpadClickable(CircleShape) { onPick(0L) },
                contentAlignment = Alignment.Center,
            ) { Text("A", style = MaterialTheme.typography.labelLarge) }
            dev.slurpdrome.ui.theme.AppThemes.accentSwatches.forEach { argb ->
                val selected = seed == argb
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(2.dp).size(36.dp)
                        .background(androidx.compose.ui.graphics.Color(argb), CircleShape)
                        .border(if (selected) 3.dp else 0.dp, ring, CircleShape)
                        .dpadClickable(CircleShape) { onPick(argb) }
                )
            }
        }
        var hue by remember(seed) {
            val hsl = FloatArray(3)
            androidx.core.graphics.ColorUtils.colorToHSL(if (seed == 0L) 0xFFFF6B35.toInt() else seed.toInt(), hsl)
            mutableFloatStateOf(hsl[0])
        }
        Slider(
            hue, { hue = it }, valueRange = 0f..359f,
            onValueChangeFinished = { onPick(androidx.compose.ui.graphics.Color.hsl(hue, 0.75f, 0.55f).toArgb().toLong() and 0xFFFFFFFFL) },
            modifier = Modifier.focusRing(RoundedCornerShape(50)),
        )
    }
}

/** Non-linear steps: fine at the small end, big jumps at the top (up to 100 GB). */
private val CACHE_STEPS_MB = listOf(256, 512, 1024, 2048, 3072, 4096, 6144, 8192, 10240, 12288, 16384, 20480, 25600, 32768, 40960, 51200, 65536, 81920, 102400)

@Composable
private fun CacheSizeSlider(currentMb: Int, onPick: (Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val startIndex = CACHE_STEPS_MB.indexOfFirst { it >= currentMb }.let { if (it < 0) CACHE_STEPS_MB.lastIndex else it }
    var index by remember(currentMb) { mutableFloatStateOf(startIndex.toFloat()) }
    val mb = CACHE_STEPS_MB[index.toInt().coerceIn(0, CACHE_STEPS_MB.lastIndex)]
    fun label(v: Int) = if (v >= 1024) ctx.getString(R.string.unit_gb, if (v % 1024 == 0) "${v / 1024}" else "%.1f".format(v / 1024f)) else ctx.getString(R.string.unit_mb, "$v")
    val freeGb = remember { ctx.cacheDir.usableSpace / 1_073_741_824L }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row {
            Text(stringResource(R.string.settings_stream_cache), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(mb), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = index, onValueChange = { index = it }, valueRange = 0f..CACHE_STEPS_MB.lastIndex.toFloat(),
            steps = CACHE_STEPS_MB.size - 2,
            onValueChangeFinished = { if (mb != currentMb) onPick(mb) },
            modifier = Modifier.focusRing(RoundedCornerShape(50)),
        )
        Text(stringResource(R.string.settings_cache_free, ctx.getString(R.string.unit_gb, "$freeGb")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
