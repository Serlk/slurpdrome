package dev.slurpdrome

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dev.slurpdrome.data.api.AdaptiveHttp
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.prefs.SettingsStore
import dev.slurpdrome.data.repo.AudiobookRepository
import dev.slurpdrome.data.repo.DownloadManager
import dev.slurpdrome.data.repo.LibraryRepository
import dev.slurpdrome.data.repo.LyricsRepository
import dev.slurpdrome.data.repo.MixEngine
import dev.slurpdrome.data.repo.NetworkMonitor
import dev.slurpdrome.data.repo.PlaylistRepository
import dev.slurpdrome.data.repo.SmartPlaylistRepository
import dev.slurpdrome.data.repo.SyncManager
import dev.slurpdrome.data.repo.SyncWorker
import dev.slurpdrome.data.source.Deduper
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.source.LocalScanner
import dev.slurpdrome.data.source.Source
import dev.slurpdrome.data.source.SourceKind
import dev.slurpdrome.data.source.SourceRegistry
import dev.slurpdrome.data.source.SourceStore
import dev.slurpdrome.player.MediaItemFactory
import dev.slurpdrome.player.PlayerConnection
import dev.slurpdrome.player.SleepTimer
import dev.slurpdrome.player.eq.EqAudioProcessor
import dev.slurpdrome.player.eq.EqRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class SlurpApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory { container.http }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(512L * 1024 * 1024).build() }
        .respectCacheHeaders(false)   // Navidrome art URLs are stable (fixed salt) — cache them aggressively.
        .crossfade(true)
        .apply { if (BuildConfig.DEBUG) logger(coil.util.DebugLogger(android.util.Log.WARN)) }
        .build()
}

/** Manual DI: one process-wide graph. */
@androidx.annotation.OptIn(UnstableApi::class)
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(app)
    val settingsState = settings.settings.stateIn(scope, SharingStarted.Eagerly, runBlocking { settings.current() })
    val db = AppDatabase.build(app)
    val network = NetworkMonitor(app)

    init {
        // v0.1 → multi-source: turn the old single login into source "s1".
        runBlocking {
            val legacy = settings.legacyCredentials()
            val store = SourceStore(app)
            if (legacy != null && store.current().none { it.isServer }) {
                store.upsert(Source("s1", SourceKind.SERVER, legacy.baseUrl.substringAfter("://"), legacy.baseUrl, legacy.username, legacy.token, legacy.salt))
                settings.clearLegacyCredentials()
                settings.resetLastSync()
            }
        }
    }

    private val freshConnections = java.util.concurrent.atomic.AtomicBoolean(false)
    private val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val http = AdaptiveHttp(baseHttp, freshConnections)
    val downloadHttp = AdaptiveHttp(baseHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).build(), freshConnections)

    val sourceStore = SourceStore(app)
    val sources = SourceRegistry(sourceStore, http, scope)
    val localScanner = LocalScanner(app, db)

    /** True once at least one source (server or local folders) is configured. */
    val hasSources: StateFlow<Boolean> = sources.sources.map { it.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, sources.sources.value.isNotEmpty())

    val library = LibraryRepository(db, sources, scope)
    val smartPlaylists = SmartPlaylistRepository(db)
    val downloads = DownloadManager(app, db, settings, smartPlaylists, scope)
    val deduper = Deduper(db, settingsState, sources)
    val cue = dev.slurpdrome.data.source.CueSplitter(db, sources, http)
    val sync: SyncManager = SyncManager(app, db, sources, localScanner, settings, library) {
        runCatching { cue.scan() }; cue.materialize(); deduper.run(); downloads.reconcile(); mixes.invalidate(); audiobooks.pullServerBookmarks()
    }
    val playlists = PlaylistRepository(app, db, sources, { sync }, { downloads })
    val lyrics = LyricsRepository(db, sources)
    val localPlaylists = dev.slurpdrome.data.repo.LocalPlaylistRepository(db, sources, library)
    val mixes = MixEngine(app, db, sources, settings, library)
    val audiobooks by lazy { AudiobookRepository(db, sources, settingsState) }


    // ---- playback infrastructure (shared by service and UI) ----
    val streamCache: SimpleCache by lazy {
        val mb = settingsState.value.streamCacheMb.coerceAtLeast(64)
        SimpleCache(
            File(app.cacheDir, "stream"),
            LeastRecentlyUsedCacheEvictor(mb * 1024L * 1024L),
            StandaloneDatabaseProvider(app),
        )
    }

    val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** file:// goes straight to disk, http(s) goes through the LRU stream cache. */
    val dataSourceFactory: DataSource.Factory by lazy { DefaultDataSource.Factory(app, cacheDataSourceFactory) }

    val mediaItems = MediaItemFactory(sources, downloads, network, { library }, { settingsState.value })
    val player = PlayerConnection(app, library, mediaItems, scope)
    val sleepTimer = SleepTimer(scope)

    // ---- equalizer (DSP lives in the player's audio pipeline) ----
    val eq = EqRepository(app, http, settingsState, scope)
    /** One EQ instance per ExoPlayer (two players overlap during crossfade); both follow the same profile. */
    val eqProcessors = listOf(EqAudioProcessor(), EqAudioProcessor())
    fun applyEq(profile: dev.slurpdrome.player.eq.EqProfile?, on: Boolean) = eqProcessors.forEach { it.profile = profile; it.enabled = on }

    /** Load progress + loudness profile of the current track (drives the seek bar visualiser). */
    val trackFile = MutableStateFlow(dev.slurpdrome.player.TrackFileState())
    val waveforms by lazy { dev.slurpdrome.player.WaveformExtractor(app) { streamCache } }

    /** Chromecast: framework available on this device / a cast session is driving playback. */
    val castAvailable = MutableStateFlow(false)
    val castActive = MutableStateFlow(false)

    /** Endless radio: keep extending the queue with an adaptive instant mix. */
    val radioEnabled = MutableStateFlow(false)
    /** (songId, skipped) of this listening session — steers radio continuation. */
    val sessionHistory = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())

    fun start() {
        // Re-merge when the merge toggle or source priority changes (no network needed).
        scope.launch {
            settingsState.map { it.mergeDuplicates to it.sourcePriority }.distinctUntilChanged().drop(1).collectLatest {
                deduper.run(); mixes.invalidate()
            }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(eq.active, settingsState.map { it.eqEnabled }) { p, on -> p to on }
                .collect { (p, on) -> applyEq(p, on) }
        }
        sync.requester = { SyncWorker.runNow(app) }
        scope.launch {
            settingsState.map { it.autoSyncHours }.distinctUntilChanged().collectLatest { h ->
                if (hasSources.value) SyncWorker.schedulePeriodic(app, h)
            }
        }
        // Flush offline actions whenever we come back online.
        scope.launch {
            network.state.map { it.online }.distinctUntilChanged().filter { it }.collectLatest {
                if (sources.servers.isNotEmpty()) {
                    library.flushPending()
                    downloads.schedule()
                }
            }
        }
        // "Auto-download favourites" — reconcile when the toggle or a favourite changes.
        scope.launch {
            settingsState.map { it.autoDownloadFavorites }.distinctUntilChanged().drop(1).collectLatest { downloads.reconcile() }
        }
        scope.launch {
            library.favoriteChanged.collectLatest {
                dev.slurpdrome.widget.WidgetBridge.state.value.song?.let { cur ->
                    library.songsByIds(listOf(cur.id)).firstOrNull()?.let { fresh ->
                        dev.slurpdrome.widget.WidgetBridge.publish(app, dev.slurpdrome.widget.WidgetBridge.state.value.copy(song = fresh))
                    }
                }
                if (settingsState.value.autoDownloadFavorites) downloads.reconcile()
            }
        }
        scope.launch { audiobooks.pruneNonBooks() }
        scope.launch {
            if (hasSources.value) {
                downloads.schedule()
                sync.syncIfStale()
            }
        }
    }

    /** Adds or updates a source and syncs just that one. */
    suspend fun saveSource(source: Source) {
        sourceStore.upsert(source)
        sources.forget(source.id)
        SyncWorker.schedulePeriodic(app, settingsState.value.autoSyncHours)
        kotlinx.coroutines.delay(100)   // let the registry flow observe the new source
        sync.request()
    }

    /** Removes a source and everything it contributed (songs, playlists, downloads). */
    suspend fun removeSource(id: String) {
        player.stop()
        sourceStore.remove(id)
        sources.forget(id)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            db.downloads().all().filter { Gid.source(it.songId) == id }.forEach { d ->
                d.filePath?.let { File(it).delete() }
                db.downloads().delete(d.songId)
            }
            sync.purge(id)
        }
        mixes.invalidate()
        if (sourceStore.current().isEmpty()) {
            SyncWorker.cancelAll(app)
            kotlinx.coroutines.withContext(Dispatchers.IO) { db.clearAllTables() }
            runCatching { streamCache.keys.toList().forEach { streamCache.removeResource(it) } }
        }
    }
}
