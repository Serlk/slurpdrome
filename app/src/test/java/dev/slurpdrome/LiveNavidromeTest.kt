package dev.slurpdrome

import dev.slurpdrome.data.api.ServerCredentials
import dev.slurpdrome.data.api.SubsonicApi
import dev.slurpdrome.data.api.SubsonicException
import dev.slurpdrome.data.repo.toEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Integration test against the public Navidrome demo (demo/demo). Skipped when offline.
 * The demo host intermittently stalls responses; the client's retry/backoff is what keeps this green.
 * Exercises every endpoint the app relies on and the JSON → entity mapping.
 */
class LiveNavidromeTest {
    private val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
    private val creds = ServerCredentials.create("demo.navidrome.org", "demo", "demo")
    private val api = SubsonicApi(http, { creds })

    @Before fun online() {
        val ok = runCatching { runBlocking { api.ping(creds) }.status == "ok" }.getOrDefault(false)
        assumeTrue("demo server unreachable", ok)
    }

    @Test fun tokenAuthAndNormalisation() {
        assertEquals("https://demo.navidrome.org", creds.baseUrl)
        val bad = ServerCredentials.create("demo.navidrome.org", "demo", "wrong")
        val e = runCatching { runBlocking { api.ping(bad) } }.exceptionOrNull()
        assertTrue(e is SubsonicException && e.code == 40)
    }

    @Test fun fullSyncEndpointsParse() = runBlocking {
        val artists = api.getArtists()
        assertTrue("artists", artists.isNotEmpty())
        val albums = api.getAlbumList("alphabeticalByName", 20)
        assertTrue("albums", albums.isNotEmpty())
        val page1 = api.search("", artistCount = 0, albumCount = 0, songCount = 20, songOffset = 0).song
        val page2 = api.search("", artistCount = 0, albumCount = 0, songCount = 20, songOffset = 20).song
        assertTrue("empty-query search returns songs", page1.size == 20)
        assertTrue("paging advances", page2.isEmpty() || page2.first().id != page1.first().id)
        val e = page1.first().toEntity(1, "s1")
        assertTrue(e.title.isNotBlank() && e.duration > 0 && e.created > 0 && e.id.startsWith("s1~"))
        println("artists=${artists.size} albums(page)=${albums.size} songs(page)=${page1.size}")
    }

    @Test fun albumArtistPlaylistLyricsSimilar() = runBlocking {
        val album = api.getAlbumList("frequent", 5).first()
        val full = api.getAlbum(album.id)!!
        assertTrue(full.song.isNotEmpty())
        val song = full.song.first()

        api.getPlaylists().firstOrNull()?.let { p -> api.getPlaylist(p.id) }

        // Endpoints that may legitimately return nothing must still parse.
        api.getLyricsBySongId(song.id)
        song.artistId?.let { api.getSimilarSongsByArtist(it, 10); api.getArtistInfo(it) }
        api.getSimilarSongs(song.id, 10)

        // Cover art + stream URLs actually resolve.
        val art = http.newCall(Request.Builder().url(api.coverArtUrl(song.coverArt, 100)!!).build()).execute()
        assertTrue("cover art", art.isSuccessful && art.body!!.contentType()?.type == "image")
        art.close()
        val stream = http.newCall(Request.Builder().url(api.streamUrl(song.id, 128, "mp3")).header("Range", "bytes=0-1023").build()).execute()
        assertTrue("stream ${stream.code}", stream.isSuccessful && stream.body!!.contentType()?.type == "audio")
        stream.close()
    }
}
