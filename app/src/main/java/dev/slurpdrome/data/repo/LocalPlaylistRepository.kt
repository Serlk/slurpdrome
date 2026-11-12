package dev.slurpdrome.data.repo

import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.LocalPlaylistEntity
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.source.SourceRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Playlists stored on the device. They can mix songs from every source; each entry remembers
 * the recording's mergeKey, so if a song disappears from one server a copy elsewhere is used.
 */
class LocalPlaylistRepository(
    private val db: AppDatabase,
    private val sources: SourceRegistry,
    private val library: LibraryRepository,
) {
    private val dao get() = db.localPlaylists()

    fun all() = dao.observeAll()
    fun counts() = dao.observeCounts()
    fun byId(id: Long) = dao.observeById(id)

    /** Entries resolved to songs (dangling ones fall back to a copy from another source). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun songs(id: Long): Flow<List<SongEntity>> =
        combine(dao.observeEntries(id), db.songs().observeCount()) { e, _ -> e }
            .flatMapLatest { entries -> flow { emit(resolve(entries.map { it.songId to it.mergeKey })) } }

    suspend fun songsOnce(id: Long): List<SongEntity> = resolve(dao.entries(id).map { it.songId to it.mergeKey })

    private suspend fun resolve(entries: List<Pair<String, String>>): List<SongEntity> {
        val direct = library.songsByIds(entries.map { it.first }).associateBy { it.id }
        return entries.mapNotNull { (id, key) ->
            direct[id] ?: key.takeIf { it.startsWith("so:") }?.let { k ->
                db.dedup().siblings(k).let { copies -> copies.firstOrNull { it.isPrimary } ?: copies.firstOrNull() }
            }
        }
    }

    private suspend fun entriesFor(songIds: List<String>): List<Pair<String, String>> {
        val songs = library.songsByIds(songIds).associateBy { it.id }
        return songIds.mapNotNull { id -> songs[id]?.let { it.id to it.mergeKey } }
    }

    suspend fun create(name: String, songIds: List<String> = emptyList()): Long {
        val id = dao.insert(LocalPlaylistEntity(name = name))
        if (songIds.isNotEmpty()) dao.replaceEntries(id, entriesFor(songIds))
        return id
    }

    suspend fun add(id: Long, songIds: List<String>) {
        val current = dao.entries(id).map { it.songId to it.mergeKey }
        dao.replaceEntries(id, current + entriesFor(songIds))
    }

    suspend fun removeAt(id: Long, index: Int) {
        val current = dao.entries(id).map { it.songId to it.mergeKey }.toMutableList()
        if (index in current.indices) { current.removeAt(index); dao.replaceEntries(id, current) }
    }

    suspend fun move(id: Long, from: Int, to: Int) {
        val current = dao.entries(id).map { it.songId to it.mergeKey }.toMutableList()
        if (from !in current.indices || to !in current.indices) return
        current.add(to, current.removeAt(from))
        dao.replaceEntries(id, current)
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name)
    suspend fun delete(id: Long) { dao.clearEntries(id); dao.delete(id) }

    /** Copy a Navidrome playlist into a device playlist (then it can be mixed with other sources). */
    suspend fun importServerPlaylist(playlistGid: String, name: String): Long =
        create(name, db.playlists().entryIds(playlistGid))

    data class ExportResult(val playlistGid: String?, val added: Int, val missing: Int)

    /**
     * Upload to a server: every song is mapped to *that server's* copy of the recording; songs the
     * server doesn't have are reported as missing.
     */
    suspend fun exportToServer(id: Long, sourceId: String, playlists: PlaylistRepository): ExportResult {
        val p = dao.byId(id) ?: return ExportResult(null, 0, 0)
        val songs = songsOnce(id)
        val mapped = songs.map { s -> library.siblings(s).firstOrNull { Gid.source(it.id) == sourceId }?.id }
        val ids = mapped.filterNotNull()
        val gid = playlists.create(p.name, ids, sourceId).getOrThrow()
        return ExportResult(gid, ids.size, mapped.count { it == null })
    }

    fun serversForExport() = sources.servers
}
