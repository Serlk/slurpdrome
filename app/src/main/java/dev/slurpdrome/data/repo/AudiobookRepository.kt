package dev.slurpdrome.data.repo

import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.BookmarkEntity
import dev.slurpdrome.data.db.ResumePointEntity
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.prefs.Settings
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.source.SourceRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * Long-form listening: audiobooks, radio plays, podcasts-as-albums. Keeps a resume point per
 * book (album), mirrors it to Navidrome bookmarks for cross-device continuity, and stores
 * user bookmarks inside chapters.
 */
class AudiobookRepository(
    private val db: AppDatabase,
    private val sources: SourceRegistry,
    private val settings: StateFlow<Settings>,
) {
    fun isAudiobook(song: SongEntity?): Boolean {
        song ?: return false
        val s = settings.value
        val genres = s.audiobookGenres.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val g = song.genre?.lowercase()
        if (g != null && genres.any { it == g }) return true
        // "Long tracks" only counts when the whole album is long chapters (≥3 tracks, ≥20 min avg):
        // a single-file CUE image or a DJ mix is music, not a book.
        return s.audiobookLongTracks && song.duration >= 10 * 60 && (song.groupAlbumId ?: song.albumId) in longAlbums
    }

    @Volatile private var longAlbums: Set<String> = emptySet()

    suspend fun refreshHeuristics() {
        longAlbums = db.albums().longFormAlbumIds(minTracks = 3, minAvgSec = 20 * 60).toSet()
    }

    fun contextKey(song: SongEntity) = "album:${song.albumId ?: song.id}"

    fun resume(albumId: String) = db.audiobooks().observeResume("album:$albumId")
    fun inProgress(limit: Int = 12) = db.audiobooks().observeInProgress(limit)

    /** Drop resume points of things that aren't books (left over from earlier bookmark imports). */
    suspend fun pruneNonBooks() {
        refreshHeuristics()
        db.audiobooks().allResume().forEach { r ->
            val song = db.songs().byId(r.songId)
            if (song == null || !isAudiobook(song)) db.audiobooks().deleteResume(r.contextKey)
        }
    }

    suspend fun savePosition(song: SongEntity, positionMs: Long, finishedBook: Boolean = false) {
        db.audiobooks().upsertResume(
            ResumePointEntity(contextKey(song), song.id, positionMs, finishedBook, System.currentTimeMillis())
        )
    }

    /** Push to Navidrome (server bookmark per track) so other clients resume at the same spot. */
    suspend fun pushServerBookmark(song: SongEntity, positionMs: Long) {
        if (Gid.isLocal(song.id)) return
        if (Gid.isVirtual(song.id)) return
        runCatching { sources.apiFor(song.id)?.createBookmark(Gid.raw(song.id), positionMs) }
    }

    /** Pull server bookmarks; newer ones win over local resume points. */
    suspend fun pullServerBookmarks() {
        pruneNonBooks()
        for (s in sources.servers) {
            val api = sources.api(s.id) ?: continue
            val list = runCatching { api.getBookmarks() }.getOrNull() ?: continue
            for (b in list) {
                val entry = b.entry ?: continue
                val songId = Gid.of(s.id, entry.id)
                // Other clients bookmark ordinary music too; only books belong in "continue".
                val song = db.songs().byId(songId) ?: continue
                if (!isAudiobook(song)) continue
                val albumId = entry.albumId?.let { Gid.of(s.id, it) } ?: songId
                val key = "album:$albumId"
                val changed = parseIso(b.changed)
                val local = db.audiobooks().resume(key)
                if (local == null || local.updatedAt < changed) {
                    db.audiobooks().upsertResume(ResumePointEntity(key, songId, b.position, false, changed))
                }
            }
        }
    }

    fun bookmarks(songId: String) = db.audiobooks().observeBookmarks(songId)
    suspend fun addBookmark(songId: String, positionMs: Long, note: String) =
        db.audiobooks().addBookmark(BookmarkEntity(songId = songId, positionMs = positionMs, note = note))
    suspend fun deleteBookmark(id: Long) = db.audiobooks().deleteBookmark(id)
}
