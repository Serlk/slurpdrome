package dev.slurpdrome.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Credentials of a Navidrome account. [salt] is fixed at login so that every
 * cover-art / stream URL is stable, which keeps Coil's disk cache effective.
 */
data class ServerCredentials(
    val baseUrl: String,
    val username: String,
    val token: String,
    val salt: String,
) {
    companion object {
        fun create(baseUrl: String, username: String, password: String): ServerCredentials {
            val salt = (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            return ServerCredentials(normalizeUrl(baseUrl), username.trim(), md5(password + salt), salt)
        }

        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            return url
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

class SubsonicException(val code: Int, message: String) : IOException(message)

class SubsonicApi(
    private val client: Call.Factory,
    private val credentialsProvider: () -> ServerCredentials?,
    private val onStall: () -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private fun creds(): ServerCredentials =
        credentialsProvider() ?: throw SubsonicException(-1, "Not logged in")

    fun url(method: String, params: List<Pair<String, String>> = emptyList(), creds: ServerCredentials = creds()): HttpUrl {
        val b = "${creds.baseUrl}/rest/$method".toHttpUrl().newBuilder()
            .addQueryParameter("u", creds.username)
            .addQueryParameter("t", creds.token)
            .addQueryParameter("s", creds.salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    /**
     * One API round-trip. Transient network failures (timeouts, resets, 5xx) are retried with
     * backoff — a single stalled response shouldn't abort a 50k-track sync. Non-idempotent
     * calls pass [retry] = false so a retry can never create a duplicate playlist.
     */
    private suspend fun call(
        method: String,
        params: List<Pair<String, String>> = emptyList(),
        creds: ServerCredentials = creds(),
        retry: Boolean = true,
    ): SubsonicResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(method, params, creds)).get().build()
        val attempts = if (retry) 3 else 1
        var last: IOException? = null
        for (attempt in 1..attempts) {
            try {
                val body = client.newCall(request).await().use { resp ->
                    if (!resp.isSuccessful) throw SubsonicException(resp.code, "HTTP ${resp.code}")
                    resp.body?.string() ?: throw SubsonicException(-1, "Empty body")
                }
                val r = json.decodeFromString(SubsonicEnvelope.serializer(), body).response
                if (r.status != "ok") throw SubsonicException(r.error?.code ?: -1, r.error?.message ?: "Server error")
                return@withContext r
            } catch (e: SubsonicException) {
                if (e.code < 500 || attempt == attempts) throw e   // API-level errors are final
                last = e
            } catch (e: IOException) {
                onStall()   // may be a poisoned reused connection — next attempt gets a fresh one
                if (attempt == attempts) throw e
                last = e
            }
            kotlinx.coroutines.delay(700L * attempt)
        }
        throw last ?: IOException("unreachable")
    }

    suspend fun ping(creds: ServerCredentials): SubsonicResponse = call("ping", creds = creds)

    suspend fun getArtists(): List<ApiArtist> =
        call("getArtists").artists?.index.orEmpty().flatMap { it.artist }

    suspend fun getAlbumList(
        type: String, size: Int, offset: Int = 0,
        extra: List<Pair<String, String>> = emptyList(), retry: Boolean = true,
    ): List<ApiAlbum> =
        call("getAlbumList2", listOf("type" to type, "size" to "$size", "offset" to "$offset") + extra, retry = retry).albumList2?.album.orEmpty()

    suspend fun getAlbum(id: String): AlbumWithSongs? = call("getAlbum", listOf("id" to id)).album

    suspend fun getArtist(id: String): ArtistWithAlbums? = call("getArtist", listOf("id" to id)).artist

    suspend fun search(
        query: String,
        artistCount: Int = 20, albumCount: Int = 20, songCount: Int = 50,
        songOffset: Int = 0,
        retry: Boolean = true,
    ): SearchResult = call(
        "search3",
        listOf(
            "query" to query,
            "artistCount" to "$artistCount", "albumCount" to "$albumCount", "songCount" to "$songCount",
            "songOffset" to "$songOffset",
        ),
        retry = retry,
    ).searchResult3 ?: SearchResult()

    suspend fun getStarred(): Starred = call("getStarred2").starred2 ?: Starred()

    suspend fun getPlaylists(): List<ApiPlaylist> = call("getPlaylists").playlists?.playlist.orEmpty()

    suspend fun getPlaylist(id: String): PlaylistWithSongs? = call("getPlaylist", listOf("id" to id)).playlist

    suspend fun createPlaylist(name: String, songIds: List<String>): PlaylistWithSongs? =
        call("createPlaylist", listOf("name" to name) + songIds.map { "songId" to it }, retry = false).playlist

    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        add: List<String> = emptyList(),
        removeIndexes: List<Int> = emptyList(),
    ) {
        val p = buildList {
            add("playlistId" to id)
            name?.let { add("name" to it) }
            add.forEach { add("songIdToAdd" to it) }
            removeIndexes.forEach { add("songIndexToRemove" to "$it") }
        }
        call("updatePlaylist", p, retry = false)
    }

    /** Replace the whole content of a playlist (used for re-ordering). */
    suspend fun replacePlaylist(id: String, songIds: List<String>) {
        call("createPlaylist", listOf("playlistId" to id) + songIds.map { "songId" to it })
    }

    suspend fun deletePlaylist(id: String) { call("deletePlaylist", listOf("id" to id)) }

    suspend fun star(id: String, kind: String) { call("star", listOf(kind.idParam() to id)) }
    suspend fun unstar(id: String, kind: String) { call("unstar", listOf(kind.idParam() to id)) }

    private fun String.idParam() = when (this) {
        "album" -> "albumId"
        "artist" -> "artistId"
        else -> "id"
    }

    suspend fun setRating(id: String, rating: Int) { call("setRating", listOf("id" to id, "rating" to "$rating")) }

    suspend fun scrobble(id: String, timeMs: Long, submission: Boolean) {
        call("scrobble", listOf("id" to id, "time" to "$timeMs", "submission" to "$submission"))
    }

    suspend fun getSimilarSongs(id: String, count: Int): List<ApiSong> =
        call("getSimilarSongs", listOf("id" to id, "count" to "$count")).similarSongs?.song.orEmpty()

    suspend fun getSimilarSongsByArtist(artistId: String, count: Int): List<ApiSong> =
        call("getSimilarSongs2", listOf("id" to artistId, "count" to "$count")).similarSongs2?.song.orEmpty()

    suspend fun getTopSongs(artistName: String, count: Int = 20): List<ApiSong> =
        call("getTopSongs", listOf("artist" to artistName, "count" to "$count")).topSongs?.song.orEmpty()

    suspend fun getArtistInfo(id: String): ArtistInfo? =
        call("getArtistInfo2", listOf("id" to id, "count" to "20")).artistInfo2

    suspend fun getLyricsBySongId(id: String): List<StructuredLyrics> =
        call("getLyricsBySongId", listOf("id" to id)).lyricsList?.structuredLyrics.orEmpty()

    suspend fun getLyrics(artist: String, title: String): PlainLyrics? =
        call("getLyrics", listOf("artist" to artist, "title" to title)).lyrics

    suspend fun getBookmarks(): List<ApiBookmark> = call("getBookmarks").bookmarks?.bookmark.orEmpty()

    suspend fun createBookmark(id: String, positionMs: Long) {
        call("createBookmark", listOf("id" to id, "position" to "$positionMs"))
    }

    fun coverArtUrl(coverArtId: String?, size: Int = 600): String? {
        if (coverArtId.isNullOrBlank()) return null
        val c = credentialsProvider() ?: return null
        return url("getCoverArt", listOf("id" to coverArtId, "size" to "$size"), c).toString()
    }

    /** Streaming URL; maxBitRate 0 = original, format "raw" = no transcoding. */
    fun streamUrl(songId: String, maxBitRate: Int, format: String): String {
        val p = buildList {
            add("id" to songId)
            if (maxBitRate > 0) add("maxBitRate" to "$maxBitRate")
            if (format != "raw") add("format" to format) else add("format" to "raw")
            add("estimateContentLength" to "true")
        }
        return url("stream", p).toString()
    }

    fun downloadUrl(songId: String): String = url("download", listOf("id" to songId)).toString()

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Slurpdrome"
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
