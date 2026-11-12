package dev.slurpdrome.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.prefs.Settings
import dev.slurpdrome.data.repo.DownloadManager
import dev.slurpdrome.data.repo.LibraryRepository
import dev.slurpdrome.data.repo.NetworkMonitor
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.source.LocalScanner
import dev.slurpdrome.data.source.SourceRegistry

class MediaItemFactory(
    private val sources: SourceRegistry,
    private val downloads: DownloadManager,
    private val network: NetworkMonitor,
    private val library: () -> LibraryRepository,
    private val settings: () -> Settings,
) {
    fun metadata(song: SongEntity): MediaMetadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .setAlbumTitle(song.album)
        .setAlbumArtist(song.artist)
        .setGenre(song.genre)
        .setTrackNumber(song.track.takeIf { it > 0 })
        .setDiscNumber(song.disc.takeIf { it > 0 })
        .setReleaseYear(song.year.takeIf { it > 0 })
        .setArtworkUri(sources.artContentUri(song.coverArt))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(Bundle().apply {
            putString(EXTRA_ALBUM_ID, song.groupAlbumId ?: song.albumId)
            putString(EXTRA_ARTIST_ID, song.groupArtistId ?: song.artistId)
            putLong(EXTRA_DURATION, song.duration * 1000L)
        })
        .build()

    /** Lightweight item the UI sends to the session; the service resolves the URI itself. */
    fun stub(song: SongEntity): MediaItem = MediaItem.Builder()
        .setMediaId(song.id)
        .setMediaMetadata(metadata(song))
        .build()

    /**
     * Best playable copy of [song] across sources, in order: file on the device → downloaded copy →
     * stream from a healthy server (by source priority, then quality). The queue keeps [song]'s id;
     * the copy actually used is recorded in extras ([EXTRA_INSTANCE]) for scrobbling and failover.
     */
    suspend fun playable(song: SongEntity, avoid: Set<String> = emptySet()): MediaItem {
        val copies = library().siblings(song).filter { it.id !in avoid }.ifEmpty { listOf(song) }
        val online = network.state.value.online
        val order = settings().sourcePriority
        val instance = copies.minWith(
            compareBy<SongEntity>(
                { if (Gid.isLocal(it.id)) 0 else if (downloads.localFile(it.id) != null) 1 else if (online && sources.isHealthy(it.sourceId)) 2 else 3 },
                { order.indexOf(it.sourceId).let { i -> if (i < 0) 99 else i } },
                { -dev.slurpdrome.data.source.Deduper.qualityScore(it) },
            )
        )
        return build(song, instance)
    }

    private fun build(song: SongEntity, instance: SongEntity): MediaItem {
        val meta = metadata(song).let { m ->
            m.buildUpon().setExtras(Bundle(m.extras ?: Bundle()).apply { putString(EXTRA_INSTANCE, instance.id) }).build()
        }
        val b = MediaItem.Builder().setMediaId(song.id).setMediaMetadata(meta)
        if (instance.clipEndMs > 0) {
            // Virtual CUE track: a slice of the parent image. Always the original (seekable) stream,
            // cache keyed by the parent so every track of the image shares the same cached bytes.
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(instance.clipStartMs).setEndPositionMs(instance.clipEndMs).build()
            )
            val parent = Gid.parent(instance.id)
            val file = downloads.localFile(parent)
            if (file != null) b.setUri(Uri.fromFile(file))
            else {
                b.setUri(sources.apiFor(parent)?.streamUrl(Gid.raw(parent), 0, "raw") ?: "")
                b.setCustomCacheKey(cacheKey(parent, 0, "raw"))
            }
            return b.build()
        }
        val local = downloads.localFile(instance.id)
        when {
            Gid.isLocal(instance.id) -> {
                b.setUri(LocalScanner.contentUri(Gid.raw(instance.id)))
                instance.contentType?.let(b::setMimeType)
            }
            local != null -> b.setUri(Uri.fromFile(local))
            else -> {
                val s = settings()
                val bitrate = if (network.state.value.unmetered) s.streamBitrateWifi else s.streamBitrateMobile
                val api = sources.apiFor(instance.id)
                b.setUri(api?.streamUrl(Gid.raw(instance.id), bitrate, s.streamFormat) ?: "")
                // Key the cache by song + quality, not by the auth-bearing URL.
                b.setCustomCacheKey(cacheKey(instance.id, bitrate, s.streamFormat))
            }
        }
        return b.build()
    }

    /**
     * Item for a Chromecast receiver: it fetches the stream itself, so it needs a direct server
     * URL (no content:// or file://), an HTTP cover and a supported MIME type. A local file can
     * still be cast if a copy of it exists on a server. Null when no server has it.
     */
    suspend fun castItem(song: SongEntity): MediaItem? {
        val instance = library().siblings(song).filter { !Gid.isLocal(it.id) && sources.isHealthy(it.sourceId) }
            .minByOrNull { settings().sourcePriority.indexOf(it.sourceId).let { i -> if (i < 0) 99 else i } } ?: return null
        val api = sources.apiFor(instance.id) ?: return null
        val raw = Gid.serverRaw(instance.id)
        val native = instance.suffix?.lowercase() in CAST_NATIVE
        val url = if (native) api.streamUrl(raw, 0, "raw") else api.streamUrl(raw, 320, "mp3")
        val mime = if (native) instance.contentType ?: "audio/${instance.suffix}" else "audio/mpeg"
        val meta = metadata(song).buildUpon()
            .setArtworkUri(sources.artUrl(song.coverArt, 600)?.let(Uri::parse))
            .setExtras(Bundle(metadata(song).extras ?: Bundle()).apply { putString(EXTRA_INSTANCE, instance.id) })
            .build()
        return MediaItem.Builder().setMediaId(song.id).setUri(url).setMimeType(mime).setMediaMetadata(meta)
            .apply {
                if (instance.clipEndMs > 0) setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(instance.clipStartMs).setEndPositionMs(instance.clipEndMs).build()
                )
            }.build()
    }

    companion object {
        private val CAST_NATIVE = setOf("mp3", "aac", "m4a", "flac", "wav", "ogg", "opus", "webm")
        const val EXTRA_ALBUM_ID = "albumId"
        const val EXTRA_ARTIST_ID = "artistId"
        const val EXTRA_DURATION = "durationMs"
        const val EXTRA_INSTANCE = "instanceId"
        fun cacheKey(id: String, bitrate: Int, format: String) = "song:$id:$bitrate:$format"
    }
}
