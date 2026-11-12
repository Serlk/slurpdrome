package dev.slurpdrome.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @RawQuery(observedEntities = [SongEntity::class, DownloadEntity::class, PlaylistEntryEntity::class])
    fun observeRaw(query: SupportSQLiteQuery): Flow<List<SongEntity>>

    @RawQuery
    suspend fun queryRaw(query: SupportSQLiteQuery): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun byId(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observeById(id: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<SongEntity>>

    /** Long single files that may be album images with an embedded cue sheet. */
    @Query("SELECT * FROM songs WHERE suffix = 'flac' AND duration >= :minSeconds AND track = 0 AND sourceId != 'local' AND id NOT LIKE '%#cue%'")
    suspend fun cueCandidates(minSeconds: Int): List<SongEntity>

    @Query("SELECT * FROM songs WHERE COALESCE(groupAlbumId, albumId) = :albumId AND isPrimary = 1 ORDER BY disc, track, title")
    fun observeByAlbum(albumId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE COALESCE(groupAlbumId, albumId) = :albumId AND isPrimary = 1 ORDER BY disc, track, title")
    suspend fun byAlbum(albumId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE COALESCE(groupArtistId, artistId) = :artistId AND isPrimary = 1 ORDER BY year DESC, album, disc, track")
    suspend fun byArtist(artistId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE COALESCE(groupArtistId, artistId) = :artistId AND isPrimary = 1 ORDER BY playCount DESC, rating DESC LIMIT :limit")
    fun observeTopByArtist(artistId: String, limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE genre = :genre AND isPrimary = 1 ORDER BY artist, album, disc, track")
    fun observeByGenre(genre: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE genre = :genre AND isPrimary = 1")
    suspend fun byGenre(genre: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE starred = 1 AND isPrimary = 1 ORDER BY starredAt DESC")
    fun observeStarred(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE starred = 1 AND isPrimary = 1")
    suspend fun starred(): List<SongEntity>

    @Query("SELECT id FROM songs WHERE starred = 1 AND isPrimary = 1")
    suspend fun starredIds(): List<String>

    @Query(
        """SELECT * FROM songs WHERE isPrimary = 1 AND (title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%'
           OR album LIKE '%' || :q || '%') ORDER BY
           CASE WHEN title LIKE :q || '%' THEN 0 WHEN title LIKE '%' || :q || '%' THEN 1 ELSE 2 END, playCount DESC
           LIMIT :limit"""
    )
    suspend fun search(q: String, limit: Int): List<SongEntity>

    @Query("SELECT * FROM songs WHERE isPrimary = 1 ORDER BY created DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE lastPlayed > 0 AND isPrimary = 1 ORDER BY lastPlayed DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE playCount > 0 AND isPrimary = 1 ORDER BY playCount DESC LIMIT :limit")
    fun observeMostPlayed(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isPrimary = 1 ORDER BY RANDOM() LIMIT :limit")
    suspend fun random(limit: Int): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs WHERE isPrimary = 1")
    fun observeCount(): Flow<Int>

    @Query(
        """SELECT genre AS genre, COUNT(*) AS songCount, COUNT(DISTINCT COALESCE(groupAlbumId, albumId)) AS albumCount
           FROM songs WHERE isPrimary = 1 AND genre IS NOT NULL AND genre != '' GROUP BY genre ORDER BY genre COLLATE NOCASE"""
    )
    fun observeGenres(): Flow<List<GenreCount>>

    @Upsert
    suspend fun upsert(items: List<SongEntity>)

    @Query("DELETE FROM songs WHERE sourceId = :sourceId AND syncGen != :gen")
    suspend fun deleteStale(sourceId: String, gen: Long)

    @Query("SELECT * FROM songs WHERE sourceId = :sourceId")
    suspend fun bySource(sourceId: String): List<SongEntity>

    @Query("DELETE FROM songs WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("UPDATE songs SET starred = :starred, starredAt = :at WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean, at: Long)

    @Query("UPDATE songs SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: Int)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :at WHERE id = :id")
    suspend fun markPlayed(id: String, at: Long)
}

@Dao
interface AlbumDao {
    @RawQuery(observedEntities = [AlbumEntity::class])
    fun observeRaw(query: SupportSQLiteQuery): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    fun observeById(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE COALESCE(groupArtistId, artistId) = :artistId AND isPrimary = 1 ORDER BY year DESC, name")
    fun observeByArtist(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT id FROM albums WHERE isPrimary = 1 AND name NOT LIKE '[Unknown%' AND songCount >= :minTracks AND duration >= songCount * :minAvgSec")
    suspend fun longFormAlbumIds(minTracks: Int, minAvgSec: Int): List<String>

    @Query("SELECT * FROM albums WHERE starred = 1 AND isPrimary = 1 ORDER BY starredAt DESC")
    fun observeStarred(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE isPrimary = 1 ORDER BY created DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE playCount > 0 AND isPrimary = 1 ORDER BY playCount DESC LIMIT :limit")
    fun observeMostPlayed(limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE lastPlayed > 0 AND isPrimary = 1 ORDER BY lastPlayed DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE isPrimary = 1 ORDER BY RANDOM() LIMIT :limit")
    suspend fun random(limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE genre = :genre AND isPrimary = 1 ORDER BY year DESC")
    fun observeByGenre(genre: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE isPrimary = 1 AND (name LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%') ORDER BY playCount DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<AlbumEntity>

    @Upsert
    suspend fun upsert(items: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE sourceId = :sourceId AND syncGen != :gen")
    suspend fun deleteStale(sourceId: String, gen: Long)

    @Query("SELECT * FROM albums WHERE sourceId = :sourceId")
    suspend fun bySource(sourceId: String): List<AlbumEntity>

    @Query("DELETE FROM albums WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("UPDATE albums SET starred = :starred, starredAt = :at WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean, at: Long)

    @Query("UPDATE albums SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: Int)

    @Query("UPDATE albums SET lastPlayed = :at WHERE id = :id")
    suspend fun markPlayed(id: String, at: Long)
}

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists WHERE isPrimary = 1 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    fun observeById(id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun byId(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE starred = 1 AND isPrimary = 1 ORDER BY starredAt DESC")
    fun observeStarred(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE isPrimary = 1 AND name LIKE '%' || :q || '%' ORDER BY CASE WHEN name LIKE :q || '%' THEN 0 ELSE 1 END, albumCount DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<ArtistEntity>

    @Upsert
    suspend fun upsert(items: List<ArtistEntity>)

    @Query("DELETE FROM artists WHERE sourceId = :sourceId AND syncGen != :gen")
    suspend fun deleteStale(sourceId: String, gen: Long)

    @Query("SELECT * FROM artists WHERE sourceId = :sourceId")
    suspend fun bySource(sourceId: String): List<ArtistEntity>

    @Query("DELETE FROM artists WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("UPDATE artists SET starred = :starred, starredAt = :at WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean, at: Long)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: String): PlaylistEntity?

    @Query(
        """SELECT s.* FROM playlist_entries e JOIN songs s ON s.id = e.songId
           WHERE e.playlistId = :id ORDER BY e.position"""
    )
    fun observeSongs(id: String): Flow<List<SongEntity>>

    @Query(
        """SELECT s.* FROM playlist_entries e JOIN songs s ON s.id = e.songId
           WHERE e.playlistId = :id ORDER BY e.position"""
    )
    suspend fun songs(id: String): List<SongEntity>

    @Query("SELECT songId FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    suspend fun entryIds(id: String): List<String>

    @Upsert
    suspend fun upsert(items: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE sourceId = :sourceId AND syncGen != :gen")
    suspend fun deleteStale(sourceId: String, gen: Long)

    @Query("SELECT * FROM playlists WHERE sourceId = :sourceId")
    suspend fun bySource(sourceId: String): List<PlaylistEntity>

    @Query("DELETE FROM playlists WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    suspend fun clearEntries(id: String)

    @Query("DELETE FROM playlist_entries WHERE playlistId NOT IN (SELECT id FROM playlists)")
    suspend fun deleteOrphanEntries()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(items: List<PlaylistEntryEntity>)

    @Transaction
    suspend fun replaceEntries(id: String, songIds: List<String>) {
        clearEntries(id)
        insertEntries(songIds.mapIndexed { i, s -> PlaylistEntryEntity(id, i, s) })
    }
}

@Dao
interface OfflinePinDao {
    @Query("SELECT * FROM offline_pins")
    fun observeAll(): Flow<List<OfflinePinEntity>>

    @Query("SELECT * FROM offline_pins")
    suspend fun all(): List<OfflinePinEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pin: OfflinePinEntity)

    @Query("DELETE FROM offline_pins WHERE kind = :kind AND targetId = :id")
    suspend fun delete(kind: String, id: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE songId = :id")
    suspend fun byId(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state = ${DownloadState.QUEUED} ORDER BY updatedAt LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state = ${DownloadState.QUEUED} ORDER BY updatedAt LIMIT :limit")
    suspend fun queued(limit: Int): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN (${DownloadState.QUEUED}, ${DownloadState.RUNNING})")
    suspend fun pendingCount(): Int

    @Query("SELECT songId FROM downloads WHERE state = ${DownloadState.DONE}")
    fun observeDoneIds(): Flow<List<String>>

    @Query(
        """SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId
           WHERE d.state = ${DownloadState.DONE} ORDER BY s.artist, s.album, s.disc, s.track"""
    )
    fun observeDownloadedSongs(): Flow<List<SongEntity>>

    @Upsert
    suspend fun upsert(item: DownloadEntity)

    @Upsert
    suspend fun upsertAll(items: List<DownloadEntity>)

    @Query("UPDATE downloads SET state = ${DownloadState.QUEUED} WHERE state = ${DownloadState.RUNNING} OR state = ${DownloadState.FAILED}")
    suspend fun requeueStuck()

    @Query("DELETE FROM downloads WHERE songId = :id")
    suspend fun delete(id: String)
}

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(e: PlayEventEntity): Long

    @Query("SELECT * FROM play_events WHERE startedAt >= :since")
    suspend fun since(since: Long): List<PlayEventEntity>

    @Query("SELECT DISTINCT songId FROM play_events WHERE startedAt >= :since")
    suspend fun songIdsSince(since: Long): List<String>

    @Query("SELECT COUNT(*) FROM play_events")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM play_events WHERE startedAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface PendingActionDao {
    @Insert
    suspend fun insert(a: PendingActionEntity)

    @Query("SELECT * FROM pending_actions ORDER BY id")
    suspend fun all(): List<PendingActionEntity>

    @Query("SELECT COUNT(*) FROM pending_actions")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_actions WHERE targetId = :targetId AND type IN (:types)")
    suspend fun deleteFor(targetId: String, types: List<String>)
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :id")
    suspend fun byId(id: String): LyricsEntity?

    @Upsert
    suspend fun upsert(l: LyricsEntity)
}

@Dao
interface SmartPlaylistDao {
    @Query("SELECT * FROM smart_playlists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SmartPlaylistEntity>>

    @Query("SELECT * FROM smart_playlists")
    suspend fun all(): List<SmartPlaylistEntity>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    fun observeById(id: Long): Flow<SmartPlaylistEntity?>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    suspend fun byId(id: Long): SmartPlaylistEntity?

    @Upsert
    suspend fun upsert(p: SmartPlaylistEntity): Long

    @Query("DELETE FROM smart_playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position")
    suspend fun all(): List<QueueItemEntity>

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Transaction
    suspend fun replace(ids: List<String>) {
        clear()
        insertAll(ids.mapIndexed { i, s -> QueueItemEntity(i, s) })
    }
}

@Dao
interface AudiobookDao {
    @Query("SELECT * FROM resume_points WHERE contextKey = :key")
    fun observeResume(key: String): Flow<ResumePointEntity?>

    @Query("SELECT * FROM resume_points WHERE contextKey = :key")
    suspend fun resume(key: String): ResumePointEntity?

    @Query("SELECT * FROM resume_points WHERE finished = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeInProgress(limit: Int): Flow<List<ResumePointEntity>>

    @Query("SELECT * FROM resume_points")
    suspend fun allResume(): List<ResumePointEntity>

    @Upsert
    suspend fun upsertResume(r: ResumePointEntity)

    @Query("DELETE FROM resume_points WHERE contextKey = :key")
    suspend fun deleteResume(key: String)

    @Query("SELECT * FROM bookmarks WHERE songId = :songId ORDER BY positionMs")
    fun observeBookmarks(songId: String): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun addBookmark(b: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}

@Dao
interface LocalPlaylistDao {
    @Query("SELECT * FROM local_playlists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM local_playlists ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlists WHERE id = :id")
    fun observeById(id: Long): Flow<LocalPlaylistEntity?>

    @Query("SELECT * FROM local_playlists WHERE id = :id")
    suspend fun byId(id: Long): LocalPlaylistEntity?

    @Query("SELECT * FROM local_playlist_entries WHERE playlistId = :id ORDER BY position")
    fun observeEntries(id: Long): Flow<List<LocalPlaylistEntryEntity>>

    @Query("SELECT * FROM local_playlist_entries WHERE playlistId = :id ORDER BY position")
    suspend fun entries(id: Long): List<LocalPlaylistEntryEntity>

    @Query("SELECT playlistId AS id, COUNT(*) AS n FROM local_playlist_entries GROUP BY playlistId")
    fun observeCounts(): Flow<List<IdCount>>

    @Insert
    suspend fun insert(p: LocalPlaylistEntity): Long

    @Query("UPDATE local_playlists SET name = :name, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: Long, name: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE local_playlists SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM local_playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM local_playlist_entries WHERE playlistId = :id")
    suspend fun clearEntries(id: Long)

    @Insert
    suspend fun insertEntries(items: List<LocalPlaylistEntryEntity>)

    @Transaction
    suspend fun replaceEntries(id: Long, items: List<Pair<String, String>>) {
        clearEntries(id)
        insertEntries(items.mapIndexed { i, (song, key) -> LocalPlaylistEntryEntity(id, i, song, key) })
        touch(id)
    }
}

/** Bulk writes of the deduper (one transaction per pass). */
@Dao
interface DedupDao {
    @Query("SELECT * FROM songs")
    suspend fun allSongs(): List<SongEntity>

    @Query("SELECT * FROM albums")
    suspend fun allAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM artists")
    suspend fun allArtists(): List<ArtistEntity>

    @Query("UPDATE songs SET mergeKey = :key, isPrimary = :primary, groupAlbumId = :album, groupArtistId = :artist WHERE id = :id")
    suspend fun markSong(id: String, key: String, primary: Boolean, album: String?, artist: String?)

    @Query("UPDATE albums SET mergeKey = :key, isPrimary = :primary, groupArtistId = :artist, songCount = :songs, duration = :duration WHERE id = :id")
    suspend fun markAlbum(id: String, key: String, primary: Boolean, artist: String?, songs: Int, duration: Int)

    @Query("UPDATE artists SET mergeKey = :key, isPrimary = :primary, albumCount = :albums WHERE id = :id")
    suspend fun markArtist(id: String, key: String, primary: Boolean, albums: Int)

    @Query("SELECT * FROM songs WHERE mergeKey = :key AND mergeKey != ''")
    suspend fun siblings(key: String): List<SongEntity>

    @Query("UPDATE songs SET starred = :starred, starredAt = :at WHERE mergeKey = :key AND mergeKey != ''")
    suspend fun starGroup(key: String, starred: Boolean, at: Long)

    @Query("UPDATE songs SET rating = :rating WHERE mergeKey = :key AND mergeKey != ''")
    suspend fun rateGroup(key: String, rating: Int)
}

@Dao
interface CueSplitDao {
    @Query("SELECT * FROM cue_splits")
    suspend fun all(): List<CueSplitEntity>

    @Query("SELECT parentId FROM cue_splits")
    suspend fun checkedIds(): List<String>

    @Query("SELECT parentId FROM cue_splits WHERE json != ''")
    suspend fun splitParents(): List<String>

    @Upsert
    suspend fun upsert(e: CueSplitEntity)
}
