package dev.slurpdrome.data.prefs

import android.content.Context
import androidx.annotation.StringRes
import dev.slurpdrome.R
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.slurpdrome.data.api.ServerCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class KeypadMode { AUTO, ON, OFF }
enum class ReplayGainMode { OFF, TRACK, ALBUM }

/** When offline downloads may use the network. */
enum class DownloadNetwork { WIFI_ONLY, MOBILE_SMALL, ANY }

/** Sections of the home screen; order + visibility is user-configurable. */
enum class HomeSection(@StringRes val titleRes: Int) {
    MIXES(R.string.home_section_mixes),
    CONTINUE(R.string.home_section_continue),
    RECENTLY_PLAYED(R.string.home_section_recently_played),
    RECENTLY_ADDED(R.string.home_section_recently_added),
    MOST_PLAYED(R.string.home_section_most_played),
    FAVORITES(R.string.home_section_favorites),
    RANDOM_ALBUMS(R.string.home_section_random_albums),
    DOWNLOADED(R.string.home_section_downloaded),
}

@Serializable
data class Settings(
    // appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeId: String = "slurp",
    val dynamicColor: Boolean = false,
    val pureBlack: Boolean = false,
    val accentSeed: Long = 0,                 // ARGB of a custom accent; 0 = theme default
    val reduceMotion: Boolean = false,
    val albumGrid: Boolean = true,
    val gridColumns: Int = 0,                 // 0 = adaptive

    // streaming
    val streamBitrateWifi: Int = 0,           // kbps, 0 = original
    val streamBitrateMobile: Int = 320,
    val streamFormat: String = "raw",         // raw | opus | mp3 | aac
    val streamCacheMb: Int = 1024,

    // offline
    val downloadOriginal: Boolean = true,
    val downloadBitrate: Int = 320,
    val downloadFormat: String = "opus",
    val downloadWifiOnly: Boolean = true,      // legacy, superseded by downloadNetwork
    val downloadNetwork: DownloadNetwork = DownloadNetwork.WIFI_ONLY,
    val downloadMobileMaxMb: Int = 25,         // MOBILE_SMALL: bigger files wait for Wi‑Fi
    val autoDownloadFavorites: Boolean = false,
    val offlineMode: Boolean = false,

    // playback
    val scrobble: Boolean = true,
    val skipSilence: Boolean = false,
    val playbackSpeed: Float = 1f,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val resumeQueue: Boolean = true,
    /** When the queue ends: mixes keep going, albums/searches continue as radio; own playlists stop. */
    val autoplay: Boolean = true,
    /** Crossfade between tracks, seconds; 0 = off (gapless). */
    val crossfadeSec: Int = 0,
    val pauseOnDisconnect: Boolean = true,

    // equalizer
    val eqEnabled: Boolean = false,
    val eqProfileId: String = "flat",
    val eqAutoSwitch: Boolean = true,
    val eqDeviceProfiles: Map<String, String> = emptyMap(),   // output device key → profile id

    // audiobooks
    val audiobookGenres: String = "audiobook, audiobooks, аудиокнига, аудиокниги, spoken word, радиоспектакль",
    val audiobookLongTracks: Boolean = true,  // tracks ≥ 20 min are treated as audiobook/podcast chapters
    val audiobookSpeed: Float = 1f,
    val audiobookRewindSec: Int = 15,         // rewind when resuming after a long pause

    // controls
    val keypadMode: KeypadMode = KeypadMode.AUTO,
    val seekStepSec: Int = 10,

    // mixes
    val mixDiscovery: Float = 0.3f,
    val mixExcludeRecentHours: Int = 48,
    val mixSize: Int = 50,

    val autoSyncHours: Int = 12,
    /** Merge the same album/track found on several sources into one entry. */
    val mergeDuplicates: Boolean = true,
    /** Source ids, most preferred first (which copy is shown/played); unknown ones go last. */
    val sourcePriority: List<String> = emptyList(),
    val homeSections: List<HomeSection> = HomeSection.entries.toList(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private object K {
        val json = stringPreferencesKey("settings_json")
        val lastSync = longPreferencesKey("lastSync")
        val queueIndex = intPreferencesKey("queueIndex")
        val queuePosition = longPreferencesKey("queuePosition")
        // legacy (v0.1) single-account credentials, migrated into a Source on first start
        val baseUrl = stringPreferencesKey("baseUrl")
        val username = stringPreferencesKey("username")
        val token = stringPreferencesKey("token")
        val salt = stringPreferencesKey("salt")
    }

    val settings: Flow<Settings> = context.dataStore.data.map(::read)

    private fun read(p: Preferences): Settings {
        p[K.json]?.let { raw -> runCatching { return json.decodeFromString(Settings.serializer(), raw) } }
        return legacy(p)
    }

    /** v0.1 stored one key per field; keep reading them until the first write migrates to JSON. */
    private fun legacy(p: Preferences): Settings {
        val d = Settings()
        fun b(n: String) = p[booleanPreferencesKey(n)]
        fun i(n: String) = p[intPreferencesKey(n)]
        fun f(n: String) = p[floatPreferencesKey(n)]
        fun s(n: String) = p[stringPreferencesKey(n)]
        return d.copy(
            themeMode = s("themeMode")?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            themeId = s("themeId") ?: d.themeId,
            dynamicColor = b("dynamicColor") ?: d.dynamicColor,
            pureBlack = b("pureBlack") ?: d.pureBlack,
            albumGrid = b("albumGrid") ?: d.albumGrid,
            gridColumns = i("gridColumns") ?: d.gridColumns,
            streamBitrateWifi = i("streamBitrateWifi") ?: d.streamBitrateWifi,
            streamBitrateMobile = i("streamBitrateMobile") ?: d.streamBitrateMobile,
            streamFormat = s("streamFormat") ?: d.streamFormat,
            streamCacheMb = i("streamCacheMb") ?: d.streamCacheMb,
            downloadOriginal = b("downloadOriginal") ?: d.downloadOriginal,
            downloadBitrate = i("downloadBitrate") ?: d.downloadBitrate,
            downloadFormat = s("downloadFormat") ?: d.downloadFormat,
            downloadWifiOnly = b("downloadWifiOnly") ?: d.downloadWifiOnly,
            autoDownloadFavorites = b("autoDownloadFavorites") ?: d.autoDownloadFavorites,
            offlineMode = b("offlineMode") ?: d.offlineMode,
            scrobble = b("scrobble") ?: d.scrobble,
            skipSilence = b("skipSilence") ?: d.skipSilence,
            playbackSpeed = f("playbackSpeed") ?: d.playbackSpeed,
            replayGain = s("replayGain")?.let { runCatching { ReplayGainMode.valueOf(it) }.getOrNull() } ?: d.replayGain,
            resumeQueue = b("resumeQueue") ?: d.resumeQueue,
            pauseOnDisconnect = b("pauseOnDisconnect") ?: d.pauseOnDisconnect,
            keypadMode = s("keypadMode")?.let { runCatching { KeypadMode.valueOf(it) }.getOrNull() } ?: d.keypadMode,
            seekStepSec = i("seekStepSec") ?: d.seekStepSec,
            mixDiscovery = f("mixDiscovery") ?: d.mixDiscovery,
            mixExcludeRecentHours = i("mixExcludeRecentHours") ?: d.mixExcludeRecentHours,
            mixSize = i("mixSize") ?: d.mixSize,
            autoSyncHours = i("autoSyncHours") ?: d.autoSyncHours,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p -> p[K.json] = json.encodeToString(Settings.serializer(), transform(read(p))) }
    }

    // ---- legacy credentials (migration only) ----
    suspend fun legacyCredentials(): ServerCredentials? {
        val p = context.dataStore.data.first()
        val url = p[K.baseUrl] ?: return null
        return ServerCredentials(url, p[K.username] ?: return null, p[K.token] ?: return null, p[K.salt] ?: return null)
    }

    suspend fun clearLegacyCredentials() {
        context.dataStore.edit { it.remove(K.baseUrl); it.remove(K.username); it.remove(K.token); it.remove(K.salt) }
    }

    val lastSync: Flow<Long> = context.dataStore.data.map { it[K.lastSync] ?: 0L }
    suspend fun setLastSync(t: Long) { context.dataStore.edit { it[K.lastSync] = t } }
    suspend fun resetLastSync() { context.dataStore.edit { it.remove(K.lastSync) } }

    suspend fun saveQueuePosition(index: Int, positionMs: Long) {
        context.dataStore.edit { it[K.queueIndex] = index; it[K.queuePosition] = positionMs }
    }

    suspend fun queuePosition(): Pair<Int, Long> {
        val p = context.dataStore.data.first()
        return (p[K.queueIndex] ?: 0) to (p[K.queuePosition] ?: 0L)
    }
}
