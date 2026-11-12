package dev.slurpdrome.player

import dev.slurpdrome.R
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import dev.slurpdrome.AppContainer
import dev.slurpdrome.data.db.DownloadState
import dev.slurpdrome.data.db.SongEntity
import kotlinx.coroutines.flow.first

/**
 * Browse tree for Android Auto / other MediaBrowser clients. Node ids:
 *   root, continue, mixes, mix|<key>, albums, album|<gid>, playlists, playlist|<gid>, smart|<id>,
 *   artists, artist|<gid>, favorites, downloads.
 * Playable children carry "ctx§<parent>§<songGid>" so picking one plays the whole parent from it.
 */
class LibraryTree(private val c: AppContainer) {
    /** Resolved per call so folder names follow the current locale. */
    private fun str(id: Int): String = c.app.getString(id)

    fun root(): MediaItem = folder(ROOT, "Slurpdrome", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val all: List<MediaItem> = when {
            parentId == ROOT -> listOf(
                folder("continue", str(R.string.auto_continue), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder("mixes", str(R.string.auto_mixes), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder("albums", str(R.string.auto_recent_albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, grid = true),
                folder("playlists", str(R.string.auto_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder("artists", str(R.string.auto_artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder("favorites", str(R.string.auto_favorites), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder("downloads", str(R.string.auto_downloads), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            )
            parentId == "continue" -> c.audiobooks.inProgress(20).first().mapNotNull { p ->
                c.db.albums().byId(p.contextKey.removePrefix("album:"))?.let { a -> folder("album|${a.id}", a.name, MediaMetadata.MEDIA_TYPE_ALBUM, a.artist, a.coverArt) }
            }
            parentId == "mixes" -> c.mixes.mixes().map { m -> folder("mix|${m.key}", m.title, MediaMetadata.MEDIA_TYPE_PLAYLIST, m.subtitle, m.coverArts.firstOrNull()) }
            parentId.startsWith("mix|") -> {
                val mix = c.mixes.mixes().firstOrNull { it.key == parentId.removePrefix("mix|") }
                songs(parentId, c.library.songsByIds(mix?.songIds.orEmpty()))
            }
            parentId == "albums" -> c.library.recentlyAddedAlbums(60).first().map { a ->
                folder("album|${a.id}", a.name, MediaMetadata.MEDIA_TYPE_ALBUM, a.artist, a.coverArt)
            }
            parentId.startsWith("album|") -> songs(parentId, c.library.albumSongsOnce(parentId.removePrefix("album|")))
            parentId == "playlists" -> c.localPlaylists.all().first().map { folder("lp|${it.id}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, str(R.string.auto_on_device)) } +
                c.smartPlaylists.all().first().map { folder("smart|${it.id}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, str(R.string.auto_smart_playlist)) } +
                c.playlists.all().first().map { p -> folder("playlist|${p.id}", p.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, null, p.coverArt) }
            parentId.startsWith("lp|") -> songs(parentId, c.localPlaylists.songsOnce(parentId.removePrefix("lp|").toLong()))
            parentId.startsWith("playlist|") -> songs(parentId, c.playlists.songsOnce(parentId.removePrefix("playlist|")))
            parentId.startsWith("smart|") -> {
                val e = c.db.smartPlaylists().byId(parentId.removePrefix("smart|").toLong())
                songs(parentId, e?.let { c.smartPlaylists.evaluate(c.smartPlaylists.decode(it)) }.orEmpty())
            }
            parentId == "artists" -> c.library.artists().first().map { a -> folder("artist|${a.id}", a.name, MediaMetadata.MEDIA_TYPE_ARTIST, null, a.coverArt) }
            parentId.startsWith("artist|") -> c.library.artistAlbums(parentId.removePrefix("artist|")).first().map { a ->
                folder("album|${a.id}", a.name, MediaMetadata.MEDIA_TYPE_ALBUM, a.year.takeIf { it > 0 }?.toString(), a.coverArt)
            }
            parentId == "favorites" -> songs(parentId, c.library.starredSongs().first())
            parentId == "downloads" -> songs(parentId, c.db.downloads().observeDownloadedSongs().first())
            else -> emptyList()
        }
        val from = (page * pageSize).coerceAtMost(all.size)
        return all.subList(from, (from + pageSize).coerceAtMost(all.size))
    }

    suspend fun item(id: String): MediaItem? = when {
        id == ROOT -> root()
        id.startsWith("ctx§") -> c.library.songsByIds(listOf(id.substringAfterLast('§'))).firstOrNull()?.let { c.mediaItems.stub(it) }
        else -> c.library.songsByIds(listOf(id)).firstOrNull()?.let { c.mediaItems.stub(it) }
    }

    suspend fun search(query: String): List<MediaItem> {
        val r = c.library.search(query)
        return r.albums.take(10).map { a -> folder("album|${a.id}", a.name, MediaMetadata.MEDIA_TYPE_ALBUM, a.artist, a.coverArt) } +
            songs("search", r.songs.take(40))
    }

    /**
     * Expands a tapped "ctx§parent§song" into the parent's full track list starting at that song.
     * Returns null when [items] isn't such a single contextual pick.
     */
    suspend fun expand(items: List<MediaItem>): Pair<List<SongEntity>, Int>? {
        val id = items.singleOrNull()?.mediaId ?: return null
        if (!id.startsWith("ctx§")) return null
        val (_, parent, songId) = id.split('§', limit = 3)
        val list = if (parent == "search") c.library.songsByIds(listOf(songId))
            else children(parent, 0, Int.MAX_VALUE).mapNotNull { it.mediaId.substringAfterLast('§').takeIf { _ -> it.mediaMetadata.isPlayable == true } }
                .let { c.library.songsByIds(it) }
        val start = list.indexOfFirst { it.id == songId }.coerceAtLeast(0)
        return list to start
    }

    private fun songs(parent: String, list: List<SongEntity>): List<MediaItem> {
        val done = c.downloads.downloads.value
        return list.map { s ->
            val base = c.mediaItems.metadata(s)
            MediaItem.Builder()
                .setMediaId("ctx§$parent§${s.id}")
                .setMediaMetadata(
                    base.buildUpon()
                        .setSubtitle(s.artist)
                        .setExtras(Bundle(base.extras ?: Bundle()).apply {
                            if (done[s.id]?.state == DownloadState.DONE) putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
                        })
                        .build()
                )
                .build()
        }
    }

    private fun folder(id: String, title: String, type: Int, subtitle: String? = null, coverArt: String? = null, grid: Boolean = false): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .setArtworkUri(c.sources.artContentUri(coverArt))
                    .setExtras(Bundle().apply {
                        putInt(
                            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                            if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                        )
                        putInt(
                            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                            if (type == MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS || type == MediaMetadata.MEDIA_TYPE_ARTIST) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                            else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                        )
                    })
                    .build()
            )
            .build()

    companion object { const val ROOT = "root" }
}
