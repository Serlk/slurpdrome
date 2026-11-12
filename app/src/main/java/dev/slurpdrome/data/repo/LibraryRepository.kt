package dev.slurpdrome.data.repo

import android.util.Log
import androidx.annotation.StringRes
import dev.slurpdrome.R
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.slurpdrome.data.api.ApiSong
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.db.AlbumEntity
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.ArtistEntity
import dev.slurpdrome.data.db.PendingActionEntity
import dev.slurpdrome.data.db.PendingType
import dev.slurpdrome.data.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SongSort(@StringRes val labelRes: Int, val sql: String) {
    TITLE(R.string.sort_title, "title COLLATE NOCASE"),
    ARTIST(R.string.sort_artist, "artist COLLATE NOCASE, album COLLATE NOCASE, disc, track"),
    ALBUM(R.string.sort_album, "album COLLATE NOCASE, disc, track"),
    ADDED(R.string.sort_added, "created DESC"),
    YEAR(R.string.sort_year, "year DESC, album COLLATE NOCASE, disc, track"),
    PLAYS(R.string.sort_plays, "playCount DESC"),
    LAST_PLAYED(R.string.sort_last_played, "lastPlayed DESC"),
    RATING(R.string.sort_rating, "rating DESC, playCount DESC"),
    DURATION(R.string.sort_duration, "duration DESC"),
}

enum class AlbumSort(@StringRes val labelRes: Int, val sql: String) {
    NAME(R.string.sort_name, "name COLLATE NOCASE"),
    ARTIST(R.string.sort_artist, "artist COLLATE NOCASE, year"),
    YEAR(R.string.sort_year, "year DESC, name COLLATE NOCASE"),
    ADDED(R.string.sort_added, "created DESC"),
    PLAYS(R.string.sort_plays, "playCount DESC"),
    LAST_PLAYED(R.string.sort_last_played, "lastPlayed DESC"),
    RATING(R.string.sort_rating, "rating DESC"),
}

data class SearchResults(
    val artists: List<ArtistEntity> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val songs: List<SongEntity> = emptyList(),
)

class LibraryRepository(
    private val db: AppDatabase,
    private val sources: dev.slurpdrome.data.source.SourceRegistry,
    private val scope: CoroutineScope,
) {
    private val flushMutex = Mutex()
    private val _favoriteChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Emits whenever a song's favourite state changes — the auto-download rule listens to it. */
    val favoriteChanged: SharedFlow<Unit> = _favoriteChanged

    // ---------- observe ----------
    fun songs(sort: SongSort, offlineOnly: Boolean): Flow<List<SongEntity>> {
        // Offline = downloaded files + music that lives on the device anyway.
        val where = if (offlineOnly) "AND (id IN (SELECT songId FROM downloads WHERE state = 2) OR sourceId = 'local')" else ""
        return db.songs().observeRaw(SimpleSQLiteQuery("SELECT * FROM songs WHERE isPrimary = 1 $where ORDER BY ${sort.sql}"))
    }

    fun albums(sort: AlbumSort, offlineOnly: Boolean): Flow<List<AlbumEntity>> {
        val where = if (offlineOnly)
            "AND (sourceId = 'local' OR id IN (SELECT DISTINCT COALESCE(s.groupAlbumId, s.albumId) FROM songs s JOIN downloads d ON d.songId = s.id WHERE d.state = 2))"
        else ""
        return db.albums().observeRaw(SimpleSQLiteQuery("SELECT * FROM albums WHERE isPrimary = 1 $where ORDER BY ${sort.sql}"))
    }

    fun artists() = db.artists().observeAll()
    fun genres() = db.songs().observeGenres()
    fun artist(id: String) = db.artists().observeById(id)
    fun album(id: String) = db.albums().observeById(id)
    fun albumSongs(id: String) = db.songs().observeByAlbum(id)
    fun artistAlbums(id: String) = db.albums().observeByArtist(id)
    fun artistTopSongs(id: String) = db.songs().observeTopByArtist(id, 10)
    fun genreSongs(genre: String) = db.songs().observeByGenre(genre)
    fun genreAlbums(genre: String) = db.albums().observeByGenre(genre)
    fun starredSongs() = db.songs().observeStarred()
    fun starredAlbums() = db.albums().observeStarred()
    fun starredArtists() = db.artists().observeStarred()
    fun recentlyAddedAlbums(n: Int = 20) = db.albums().observeRecentlyAdded(n)
    fun recentlyPlayedAlbums(n: Int = 20) = db.albums().observeRecentlyPlayed(n)
    fun mostPlayedAlbums(n: Int = 20) = db.albums().observeMostPlayed(n)
    fun recentlyPlayedSongs(n: Int = 30) = db.songs().observeRecentlyPlayed(n)
    fun song(id: String) = db.songs().observeById(id)
    fun songCount() = db.songs().observeCount()

    suspend fun songsByIds(ids: List<String>): List<SongEntity> {
        if (ids.isEmpty()) return emptyList()
        val map = ids.chunked(900).flatMap { db.songs().byIds(it) }.associateBy { it.id }
        return ids.mapNotNull { map[it] }
    }

    /** Live rows for [ids], in the given order (duplicates kept) — reflects stars/ratings instantly. */
    fun observeSongsByIds(ids: List<String>): Flow<List<SongEntity>> {
        if (ids.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        val distinct = ids.distinct()
        // SQLite caps bound args (~999); huge queues fall back to a single snapshot.
        if (distinct.size > 900) return kotlinx.coroutines.flow.flow { emit(songsByIds(ids)) }
        return db.songs().observeByIds(distinct).map { rows ->
            val byId = rows.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }
    }

    suspend fun albumSongsOnce(id: String) = db.songs().byAlbum(id)
    suspend fun artistSongsOnce(id: String) = db.songs().byArtist(id)
    suspend fun randomAlbums(n: Int) = db.albums().random(n)

    suspend fun search(q: String): SearchResults {
        val query = q.trim()
        if (query.isEmpty()) return SearchResults()
        return SearchResults(
            artists = db.artists().search(query, 10),
            albums = db.albums().search(query, 20),
            songs = db.songs().search(query, 100),
        )
    }

    suspend fun artistInfo(id: String) = runCatching { sources.apiFor(id)?.getArtistInfo(Gid.serverRaw(id)) }.getOrNull()
        ?.let { info -> info.copy(similarArtist = info.similarArtist.map { a -> a.copy(id = Gid.of(Gid.source(id), a.id)) }) }

    /** Persist songs that came from server-side endpoints (similar songs etc.) but are unknown locally. */
    suspend fun ensureLocal(songs: List<ApiSong>, sourceId: String): List<SongEntity> {
        if (songs.isEmpty()) return emptyList()
        val existing = songsByIds(songs.map { Gid.of(sourceId, it.id) }).associateBy { it.id }
        val missing = songs.filter { Gid.of(sourceId, it.id) !in existing }.map { it.toEntity(gen = 0, sid = sourceId) }
        if (missing.isNotEmpty()) db.songs().upsert(missing)
        val all = existing + missing.associateBy { it.id }
        return songs.mapNotNull { all[Gid.of(sourceId, it.id)] }
    }

    // ---------- mutations (optimistic + offline queue) ----------
    fun toggleSongStar(song: SongEntity) = setStar(song.id, "song", !song.starred)
    fun toggleAlbumStar(album: AlbumEntity) = setStar(album.id, "album", !album.starred)
    fun toggleArtistStar(artist: ArtistEntity) = setStar(artist.id, "artist", !artist.starred)

    fun setStar(id: String, kind: String, starred: Boolean) {
        scope.launch {
            val now = System.currentTimeMillis()
            if (kind == "song") {
                // A favourite applies to the recording, i.e. every copy on every source.
                val copies = copiesOf(id)
                copies.forEach { db.songs().setStarred(it, starred, now) }
                copies.filterNot { Gid.isLocal(it) || Gid.isVirtual(it) }.forEach { cid ->
                    db.pending().deleteFor(cid, listOf(PendingType.STAR, PendingType.UNSTAR))
                    db.pending().insert(PendingActionEntity(type = if (starred) PendingType.STAR else PendingType.UNSTAR, targetId = cid, targetKind = kind))
                }
                _favoriteChanged.tryEmit(Unit)
                flushPending()
                return@launch
            }
            when (kind) {
                "song" -> db.songs().setStarred(id, starred, now)
                "album" -> db.albums().setStarred(id, starred, now)
                "artist" -> db.artists().setStarred(id, starred, now)
            }
            if (!Gid.isLocal(id)) {
            db.pending().deleteFor(id, listOf(PendingType.STAR, PendingType.UNSTAR))
            db.pending().insert(
                PendingActionEntity(type = if (starred) PendingType.STAR else PendingType.UNSTAR, targetId = id, targetKind = kind)
            )
            }
            if (kind == "song") _favoriteChanged.tryEmit(Unit)
            flushPending()
        }
    }

    fun setRating(id: String, kind: String, rating: Int) {
        scope.launch {
            if (kind == "song") {
                for (cid in copiesOf(id)) {
                    db.songs().setRating(cid, rating)
                    if (!Gid.isLocal(cid) && !Gid.isVirtual(cid)) {
                        db.pending().deleteFor(cid, listOf(PendingType.RATE))
                        db.pending().insert(PendingActionEntity(type = PendingType.RATE, targetId = cid, targetKind = kind, value = rating.toLong()))
                    }
                }
                flushPending()
                return@launch
            }
            when (kind) {
                "song" -> db.songs().setRating(id, rating)
                "album" -> db.albums().setRating(id, rating)
            }
            if (!Gid.isLocal(id)) {
                db.pending().deleteFor(id, listOf(PendingType.RATE))
                db.pending().insert(PendingActionEntity(type = PendingType.RATE, targetId = id, targetKind = kind, value = rating.toLong()))
            }
            flushPending()
        }
    }

    suspend fun queueScrobble(songId: String, at: Long) {
        if (Gid.isLocal(songId)) return
        val target = if (Gid.isVirtual(songId)) Gid.parent(songId) else songId
        if (target != songId) return   // a slice of an image isn't a server-side play
        db.pending().insert(PendingActionEntity(type = PendingType.SCROBBLE, targetId = songId, targetKind = "song", value = at))
        flushPending()
    }

    /** All copies of a recording across sources (just [id] when it has no merged siblings). */
    suspend fun copiesOf(id: String): List<String> {
        val key = db.songs().byId(id)?.mergeKey.orEmpty()
        if (key.isEmpty() || !key.startsWith("so:")) return listOf(id)
        return db.dedup().siblings(key).map { it.id }.ifEmpty { listOf(id) }
    }

    suspend fun siblings(song: SongEntity): List<SongEntity> =
        if (song.mergeKey.startsWith("so:")) db.dedup().siblings(song.mergeKey).ifEmpty { listOf(song) } else listOf(song)

    fun pendingCount() = db.pending().observeCount()

    /** Push queued mutations. Stops at the first network error, keeping order intact. */
    suspend fun flushPending() = flushMutex.withLock {
        val blocked = mutableSetOf<String>()   // sources that failed this round keep their order
        for (a in db.pending().all()) {
            val sid = Gid.source(a.targetId)
            if (sid in blocked) continue
            val api = sources.api(sid)
            if (api == null) { db.pending().delete(a.id); continue }   // source removed
            val raw = Gid.serverRaw(a.targetId)
            try {
                when (a.type) {
                    PendingType.STAR -> api.star(raw, a.targetKind)
                    PendingType.UNSTAR -> api.unstar(raw, a.targetKind)
                    PendingType.RATE -> api.setRating(raw, a.value.toInt())
                    PendingType.SCROBBLE -> api.scrobble(raw, a.value, submission = true)
                }
                db.pending().delete(a.id)
            } catch (e: dev.slurpdrome.data.api.SubsonicException) {
                // Subsonic-level rejection (e.g. 70 = not found) — drop rather than retry forever.
                // Auth problems (40/41) and HTTP errors (>=100) are transient: pause and retry later.
                if (e.code in 1..99 && e.code !in 40..41) db.pending().delete(a.id) else blocked += sid
            } catch (e: Exception) {
                Log.d("Library", "flush paused for $sid: ${e.message}")
                blocked += sid
            }
        }
    }
}
