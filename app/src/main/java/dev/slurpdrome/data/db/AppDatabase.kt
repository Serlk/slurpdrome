package dev.slurpdrome.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class, AlbumEntity::class, ArtistEntity::class,
        PlaylistEntity::class, PlaylistEntryEntity::class, OfflinePinEntity::class,
        DownloadEntity::class, PlayEventEntity::class, PendingActionEntity::class,
        LyricsEntity::class, SmartPlaylistEntity::class, QueueItemEntity::class,
        ResumePointEntity::class, BookmarkEntity::class,
        LocalPlaylistEntity::class, LocalPlaylistEntryEntity::class, CueSplitEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 2, to = 3), androidx.room.AutoMigration(from = 3, to = 4)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun albums(): AlbumDao
    abstract fun artists(): ArtistDao
    abstract fun playlists(): PlaylistDao
    abstract fun pins(): OfflinePinDao
    abstract fun downloads(): DownloadDao
    abstract fun playEvents(): PlayEventDao
    abstract fun pending(): PendingActionDao
    abstract fun lyrics(): LyricsDao
    abstract fun smartPlaylists(): SmartPlaylistDao
    abstract fun queue(): QueueDao
    abstract fun audiobooks(): AudiobookDao
    abstract fun localPlaylists(): LocalPlaylistDao
    abstract fun dedup(): DedupDao
    abstract fun cueSplits(): CueSplitDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "slurpdrome.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                // v2 namespaced every id by source (multi-server); old rows can't be mapped — resync.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }
}
