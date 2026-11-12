package dev.slurpdrome.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import dev.slurpdrome.ui.components.eqProfileName
import dev.slurpdrome.player.eq.displayLabel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.slurpdrome.player.eq.AutoEqEntry
import dev.slurpdrome.player.eq.EqBand
import dev.slurpdrome.player.eq.EqPresets
import dev.slurpdrome.player.eq.EqProfile
import dev.slurpdrome.player.eq.FilterType
import dev.slurpdrome.ui.LocalContainer
import dev.slurpdrome.ui.components.FIconButton
import dev.slurpdrome.ui.components.LocalUi
import dev.slurpdrome.ui.components.ScreenBar
import dev.slurpdrome.ui.components.dpadClickable
import dev.slurpdrome.ui.components.dpadEscape
import dev.slurpdrome.ui.components.focusRing
import dev.slurpdrome.ui.theme.MonoStyle
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val settings by c.settingsState.collectAsState()
    val profiles by c.eq.all.collectAsState()
    val active by c.eq.active.collectAsState()
    val device by c.eq.device.collectAsState()
    var draft by remember(active?.id) { mutableStateOf(active) }
    var showAutoEq by remember { mutableStateOf(false) }
    fun set(t: (dev.slurpdrome.data.prefs.Settings) -> dev.slurpdrome.data.prefs.Settings) { c.scope.launch { c.settings.update(t) } }
    val ctx = LocalContext.current

    /** Edits to a built-in or AutoEq profile become a personal copy; personal ones save in place. */
    fun commit(p: EqProfile) {
        scope.launch {
            val target = if (p.origin == "manual") p else p.copy(id = "custom:" + System.currentTimeMillis(), name = ctx.getString(R.string.eqs_custom_name, eqProfileName(ctx, p)), origin = "manual")
            c.eq.save(target)
            if (target.id != p.id) set { it.copy(eqProfileId = target.id, eqDeviceProfiles = it.eqDeviceProfiles - device.key) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenBar(stringResource(R.string.settings_equalizer)) {
            Switch(settings.eqEnabled, { v -> set { it.copy(eqEnabled = v) } }, modifier = Modifier.padding(end = 12.dp).focusRing())
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp + dev.slurpdrome.ui.LocalBottomInset.current)) {
            item { ResponseCurve(draft, settings.eqEnabled) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.displayLabel(), style = MaterialTheme.typography.bodyLarge)
                        val bound = settings.eqDeviceProfiles[device.key]
                        Text(
                            if (bound != null) stringResource(R.string.eqs_bound) else stringResource(R.string.eqs_not_bound),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val bound = settings.eqDeviceProfiles[device.key] == active?.id
                    Switch(bound, { on ->
                        val id = active?.id ?: return@Switch
                        set { it.copy(eqDeviceProfiles = if (on) it.eqDeviceProfiles + (device.key to id) else it.eqDeviceProfiles - device.key, eqAutoSwitch = true) }
                    }, modifier = Modifier.focusRing())
                }
            }
            item {
                Text(stringResource(R.string.eqs_profiles), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profiles.forEach { p ->
                        FilterChip(
                            selected = p.id == active?.id,
                            onClick = {
                                set {
                                    // Choosing a profile while a device binding is active rebinds it.
                                    val map = if (it.eqDeviceProfiles.containsKey(device.key)) it.eqDeviceProfiles + (device.key to p.id) else it.eqDeviceProfiles
                                    it.copy(eqProfileId = p.id, eqDeviceProfiles = map, eqEnabled = true)
                                }
                            },
                            label = { Text(eqProfileName(p), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.focusRing(),
                        )
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAutoEq = true }, modifier = Modifier.focusRing()) {
                        Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.eqs_autoeq_find))
                    }
                    val a = active
                    if (a != null && a.origin != "builtin") FIconButton(Icons.Default.Delete, stringResource(R.string.eqs_delete_profile)) {
                        scope.launch { c.eq.delete(a.id); set { it.copy(eqProfileId = "flat") } }
                    }
                }
            }
            val d = draft
            if (d != null) {
                item {
                    BandSlider(stringResource(R.string.eqs_preamp), d.preampDb, -15f..6f) { v -> draft = d.copy(preampDb = v) }
                }
                if (d.origin == "autoeq") {
                    item {
                        Text(
                            pluralStringResource(R.plurals.eqs_autoeq_info, d.bands.size, d.bands.size),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                items(d.bands.size) { i ->
                    val b = d.bands[i]
                    val label = (if (b.freq >= 1000) stringResource(R.string.unit_khz, "%.1f".format(b.freq / 1000)) else stringResource(R.string.unit_hz, "${b.freq.toInt()}")) +
                        when (b.type) { FilterType.LOW_SHELF -> " LS"; FilterType.HIGH_SHELF -> " HS"; else -> "" }
                    BandSlider(label, b.gainDb, -12f..12f) { v ->
                        draft = d.copy(bands = d.bands.toMutableList().also { it[i] = b.copy(gainDb = v) })
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { commit(d); ui.toast(ctx.getString(R.string.eqs_saved)) }, enabled = d != active, modifier = Modifier.focusRing()) { Text(stringResource(R.string.common_save)) }
                        TextButton(onClick = { draft = active }, enabled = d != active, modifier = Modifier.focusRing()) { Text(stringResource(R.string.eqs_reset)) }
                        TextButton(onClick = { draft = d.copy(preampDb = 0f, bands = d.bands.map { it.copy(gainDb = 0f) }) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.eqs_zero)) }
                    }
                }
            }
        }
    }
    // Live preview: what you hear follows the sliders before saving; leaving restores the saved one.
    LaunchedEffect(draft, settings.eqEnabled) { c.applyEq(draft, settings.eqEnabled) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { c.applyEq(c.eq.active.value, c.settingsState.value.eqEnabled) }
    }
    if (showAutoEq) AutoEqDialog(onDismiss = { showAutoEq = false }) { p ->
        showAutoEq = false
        set { s -> s.copy(eqProfileId = p.id, eqEnabled = true, eqDeviceProfiles = s.eqDeviceProfiles + (device.key to p.id)) }
        ui.toast(ctx.getString(R.string.eqs_bound_toast, eqProfileName(ctx, p), device.displayLabel(ctx)))
    }
}

@Composable
private fun BandSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MonoStyle, modifier = Modifier.width(96.dp))
        Slider(
            value, { onChange((it * 2).toInt() / 2f) }, valueRange = range,
            modifier = Modifier.weight(1f).focusRing(RoundedCornerShape(50)),
        )
        Text("%+.1f".format(value), style = MonoStyle, modifier = Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Magnitude response of the whole chain (preamp + biquads), log frequency axis, ±15 dB. */
@Composable
private fun ResponseCurve(profile: EqProfile?, enabled: Boolean) {
    val line = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    Surface(Modifier.fillMaxWidth().padding(16.dp).height(160.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val fMin = 20.0; val fMax = 20000.0; val dbRange = 15f
            fun x(f: Double) = (log10(f / fMin) / log10(fMax / fMin)).toFloat() * size.width
            fun y(db: Float) = size.height / 2 - db / dbRange * size.height / 2
            listOf(100.0, 1000.0, 10000.0).forEach { f -> drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height)) }
            drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)))
            val p = profile ?: return@Canvas
            val path = Path()
            val steps = 120
            for (i in 0..steps) {
                val f = fMin * (fMax / fMin).pow(i.toDouble() / steps)
                val db = (p.preampDb + p.bands.sumOf { bandDb(it, f) }.toFloat()).coerceIn(-dbRange, dbRange)
                if (i == 0) path.moveTo(x(f), y(db)) else path.lineTo(x(f), y(db))
            }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
        }
    }
}

/** dB gain of one RBJ biquad at frequency [f] (fs = 48 kHz, display only). */
private fun bandDb(b: EqBand, f: Double): Double {
    if (b.gainDb == 0f) return 0.0
    val fs = 48000.0
    val a = 10.0.pow(b.gainDb / 40.0)
    val w0 = 2 * PI * b.freq / fs; val cw = cos(w0); val sw = sin(w0)
    val alpha = sw / (2 * b.q.coerceAtLeast(0.1f))
    val (b0, b1, b2, a0, a1, a2) = when (b.type) {
        FilterType.PEAK -> listOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
        FilterType.LOW_SHELF -> { val s = 2 * sqrt(a) * alpha
            listOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s), (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s) }
        FilterType.HIGH_SHELF -> { val s = 2 * sqrt(a) * alpha
            listOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s), (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s) }
    }
    val w = 2 * PI * f / fs
    fun mag(c0: Double, c1: Double, c2: Double): Double {
        val re = c0 + c1 * cos(w) + c2 * cos(2 * w); val im = -(c1 * sin(w) + c2 * sin(2 * w))
        return hypot(re, im)
    }
    return 20 * log10(mag(b0, b1, b2) / mag(a0, a1, a2))
}

private operator fun <T> List<T>.component6() = this[5]

@Composable
private fun AutoEqDialog(onDismiss: () -> Unit, onImported: (EqProfile) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var index by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { runCatching { index = c.eq.autoEqIndex() }.onFailure { error = ctx.getString(R.string.eqs_catalog_error, "${it.message}") } }
    val results = remember(query, index) {
        val q = query.trim().lowercase()
        if (q.length < 2) emptyList() else index.orEmpty().filter { q in it.name.lowercase() }.take(60)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AutoEQ") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.eqs_headphone_model)) }, singleLine = true, modifier = Modifier.fillMaxWidth().dpadEscape())
                Spacer(Modifier.height(8.dp))
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    index == null -> CircularProgressIndicator()
                    query.length < 2 -> Text(pluralStringResource(R.plurals.eqs_profiles_count, index!!.size, index!!.size), style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(results, key = { it.path }) { e ->
                            Row(
                                Modifier.fillMaxWidth().dpadClickable {
                                    if (busy != null) return@dpadClickable
                                    busy = e.path
                                    scope.launch {
                                        runCatching { c.eq.importAutoEq(e) }
                                            .onSuccess(onImported)
                                            .onFailure { error = ctx.getString(R.string.eqs_download_error, "${it.message}") }
                                        busy = null
                                    }
                                }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(e.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                if (busy == e.path) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
