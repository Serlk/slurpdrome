package dev.slurpdrome.player.eq

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.slurpdrome.R
import dev.slurpdrome.data.api.await
import dev.slurpdrome.data.prefs.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.net.URLDecoder

private val Context.eqStore: DataStore<Preferences> by preferencesDataStore("equalizer")

/**
 * An output the user listens on; EQ profiles can be bound to it for automatic switching.
 * [label] is a device/product name; when blank, [labelRes] gives the localized generic name. */
data class OutputDevice(val key: String, val label: String, @StringRes val labelRes: Int = 0)

fun OutputDevice.displayLabel(ctx: Context): String =
    if (label.isBlank() && labelRes != 0) ctx.getString(labelRes) else label

@Composable
fun OutputDevice.displayLabel(): String =
    if (label.isBlank() && labelRes != 0) stringResource(labelRes) else label

data class AutoEqEntry(val name: String, val path: String, val source: String)

class EqRepository(
    private val context: Context,
    private val http: Call.Factory,
    settings: StateFlow<Settings>,
    scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("profiles")
    private val ser = ListSerializer(EqProfile.serializer())

    val custom: StateFlow<List<EqProfile>> = context.eqStore.data
        .map { p -> p[key]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val all: StateFlow<List<EqProfile>> = custom.map { EqPresets.builtins + it }
        .stateIn(scope, SharingStarted.Eagerly, EqPresets.builtins)

    /** Current audio output, reported by the playback service. */
    val device = MutableStateFlow(OutputDevice("speaker", "", R.string.output_speaker))

    /** Profile in effect right now: device binding (if auto-switch) → selected profile. */
    val active: StateFlow<EqProfile?> = combine(settings, all, device) { s, list, dev ->
        val id = (if (s.eqAutoSwitch) s.eqDeviceProfiles[dev.key] else null) ?: s.eqProfileId
        list.firstOrNull { it.id == id } ?: list.first()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun save(p: EqProfile) = edit { list -> list.filter { it.id != p.id } + p }
    suspend fun delete(id: String) = edit { list -> list.filter { it.id != id } }

    private suspend fun edit(t: (List<EqProfile>) -> List<EqProfile>) {
        context.eqStore.edit { p ->
            val old = p[key]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
            p[key] = json.encodeToString(ser, t(old))
        }
    }

    // ---------------- AutoEq ----------------

    private val indexFile = File(context.cacheDir, "autoeq-index.md")

    /** ~8800 headphone profiles from github.com/jaakkopasanen/AutoEq (index cached for a week). */
    suspend fun autoEqIndex(): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        val fresh = indexFile.exists() && System.currentTimeMillis() - indexFile.lastModified() < 7 * 86_400_000L
        if (!fresh) {
            http.newCall(Request.Builder().url("$RAW/results/INDEX.md").build()).await().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                indexFile.writeText(r.body!!.string())
            }
        }
        val line = Regex("""^- \[(.+?)]\(\./(.+?)\) by (.+)$""")
        indexFile.readLines().mapNotNull { l ->
            line.find(l)?.let { m -> AutoEqEntry(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        }
    }

    suspend fun importAutoEq(entry: AutoEqEntry): EqProfile = withContext(Dispatchers.IO) {
        val folderName = URLDecoder.decode(entry.path.substringAfterLast('/'), "UTF-8")
        val file = java.net.URLEncoder.encode("$folderName ParametricEQ.txt", "UTF-8").replace("+", "%20")
        val url = "$RAW/results/${entry.path}/$file"
        val text = http.newCall(Request.Builder().url(url).build()).await().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body!!.string()
        }
        val id = "autoeq:" + entry.path.hashCode().toUInt().toString(16)
        val profile = EqPresets.parseAutoEq(id, "${entry.name} · ${entry.source.substringBefore(" on ")}", text)
            ?: throw IllegalStateException(context.getString(R.string.eq_profile_not_recognized))
        save(profile)
        profile
    }

    companion object {
        private const val RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master"

        /** Stable identity of an output device (type + product name). */
        fun describe(d: AudioDeviceInfo): OutputDevice {
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: ""
            val (kind, label, res) = when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> Triple("bt", name.ifBlank { "Bluetooth" }, 0)
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> Triple("wired", "", R.string.output_wired)
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> Triple("usb", name, R.string.output_usb)
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Triple("speaker", "", R.string.output_speaker)
                else -> Triple("other${d.type}", name, R.string.output_other)
            }
            return OutputDevice(if (kind == "speaker" || kind == "wired") kind else "$kind:$name", label, res)
        }

        /** Most relevant active output: external devices win over the built-in speaker. */
        fun currentOutput(am: AudioManager): OutputDevice {
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val priority = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            )
            val best = priority.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } }
                ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            return best?.let(::describe) ?: OutputDevice("speaker", "", R.string.output_speaker)
        }
    }
}
