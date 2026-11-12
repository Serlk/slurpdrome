package dev.slurpdrome.data.repo

import dev.slurpdrome.R
import android.content.Context
import dev.slurpdrome.data.api.SubsonicApi
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.source.SourceRegistry

/**
 * Navidrome playlists live on their server and can only hold that server's songs. Edits go
 * straight to the server (single source of truth), then the local mirror is refreshed.
 */
class PlaylistRepository(
    private val ctx: Context,
    private val db: AppDatabase,
    private val sources: SourceRegistry,
    private val sync: () -> SyncManager,
    private val downloads: () -> DownloadManager,
) {
    fun all() = db.playlists().observeAll()
    fun byId(id: String) = db.playlists().observeById(id)
    fun songs(id: String) = db.playlists().observeSongs(id)
    suspend fun songsOnce(id: String) = db.playlists().songs(id)

    private fun apiOf(gid: String): SubsonicApi =
        sources.apiFor(gid) ?: throw IllegalStateException(ctx.getString(R.string.err_playlists_server_only))

    /** Creates the playlist on the server that owns the songs (or [sourceId] when empty). */
    suspend fun create(name: String, songIds: List<String>, sourceId: String? = null): Result<String?> = runCatching {
        val sid = sourceId ?: songIds.firstOrNull()?.let(Gid::source) ?: sources.servers.firstOrNull()?.id
            ?: throw IllegalStateException(ctx.getString(R.string.err_no_servers))
        val api = sources.api(sid) ?: throw IllegalStateException(ctx.getString(R.string.err_playlists_server_only))
        val own = songIds.filter { Gid.source(it) == sid }.map(Gid::raw)
        val p = api.createPlaylist(name, own)
        val rawId = p?.id ?: api.getPlaylists().firstOrNull { it.name == name }?.id
        val gid = rawId?.let { Gid.of(sid, it) }
        gid?.let { sync().refreshPlaylist(it) }
        gid
    }

    /** Adds songs; ones from another source are skipped (Navidrome can't reference them). */
    suspend fun add(id: String, songIds: List<String>): Result<Unit> = edit(id) {
        val own = songIds.filter { Gid.source(it) == Gid.source(id) }.map(Gid::raw)
        if (own.isEmpty()) throw IllegalStateException(ctx.getString(R.string.err_playlist_other_source))
        apiOf(id).updatePlaylist(Gid.raw(id), add = own)
    }

    suspend fun removeAt(id: String, indexes: List<Int>): Result<Unit> = edit(id) { apiOf(id).updatePlaylist(Gid.raw(id), removeIndexes = indexes) }

    suspend fun rename(id: String, name: String): Result<Unit> = edit(id) { apiOf(id).updatePlaylist(Gid.raw(id), name = name) }

    suspend fun move(id: String, from: Int, to: Int): Result<Unit> = edit(id) {
        val ids = db.playlists().entryIds(id).toMutableList()
        if (from !in ids.indices || to !in ids.indices) return@edit
        ids.add(to, ids.removeAt(from))
        apiOf(id).replacePlaylist(Gid.raw(id), ids.map(Gid::raw))
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        apiOf(id).deletePlaylist(Gid.raw(id))
        db.playlists().delete(id)
        db.playlists().clearEntries(id)
        downloads().setPinned(PinKind.PLAYLIST, id, false)
    }

    private suspend fun edit(id: String, block: suspend () -> Unit): Result<Unit> = runCatching {
        block()
        sync().refreshPlaylist(id)
        if (downloads().isPinned(PinKind.PLAYLIST, id)) downloads().reconcile()
    }
}
