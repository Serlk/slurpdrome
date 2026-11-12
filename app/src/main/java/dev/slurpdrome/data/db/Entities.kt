package dev.slurpdrome.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [Index("albumId"), Index("artistId"), Index("genre"), Index("title"), Index("starred"), Index("sourceId"),
        Index("mergeKey"), Index("groupAlbumId"), Index("groupArtistId")],
)
data class SongEntity(
    @PrimaryKey val id: String,            // global id "<sourceId>~<rawId>"
    val sourceId: String,
    val title: String,
    val album: String,
    val albumId: String?,
    val artist: String,
    val artistId: String?,
    val track: Int,
    val disc: Int,
    val year: Int,
    val genre: String?,
    val duration: Int,          // seconds
    val bitRate: Int,
    val suffix: String?,
    val contentType: String?,
    val size: Long,
    val coverArt: String?,
    val created: Long,          // epoch ms, "date added"
    val playCount: Long,
    val lastPlayed: Long,       // epoch ms, 0 = never
    val starred: Boolean,
    val starredAt: Long,
    val rating: Int,
    val bpm: Int,
    val trackGain: Float?,
    val albumGain: Float?,
    val syncGen: Long,
    /** Same recording across sources shares a mergeKey; one row per key is the visible one. */
    @ColumnInfo(defaultValue = "") val mergeKey: String = "",
    @ColumnInfo(defaultValue = "1") val isPrimary: Boolean = true,
    /** Representative album / artist of the merged group (null until the deduper ran). */
    val groupAlbumId: String? = null,
    val groupArtistId: String? = null,
    /** Virtual track of a CUE image: play [clipStartMs]..[clipEndMs] of the parent file. */
    @ColumnInfo(defaultValue = "0") val clipStartMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val clipEndMs: Long = 0,
)

@Entity(tableName = "albums", indices = [Index("artistId"), Index("name"), Index("sourceId"), Index("mergeKey"), Index("groupArtistId")])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val name: String,
    val artist: String,
    val artistId: String?,
    val coverArt: String?,
    val songCount: Int,
    val duration: Int,
    val year: Int,
    val genre: String?,
    val created: Long,
    val playCount: Long,
    val lastPlayed: Long,
    val starred: Boolean,
    val starredAt: Long,
    val rating: Int,
    val syncGen: Long,
    @ColumnInfo(defaultValue = "") val mergeKey: String = "",
    @ColumnInfo(defaultValue = "1") val isPrimary: Boolean = true,
    val groupArtistId: String? = null,
)

@Entity(tableName = "artists", indices = [Index("name"), Index("sourceId"), Index("mergeKey")])
data class ArtistEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val name: String,
    val albumCount: Int,
    val coverArt: String?,
    val imageUrl: String?,
    val starred: Boolean,
    val starredAt: Long,
    val rating: Int,
    val syncGen: Long,
    @ColumnInfo(defaultValue = "") val mergeKey: String = "",
    @ColumnInfo(defaultValue = "1") val isPrimary: Boolean = true,
)

@Entity(tableName = "playlists", indices = [Index("sourceId")])
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val name: String,
    val comment: String?,
    val owner: String?,
    val songCount: Int,
    val duration: Int,
    val coverArt: String?,
    val changed: Long,
    val syncGen: Long,
)

@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "position"], indices = [Index("songId")])
data class PlaylistEntryEntity(
    val playlistId: String,
    val position: Int,
    val songId: String,
)

/** A "keep offline" rule pinned on an album / playlist / artist. */
@Entity(tableName = "offline_pins", primaryKeys = ["kind", "targetId"])
data class OfflinePinEntity(
    val kind: String,   // album | playlist | artist
    val targetId: String,
    val createdAt: Long = System.currentTimeMillis(),
)

object DownloadState {
    const val QUEUED = 0
    const val RUNNING = 1
    const val DONE = 2
    const val FAILED = 3
}

@Entity(tableName = "downloads", indices = [Index("state")])
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val state: Int,
    val manual: Boolean,       // user explicitly downloaded it (survives rule reconciliation)
    val filePath: String?,
    val bytes: Long,
    val totalBytes: Long,
    val error: String?,
    val updatedAt: Long,
)

/** One listening session of a track — the raw signal for mixes and stats. */
@Entity(tableName = "play_events", indices = [Index("songId"), Index("startedAt"), Index("artistId")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val artistId: String?,
    val albumId: String?,
    val genre: String?,
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val skipped: Boolean,
    val completed: Boolean,
    val source: String?,       // what started the playback: album, mix:my, search, ...
)

object PendingType {
    const val STAR = "star"
    const val UNSTAR = "unstar"
    const val RATE = "rate"
    const val SCROBBLE = "scrobble"
}

/** Mutations made while offline, flushed to the server when connectivity returns. */
@Entity(tableName = "pending_actions")
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val targetId: String,
    val targetKind: String,    // song | album | artist
    val value: Long = 0,       // rating, or scrobble timestamp
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: String,
    val synced: Boolean,
    val linesJson: String,     // [{"t":1234,"v":"text"}]
    val fetchedAt: Long,
)

@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val specJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val keepOffline: Boolean = false,
)

@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val songId: String,
)

/** Playlist that lives on the device and may mix songs from every source. */
@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val comment: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** [mergeKey] lets an entry survive its song vanishing from one server (a copy elsewhere is used). */
@Entity(tableName = "local_playlist_entries", primaryKeys = ["playlistId", "position"], indices = [Index("songId")])
data class LocalPlaylistEntryEntity(
    val playlistId: Long,
    val position: Int,
    val songId: String,
    val mergeKey: String,
)

/** Audiobook / long-form resume point for an album or playlist context. */
@Entity(tableName = "resume_points")
data class ResumePointEntity(
    @PrimaryKey val contextKey: String,   // "album:<gid>" | "playlist:<gid>"
    val songId: String,
    val positionMs: Long,
    val finished: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** User bookmark inside a track (chapter marks, quotes in audiobooks). */
@Entity(tableName = "bookmarks", indices = [Index("songId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val positionMs: Long,
    val note: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Lightweight projections */
data class GenreCount(val genre: String, val songCount: Int, val albumCount: Int)

data class ArtistAffinityRow(val artistId: String?, val plays: Int, val skips: Int, val listenedMs: Long, val lastAt: Long)

data class IdCount(val id: Long, val n: Int)

/** Result of looking for a cue sheet inside a long file ([json] empty = none found). */
@Entity(tableName = "cue_splits")
data class CueSplitEntity(
    @PrimaryKey val parentId: String,
    val json: String,
    val checkedAt: Long = System.currentTimeMillis(),
)
