package dev.slurpdrome.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire models for the Subsonic / OpenSubsonic JSON API as implemented by Navidrome. */

@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
data class SubsonicError(val code: Int = 0, val message: String = "")

@Serializable
data class SubsonicResponse(
    val status: String = "failed",
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val error: SubsonicError? = null,
    val artists: ArtistsIndex? = null,
    val albumList2: AlbumList? = null,
    val album: AlbumWithSongs? = null,
    val artist: ArtistWithAlbums? = null,
    val searchResult3: SearchResult? = null,
    val starred2: Starred? = null,
    val playlists: Playlists? = null,
    val playlist: PlaylistWithSongs? = null,
    val similarSongs2: SongList? = null,
    val similarSongs: SongList? = null,
    val topSongs: SongList? = null,
    val randomSongs: SongList? = null,
    val lyricsList: LyricsList? = null,
    val lyrics: PlainLyrics? = null,
    val artistInfo2: ArtistInfo? = null,
    val genres: Genres? = null,
    val bookmarks: Bookmarks? = null,
)

@Serializable
data class ArtistsIndex(val index: List<ArtistIndexEntry> = emptyList())

@Serializable
data class ArtistIndexEntry(val name: String = "", val artist: List<ApiArtist> = emptyList())

@Serializable
data class ApiArtist(
    val id: String,
    val name: String = "",
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val artistImageUrl: String? = null,
    val starred: String? = null,
    val userRating: Int = 0,
)

@Serializable
data class ArtistWithAlbums(
    val id: String,
    val name: String = "",
    val album: List<ApiAlbum> = emptyList(),
)

@Serializable
data class AlbumList(val album: List<ApiAlbum> = emptyList())

@Serializable
data class ApiAlbum(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val playCount: Long = 0,
    val created: String? = null,
    val year: Int = 0,
    val genre: String? = null,
    val starred: String? = null,
    val userRating: Int = 0,
    val played: String? = null,
)

@Serializable
data class AlbumWithSongs(
    val id: String,
    val name: String = "",
    val song: List<ApiSong> = emptyList(),
)

@Serializable
data class ApiSong(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long = 0,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Int = 0,
    val bitRate: Int = 0,
    val path: String? = null,
    val playCount: Long = 0,
    val played: String? = null,
    val created: String? = null,
    val starred: String? = null,
    val userRating: Int = 0,
    val bpm: Int = 0,
    val replayGain: ReplayGain? = null,
)

@Serializable
data class ReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
)

@Serializable
data class SearchResult(
    val artist: List<ApiArtist> = emptyList(),
    val album: List<ApiAlbum> = emptyList(),
    val song: List<ApiSong> = emptyList(),
)

@Serializable
data class Starred(
    val artist: List<ApiArtist> = emptyList(),
    val album: List<ApiAlbum> = emptyList(),
    val song: List<ApiSong> = emptyList(),
)

@Serializable
data class Playlists(val playlist: List<ApiPlaylist> = emptyList())

@Serializable
data class ApiPlaylist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null,
)

@Serializable
data class PlaylistWithSongs(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val changed: String? = null,
    val coverArt: String? = null,
    val entry: List<ApiSong> = emptyList(),
)

@Serializable
data class SongList(val song: List<ApiSong> = emptyList())

@Serializable
data class LyricsList(val structuredLyrics: List<StructuredLyrics> = emptyList())

@Serializable
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val offset: Long = 0,
    val line: List<LyricLine> = emptyList(),
)

@Serializable
data class LyricLine(val start: Long? = null, val value: String = "")

@Serializable
data class PlainLyrics(val artist: String? = null, val title: String? = null, val value: String? = null)

@Serializable
data class ArtistInfo(
    val biography: String? = null,
    val largeImageUrl: String? = null,
    val similarArtist: List<ApiArtist> = emptyList(),
)

@Serializable
data class Genres(val genre: List<ApiGenre> = emptyList())

@Serializable
data class ApiGenre(val value: String = "", val songCount: Int = 0, val albumCount: Int = 0)

@Serializable
data class Bookmarks(val bookmark: List<ApiBookmark> = emptyList())

@Serializable
data class ApiBookmark(val position: Long = 0, val changed: String? = null, val entry: ApiSong? = null)
