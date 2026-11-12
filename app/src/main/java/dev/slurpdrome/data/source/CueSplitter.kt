package dev.slurpdrome.data.source

import android.util.Log
import dev.slurpdrome.data.api.await
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * Finds single-file album images (FLAC + embedded cue sheet) and turns them into virtual tracks.
 * Detection reads only the FLAC metadata at the start of the raw stream.
 */
class CueSplitter(private val db: AppDatabase, private val sources: SourceRegistry, private val http: Call.Factory) {

    suspend fun candidates(): List<SongEntity> = db.songs().cueCandidates(minSeconds = 600)

    /** Reads the embedded cue sheet of [song] from its server, or null when there is none. */
    suspend fun probe(song: SongEntity): CueAlbum? = withContext(Dispatchers.IO) {
        val api = sources.apiFor(song.id) ?: return@withContext null
        val url = api.streamUrl(Gid.raw(song.id), 0, "raw")
        http.newCall(Request.Builder().url(url).build()).await().use { r ->
            if (!r.isSuccessful) return@use null
            r.body?.byteStream()?.use { CueSheet.fromFlac(it.buffered(64 * 1024), song.duration * 1000L) }
        }
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Probe files we haven't looked at yet (each file is checked once). */
    suspend fun scan() {
        // Positive results are final; "no cue" is re-checked daily (the file may have been tagged since).
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        val checked = db.cueSplits().all().filter { it.json.isNotEmpty() || it.checkedAt > dayAgo }.map { it.parentId }.toHashSet()
        val todo = candidates().filter { it.id !in checked }
        if (todo.isEmpty()) return
        Log.i("Cue", "probing ${todo.size} long files for embedded cue sheets")
        for (s in todo) {
            val result = runCatching { probe(s) }
            if (result.isFailure) continue                     // network error: retry next sync
            val album = result.getOrNull()
            db.cueSplits().upsert(dev.slurpdrome.data.db.CueSplitEntity(s.id, album?.let { json.encodeToString(CueAlbum.serializer(), it) } ?: ""))
            if (album != null) Log.i("Cue", "split ${s.title}: ${album.tracks.size} tracks")
        }
    }

    /**
     * (Re)creates virtual albums/tracks for every split image whose file is still in the library.
     * Runs after each sync (sync deletes rows it didn't see), keeping stars/ratings/plays.
     */
    suspend fun materialize() = withContext(Dispatchers.IO) {
        for (split in db.cueSplits().all()) {
            if (split.json.isEmpty()) continue
            val parent = db.songs().byId(split.parentId) ?: continue
            val cue = runCatching { json.decodeFromString(CueAlbum.serializer(), split.json) }.getOrNull() ?: continue
            val sid = parent.sourceId
            val albumId = Gid.of(sid, "cue:" + Gid.raw(parent.id))
            // Title/performer fallbacks from the file name "Artist - Album".
            val nameParts = parent.title.split(" - ", limit = 2)
            val albumTitle = cue.title ?: nameParts.getOrNull(1) ?: parent.title
            val albumArtist = cue.performer ?: parent.artist.takeIf { !it.startsWith("[") } ?: nameParts.first()
            val old = db.songs().byIds(cue.tracks.map { Gid.virtual(parent.id, it.number) }).associateBy { it.id }
            val songs = cue.tracks.map { t ->
                val id = Gid.virtual(parent.id, t.number)
                val prev = old[id]
                parent.copy(
                    id = id, title = t.title, artist = t.performer ?: albumArtist, album = albumTitle, albumId = albumId,
                    artistId = parent.artistId.takeIf { !parent.artist.startsWith("[") },
                    track = t.number, disc = 1, year = if (cue.year > 0) cue.year else parent.year,
                    genre = cue.genre ?: parent.genre, duration = ((t.endMs - t.startMs) / 1000).toInt(), size = 0,
                    playCount = prev?.playCount ?: 0, lastPlayed = prev?.lastPlayed ?: 0, starred = prev?.starred ?: false,
                    starredAt = prev?.starredAt ?: 0, rating = prev?.rating ?: 0,
                    mergeKey = "", isPrimary = true, groupAlbumId = null, groupArtistId = null,
                    clipStartMs = t.startMs, clipEndMs = t.endMs,
                )
            }
            db.songs().upsert(songs)
            val prevAlbum = db.albums().byId(albumId)
            db.albums().upsert(listOf(dev.slurpdrome.data.db.AlbumEntity(
                id = albumId, sourceId = sid, name = albumTitle, artist = albumArtist, artistId = songs.first().artistId,
                coverArt = parent.coverArt, songCount = songs.size, duration = songs.sumOf { it.duration },
                year = songs.first().year, genre = songs.first().genre, created = parent.created,
                playCount = songs.sumOf { it.playCount }, lastPlayed = songs.maxOf { it.lastPlayed },
                starred = prevAlbum?.starred ?: false, starredAt = prevAlbum?.starredAt ?: 0, rating = prevAlbum?.rating ?: 0,
                syncGen = parent.syncGen,
            )))
        }
    }
}
