package dev.slurpdrome.data.source

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.slurpdrome.data.api.AdaptiveHttp
import dev.slurpdrome.data.api.ServerCredentials
import dev.slurpdrome.data.api.SubsonicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

enum class SourceKind { SERVER, LOCAL }

/** A place music comes from. All sources are active at once and merged into one library. */
@Serializable
data class Source(
    val id: String,
    val kind: SourceKind,
    val name: String,
    val baseUrl: String = "",
    val username: String = "",
    val token: String = "",
    val salt: String = "",
    val enabled: Boolean = true,
    /** LOCAL only: MediaStore relative paths to include; empty = all music on the device. */
    val folders: List<String> = emptyList(),
) {
    val isServer get() = kind == SourceKind.SERVER
    fun credentials(): ServerCredentials? =
        if (isServer) ServerCredentials(baseUrl, username, token, salt) else null

    companion object {
        const val LOCAL_ID = "local"
    }
}

/**
 * Global ids: every entity id is "<sourceId>~<rawId>", so two servers can never collide and
 * any id tells us which API to talk to. Raw ids go over the wire, global ids live everywhere else.
 */
object Gid {
    const val SEP = '~'
    fun of(sourceId: String, raw: String) = "$sourceId$SEP$raw"
    fun ofOrNull(sourceId: String, raw: String?) = raw?.takeIf { it.isNotBlank() }?.let { of(sourceId, it) }
    fun source(id: String) = id.substringBefore(SEP, "")
    fun raw(id: String) = id.substringAfter(SEP)
    fun isLocal(id: String) = source(id) == Source.LOCAL_ID

    private const val CUE = "#cue"
    /** Virtual CUE track ids look like "<parentGid>#cue<n>". */
    fun isVirtual(id: String) = id.contains(CUE)
    fun parent(id: String) = id.substringBefore(CUE)
    fun virtual(parent: String, n: Int) = "$parent$CUE$n"
    /** Id to send to the server: the real file behind a virtual track. */
    fun serverRaw(id: String) = raw(parent(id))
}

private val Context.sourceStore: DataStore<Preferences> by preferencesDataStore("sources")

class SourceStore(private val context: Context) {
    private val key = stringPreferencesKey("sources_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Source.serializer())

    val sources = context.sourceStore.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
    }

    suspend fun current(): List<Source> = sources.first()

    suspend fun upsert(s: Source) = edit { list -> list.filter { it.id != s.id } + s }
    suspend fun remove(id: String) = edit { list -> list.filter { it.id != id } }

    private suspend fun edit(transform: (List<Source>) -> List<Source>) {
        context.sourceStore.edit { p ->
            val old = p[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
            p[key] = json.encodeToString(serializer, transform(old))
        }
    }

    /** New short, stable id for a server (s1, s2, …). */
    suspend fun nextServerId(): String {
        val used = current().map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { "s$it" }.first { it !in used }
    }
}

/** One SubsonicApi per server, created lazily; routes calls by the source part of a global id. */
class SourceRegistry(
    private val store: SourceStore,
    private val http: AdaptiveHttp,
    scope: CoroutineScope,
) {
    val sources: StateFlow<List<Source>> = store.sources
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { store.current() })

    val enabled get() = sources.value.filter { it.enabled }
    val servers get() = enabled.filter { it.isServer }
    fun source(id: String) = sources.value.firstOrNull { it.id == id }

    private val apis = ConcurrentHashMap<String, SubsonicApi>()

    fun api(sourceId: String): SubsonicApi? {
        val s = source(sourceId) ?: return null
        if (!s.isServer) return null
        return apis.getOrPut(sourceId) {
            SubsonicApi(http, { source(sourceId)?.credentials() }, http::onStall)
        }
    }

    fun apiFor(globalId: String): SubsonicApi? = api(Gid.source(globalId))

    /** Image URL for UI (Coil): direct HTTP for servers, our artwork provider for local files. */
    fun artUrl(coverArt: String?, size: Int = 600): String? {
        if (coverArt.isNullOrBlank()) return null
        if (Gid.isLocal(coverArt)) return ArtworkProvider.uri(coverArt).toString()
        return apiFor(coverArt)?.coverArtUrl(Gid.raw(coverArt), size)
    }

    /** content:// artwork for MediaSession / Android Auto / widgets (they can't send our auth). */
    fun artContentUri(coverArt: String?): Uri? =
        coverArt?.takeIf { it.isNotBlank() }?.let { ArtworkProvider.uri(it) }

    fun forget(sourceId: String) { apis.remove(sourceId); downSince.remove(sourceId) }

    // ---- health: a server that just failed is avoided for a while when picking copies ----
    private val downSince = ConcurrentHashMap<String, Long>()
    fun markDown(sourceId: String) { if (sourceId != Source.LOCAL_ID) downSince[sourceId] = System.currentTimeMillis() }
    fun markUp(sourceId: String) { downSince.remove(sourceId) }
    fun isHealthy(sourceId: String): Boolean =
        source(sourceId)?.enabled != false && (downSince[sourceId]?.let { System.currentTimeMillis() - it > 90_000 } ?: true)
}
