package dev.slurpdrome.data.source

import dev.slurpdrome.R
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.slurpdrome.data.db.AlbumEntity
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.ArtistEntity
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.repo.fixMojibake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Indexes music on the device through MediaStore (fast, tag-aware, no SAF tree walking). */
class LocalScanner(private val context: Context, private val db: AppDatabase) {

    data class Folder(val path: String, val songs: Int)

    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class Row(
        val id: Long, val title: String, val artist: String, val artistId: Long, val album: String, val albumId: Long,
        val duration: Long, val track: Int, val year: Int, val mime: String?, val size: Long, val added: Long,
        val path: String, val name: String, val genre: String?, val bitrate: Int,
    )

    private fun rows(): List<Row> {
        if (!hasPermission()) return emptyList()
        val cols = buildList {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ARTIST_ID); add(MediaStore.Audio.Media.ALBUM); add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION); add(MediaStore.Audio.Media.TRACK); add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.MIME_TYPE); add(MediaStore.Audio.Media.SIZE); add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Audio.Media.RELATIVE_PATH) else add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= 30) { add(MediaStore.Audio.Media.GENRE); add(MediaStore.Audio.Media.BITRATE) }
        }.toTypedArray()
        val out = mutableListOf<Row>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            fun idx(n: String) = c.getColumnIndex(n)
            val iPath = if (Build.VERSION.SDK_INT >= 29) idx(MediaStore.Audio.Media.RELATIVE_PATH) else idx(MediaStore.Audio.Media.DATA)
            val iGenre = if (Build.VERSION.SDK_INT >= 30) idx(MediaStore.Audio.Media.GENRE) else -1
            val iBit = if (Build.VERSION.SDK_INT >= 30) idx(MediaStore.Audio.Media.BITRATE) else -1
            while (c.moveToNext()) {
                val rawPath = c.getString(iPath).orEmpty()
                val rel = if (Build.VERSION.SDK_INT >= 29) rawPath
                else rawPath.substringAfter("/emulated/0/", rawPath).substringBeforeLast('/') + "/"
                out += Row(
                    id = c.getLong(idx(MediaStore.Audio.Media._ID)),
                    title = c.getString(idx(MediaStore.Audio.Media.TITLE)).orEmpty().fixMojibake(),
                    artist = c.getString(idx(MediaStore.Audio.Media.ARTIST)).orEmpty().fixMojibake().let { if (it == "<unknown>") "" else it },
                    artistId = c.getLong(idx(MediaStore.Audio.Media.ARTIST_ID)),
                    album = c.getString(idx(MediaStore.Audio.Media.ALBUM)).orEmpty().fixMojibake(),
                    albumId = c.getLong(idx(MediaStore.Audio.Media.ALBUM_ID)),
                    duration = c.getLong(idx(MediaStore.Audio.Media.DURATION)),
                    track = c.getInt(idx(MediaStore.Audio.Media.TRACK)),
                    year = c.getInt(idx(MediaStore.Audio.Media.YEAR)),
                    mime = c.getString(idx(MediaStore.Audio.Media.MIME_TYPE)),
                    size = c.getLong(idx(MediaStore.Audio.Media.SIZE)),
                    added = c.getLong(idx(MediaStore.Audio.Media.DATE_ADDED)) * 1000,
                    path = rel,
                    name = c.getString(idx(MediaStore.Audio.Media.DISPLAY_NAME)).orEmpty(),
                    genre = if (iGenre >= 0) c.getString(iGenre) else null,
                    bitrate = if (iBit >= 0) c.getInt(iBit) / 1000 else 0,
                )
            }
        }
        return out
    }

    /** Folders that contain music, for the folder picker. */
    suspend fun folders(): List<Folder> = withContext(Dispatchers.IO) {
        rows().groupingBy { it.path }.eachCount().map { Folder(it.key, it.value) }.sortedBy { it.path.lowercase() }
    }

    suspend fun scan(source: Source, gen: Long) = withContext(Dispatchers.IO) {
        val sid = source.id
        val all = rows().filter { r -> source.folders.isEmpty() || source.folders.any { r.path.startsWith(it) } }
        // Keep user state (plays, stars, ratings) that only exists locally for these files.
        val previous = db.songs().bySource(sid).associateBy { it.id }
        val firstOfAlbum = all.groupBy { it.albumId }.mapValues { (_, v) -> v.minBy { it.track }.id }

        val songs = all.map { r ->
            val id = Gid.of(sid, r.id.toString())
            val old = previous[id]
            SongEntity(
                id = id, sourceId = sid,
                title = r.title.ifBlank { r.name.substringBeforeLast('.') },
                album = r.album, albumId = Gid.of(sid, "a${r.albumId}"),
                artist = r.artist, artistId = Gid.of(sid, "r${r.artistId}"),
                track = r.track % 1000, disc = (r.track / 1000).coerceAtLeast(1), year = r.year,
                genre = r.genre?.takeIf { it.isNotBlank() }, duration = (r.duration / 1000).toInt(),
                bitRate = r.bitrate, suffix = r.name.substringAfterLast('.', "").lowercase().ifBlank { null },
                contentType = r.mime, size = r.size,
                coverArt = Gid.of(sid, firstOfAlbum[r.albumId].toString()),
                created = r.added,
                playCount = old?.playCount ?: 0, lastPlayed = old?.lastPlayed ?: 0,
                starred = old?.starred ?: false, starredAt = old?.starredAt ?: 0, rating = old?.rating ?: 0,
                bpm = 0, trackGain = null, albumGain = null, syncGen = gen,
            )
        }
        val prevAlbums = db.albums().bySource(sid).associateBy { it.id }
        val albums = songs.groupBy { it.albumId!! }.map { (aid, list) ->
            val first = list.first(); val old = prevAlbums[aid]
            AlbumEntity(
                id = aid, sourceId = sid, name = first.album, artist = first.artist, artistId = first.artistId,
                coverArt = first.coverArt, songCount = list.size, duration = list.sumOf { it.duration },
                year = list.maxOf { it.year }, genre = list.firstNotNullOfOrNull { it.genre },
                created = list.maxOf { it.created }, playCount = list.sumOf { it.playCount },
                lastPlayed = list.maxOf { it.lastPlayed }, starred = old?.starred ?: false,
                starredAt = old?.starredAt ?: 0, rating = old?.rating ?: 0, syncGen = gen,
            )
        }
        val prevArtists = db.artists().bySource(sid).associateBy { it.id }
        val artists = songs.groupBy { it.artistId!! }.map { (rid, list) ->
            val old = prevArtists[rid]
            ArtistEntity(
                id = rid, sourceId = sid, name = list.first().artist.ifBlank { context.getString(R.string.unknown_artist) },
                albumCount = list.map { it.albumId }.distinct().size, coverArt = list.first().coverArt, imageUrl = null,
                starred = old?.starred ?: false, starredAt = old?.starredAt ?: 0, rating = 0, syncGen = gen,
            )
        }
        songs.chunked(500).forEach { db.songs().upsert(it) }
        db.albums().upsert(albums)
        db.artists().upsert(artists)
        db.songs().deleteStale(sid, gen)
        db.albums().deleteStale(sid, gen)
        db.artists().deleteStale(sid, gen)
        songs.size
    }

    companion object {
        fun contentUri(rawId: String): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, rawId.toLong())
    }
}
