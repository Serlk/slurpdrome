package dev.slurpdrome.data.repo

import dev.slurpdrome.R
import android.content.Context
import androidx.annotation.StringRes
import android.util.Log
import dev.slurpdrome.data.api.SubsonicApi
import dev.slurpdrome.data.api.SubsonicException
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.prefs.SettingsStore
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.source.LocalScanner
import dev.slurpdrome.data.source.Source
import dev.slurpdrome.data.source.SourceKind
import dev.slurpdrome.data.source.SourceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncState(
    val running: Boolean = false,
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

/**
 * Mirrors every source (Navidrome servers + local files) into one Room library. Sources are
 * synced independently: a dead server keeps its last snapshot and doesn't block the others.
 */
class SyncManager(
    private val ctx: Context,
    private val db: AppDatabase,
    private val sources: SourceRegistry,
    private val local: LocalScanner,
    private val settings: SettingsStore,
    private val library: LibraryRepository,
    private val onSynced: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Ask for a sync that survives the app going to background (WorkManager-backed). */
    var requester: () -> Unit = {}
    fun request() {
        _state.value = SyncState(running = true, stage = ctx.getString(R.string.sync_stage_waiting_network))
        requester()
    }

    suspend fun syncIfStale() {
        val hours = settings.current().autoSyncHours
        val last = settings.lastSync.first()
        if (last == 0L || (hours > 0 && System.currentTimeMillis() - last > hours * 3_600_000L)) request()
    }

    /** Sync all enabled sources (or just [only]). Returns true when every source succeeded. */
    suspend fun sync(only: String? = null): Boolean {
        if (mutex.isLocked) return false
        return mutex.withLock {
            val errors = mutableListOf<String>()
            try {
                library.flushPending()   // push offline mutations before taking a fresh snapshot
                for (s in sources.enabled.filter { only == null || it.id == only }) {
                    try {
                        when (s.kind) {
                            SourceKind.SERVER -> syncServer(s)
                            SourceKind.LOCAL -> {
                                stage(s, R.string.sync_stage_scanning_device)
                                local.scan(s, System.currentTimeMillis())
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Sync", "source ${s.id} failed", e)
                        errors += "${s.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                db.playlists().deleteOrphanEntries()
                db.playEvents().prune(System.currentTimeMillis() - 365L * 86_400_000L)
                if (errors.isEmpty()) settings.setLastSync(System.currentTimeMillis())
                onSynced()
                _state.value = SyncState(error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
                errors.isEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = SyncState()
                throw e
            }
        }
    }

    private suspend fun syncServer(s: Source) {
        val api = sources.api(s.id) ?: return
        val sid = s.id
        val gen = System.currentTimeMillis()

        stage(s, R.string.sync_stage_artists)
        db.artists().upsert(api.getArtists().map { it.toEntity(gen, sid) })

        stage(s, R.string.sync_stage_albums)
        val albumIds = mutableListOf<String>()
        paged { offset, size ->
            val page = api.getAlbumList("alphabeticalByName", size, offset, retry = false)
            db.albums().upsert(page.map { it.toEntity(gen, sid) })
            albumIds += page.map { it.id }
            progress(offset + page.size)
            page.size
        }

        stage(s, R.string.sync_stage_tracks)
        var songsSeen = 0
        paged { offset, size ->
            val page = api.search("", artistCount = 0, albumCount = 0, songCount = size, songOffset = offset, retry = false).song
            db.songs().upsert(page.map { it.toEntity(gen, sid) })
            songsSeen = offset + page.size
            progress(songsSeen)
            page.size
        }
        if (songsSeen == 0 && albumIds.isNotEmpty()) {
            // Server without empty-query search support: walk albums one by one.
            albumIds.forEachIndexed { i, id ->
                api.getAlbum(id)?.song?.let { list -> db.songs().upsert(list.map { it.toEntity(gen, sid) }) }
                progress(i + 1, albumIds.size)
            }
        }

        stage(s, R.string.sync_stage_playlists)
        val playlists = api.getPlaylists()
        db.playlists().upsert(playlists.map { it.toEntity(gen, sid) })
        playlists.forEachIndexed { i, p ->
            val full = api.getPlaylist(p.id) ?: return@forEachIndexed
            db.songs().upsert(full.entry.map { it.toEntity(gen, sid) })
            db.playlists().replaceEntries(Gid.of(sid, p.id), full.entry.map { Gid.of(sid, it.id) })
            progress(i + 1, playlists.size)
        }

        db.songs().deleteStale(sid, gen)
        db.albums().deleteStale(sid, gen)
        db.artists().deleteStale(sid, gen)
        db.playlists().deleteStale(sid, gen)
    }

    /**
     * Adaptive paging: a page that times out is retried at half the size (down to [MIN_PAGE]);
     * after a few good pages the size grows back. Slow servers (Pi over VPN, mobile uplink)
     * stall on big responses, and one stall must not kill a 50k-track sync.
     */
    private suspend fun paged(fetch: suspend (offset: Int, size: Int) -> Int) {
        var offset = 0
        var size = PAGE
        var good = 0
        var failures = 0
        while (true) {
            val got = try {
                fetch(offset, size)
            } catch (e: java.io.IOException) {
                if (e is SubsonicException && e.code in 1..99) throw e
                if (++failures >= 8) throw e   // ~8 consecutive stalls: genuinely offline
                size = (size / 2).coerceAtLeast(MIN_PAGE)
                good = 0
                Log.d("Sync", "page stalled at $offset, retrying with size $size")
                kotlinx.coroutines.delay(500L * failures)
                continue
            }
            failures = 0
            offset += got
            if (got < size) return
            if (++good >= 3 && size < PAGE) { size = (size * 2).coerceAtMost(PAGE); good = 0 }
        }
    }

    /** Refresh one playlist after a local edit. */
    suspend fun refreshPlaylist(playlistGid: String) {
        val sid = Gid.source(playlistGid)
        val api: SubsonicApi = sources.api(sid) ?: return
        val raw = Gid.raw(playlistGid)
        val full = api.getPlaylist(raw) ?: return
        val gen = System.currentTimeMillis()
        val known = library.songsByIds(full.entry.map { Gid.of(sid, it.id) }).map { it.id }.toSet()
        db.songs().upsert(full.entry.map { it.toEntity(gen, sid) }.filter { it.id !in known })
        db.playlists().replaceEntries(playlistGid, full.entry.map { Gid.of(sid, it.id) })
        api.getPlaylists().firstOrNull { it.id == raw }?.let { db.playlists().upsert(listOf(it.toEntity(gen, sid))) }
    }

    /** Remove everything a source contributed (when the user deletes it). */
    suspend fun purge(sourceId: String) {
        db.songs().deleteSource(sourceId)
        db.albums().deleteSource(sourceId)
        db.artists().deleteSource(sourceId)
        db.playlists().deleteSource(sourceId)
        db.playlists().deleteOrphanEntries()
    }

    private fun stage(s: Source, @StringRes name: Int) {
        val prefix = if (sources.enabled.size > 1) "${s.name}: " else ""
        _state.value = SyncState(running = true, stage = prefix + ctx.getString(name))
    }

    private fun progress(done: Int, total: Int = 0) {
        _state.value = _state.value.copy(done = done, total = total)
    }

    companion object {
        private const val PAGE = 500
        private const val MIN_PAGE = 25
    }
}
