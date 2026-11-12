package dev.slurpdrome.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.slurpdrome.SlurpApp
import dev.slurpdrome.data.db.PlayEventEntity
import dev.slurpdrome.data.prefs.ReplayGainMode
import dev.slurpdrome.ui.MainActivity
import dev.slurpdrome.widget.NowPlayingSnapshot
import kotlin.coroutines.coroutineContext
import dev.slurpdrome.widget.WidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val c get() = (application as SlurpApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The player driving the session. Swaps with [spare] at every crossfade. */
    private lateinit var player: ExoPlayer
    /** Second player: plays the incoming track during a crossfade, otherwise idle. */
    private lateinit var spare: ExoPlayer
    private val musicAttrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private lateinit var session: MediaLibrarySession
    private val tree by lazy { LibraryTree(c) }
    private var castPlayer: androidx.media3.cast.CastPlayer? = null
    private val casting get() = castPlayer != null && session.player === castPlayer
    private var dlnaPlayer: dev.slurpdrome.player.dlna.DlnaPlayer? = null
    /** Playback happens elsewhere (Chromecast or a DLNA renderer), not in our ExoPlayer. */
    private val remote get() = this::session.isInitialized && session.player !== player

    // ---- listening-session tracking ----
    private var current: Tracked? = null
    /** Song entity of the current item, used for audiobook logic. */
    private var currentSong: dev.slurpdrome.data.db.SongEntity? = null
    private var pausedAt = 0L
    private var consecutiveErrors = 0
    private var precacheJob: Job? = null
    private var radioJob: Job? = null

    private class Tracked(
        val songId: String,
        val artistId: String?,
        val albumId: String?,
        val genre: String?,
        val durationMs: Long,
        val startedAt: Long = System.currentTimeMillis(),
        var listenedMs: Long = 0,
        var scrobbled: Boolean = false,
    )

    override fun onCreate() {
        super.onCreate()
        player = buildPlayer(c.eqProcessors[0], focus = true)
        spare = buildPlayer(c.eqProcessors[1], focus = false)

        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(activityIntent)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(dev.slurpdrome.R.drawable.ic_notification) }
        )
        c.sleepTimer.player = player
        player.addListener(listener)

        // Track the output device so EQ profiles can follow the headphones.
        val am = getSystemService(android.media.AudioManager::class.java)
        c.eq.device.value = dev.slurpdrome.player.eq.EqRepository.currentOutput(am)
        am.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) {
                c.eq.device.value = dev.slurpdrome.player.eq.EqRepository.currentOutput(am)
            }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) {
                c.eq.device.value = dev.slurpdrome.player.eq.EqRepository.currentOutput(am)
            }
        }, null)

        scope.launch {
            c.settingsState.map { Triple(it.playbackSpeed, it.audiobookSpeed, it.skipSilence) }.distinctUntilChanged().collect { (_, _, skip) ->
                applySpeed()
                player.skipSilenceEnabled = skip
            }
        }
        setupCast()
        scope.launch { followDlna() }
        scope.launch { ticker() }
        scope.launch { crossfadeWatcher() }
        scope.launch { restoreQueue() }
    }

    /** ExoPlayer with our EQ inside the audio sink (before speed/silence). Only the main player handles audio focus. */
    private fun buildPlayer(eq: dev.slurpdrome.player.eq.EqAudioProcessor, focus: Boolean): ExoPlayer {
        val s = c.settingsState.value
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(eq))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(c.dataSourceFactory))
            .setAudioAttributes(musicAttrs, focus)
            .setHandleAudioBecomingNoisy(s.pauseOnDisconnect)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(s.seekStepSec * 1000L)
            .setSeekForwardIncrementMs(s.seekStepSec * 1000L)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        finishCurrent(completed = false, skipped = false)
        saveQueueNow()
        c.sleepTimer.player = null
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        dlnaPlayer?.release()
        scope.cancel()
        session.release()
        player.release()
        spare.release()
        super.onDestroy()
    }

    /** Library browsing (Android Auto) + resolving controller-sent stubs into playable items. */
    private inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(ImmutableList.copyOf(tree.children(parentId, page, pageSize)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch { session.notifySearchResultChanged(browser, query, tree.search(query).size, params) }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
        }

        /** A pick inside an album/playlist plays the whole context from that track. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val expanded = tree.expand(mediaItems)
            if (expanded != null) {
                val items = expanded.first.mapNotNull { if (casting) c.mediaItems.castItem(it) else c.mediaItems.playable(it) }
                MediaSession.MediaItemsWithStartPosition(items, expanded.second.coerceAtMost((items.size - 1).coerceAtLeast(0)), 0)
            } else {
                MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { resolve(mediaItems).toMutableList() }

        private suspend fun resolve(items: List<MediaItem>): List<MediaItem> {
            val ids = items.map { it.mediaId.substringAfterLast('§') }
            val songs = c.library.songsByIds(ids).associateBy { it.id }
            return ids.mapNotNull { id -> songs[id]?.let { if (casting) c.mediaItems.castItem(it) else c.mediaItems.playable(it) } }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || handover
            val prev = current
            val prevSong = currentSong
            // Finished the last chapter of a book (next item belongs to another album, or none).
            if (auto && prevSong != null && c.audiobooks.isAudiobook(prevSong)) {
                val nextAlbum = mediaItem?.mediaMetadata?.extras?.getString(MediaItemFactory.EXTRA_ALBUM_ID)
                c.scope.launch {
                    if (nextAlbum != prevSong.albumId) c.audiobooks.savePosition(prevSong, 0, finishedBook = true)
                }
            }
            if (prev != null && prev.songId != mediaItem?.mediaId || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                val frac = if (prev != null && prev.durationMs > 0) prev.listenedMs.toFloat() / prev.durationMs else 0f
                finishCurrent(completed = auto, skipped = !auto && frac < 0.5f)
            }
            if (auto && c.sleepTimer.consumeEndOfTrack()) {
                player.pause()
                player.seekTo(0)
            }
            mediaItem?.let { startTracking(it) }
            applyReplayGain(mediaItem)
            prepareCurrentFile(mediaItem)
            precacheUpcoming()
            maybeExtendRadio()
            saveQueueSoon()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                saveQueueSoon()
                precacheUpcoming()
                maybeExtendRadio()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val song = currentSong
            scope.launch { WidgetBridge.publish(this@PlaybackService, NowPlayingSnapshot(song, isPlaying)) }
            val book = c.audiobooks.isAudiobook(song)
            if (isPlaying) {
                consecutiveErrors = 0
                maybeExtendRadio()   // e.g. resumed on the last track of a restored queue
                player.currentMediaItem?.let { triedCopies.remove(it.mediaId) }
                // Back after a long pause in a book: rewind a little to regain context.
                val rewind = c.settingsState.value.audiobookRewindSec * 1000L
                if (book && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 5 * 60_000L && rewind > 0) {
                    player.seekTo((player.currentPosition - rewind).coerceAtLeast(0))
                }
                pausedAt = 0
            } else {
                pausedAt = System.currentTimeMillis()
                saveQueueSoon()
                if (book && song != null) {
                    val pos = player.currentPosition
                    c.scope.launch { c.audiobooks.savePosition(song, pos); c.audiobooks.pushServerBookmark(song, pos) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w("Playback", "error ${error.errorCodeName}", error)
            if (failover()) return
            // Unplayable (offline & uncached, deleted on server…) — move on instead of stalling.
            if (++consecutiveErrors <= 5 && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private fun startTracking(item: MediaItem) {
        scope.launch {
            val song = c.library.songsByIds(listOf(item.mediaId)).firstOrNull() ?: return@launch
            current = Tracked(song.id, song.artistId, song.albumId, song.genre, song.duration * 1000L)
            currentSong = song
            applySpeed()
            launch { WidgetBridge.publish(this@PlaybackService, NowPlayingSnapshot(song, player.isPlaying)) }
            val instance = item.mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_INSTANCE) ?: song.id
            val api = c.sources.apiFor(instance)
            if (api != null && c.settingsState.value.scrobble && c.network.state.value.online) {
                launch(Dispatchers.IO) {
                    runCatching { api.scrobble(dev.slurpdrome.data.source.Gid.serverRaw(instance), System.currentTimeMillis(), submission = false) }
                }
            }
        }
    }

    private fun finishCurrent(completed: Boolean, skipped: Boolean) {
        val t = current ?: return
        current = null
        if (t.listenedMs < 3_000 && !completed) return   // accidental taps aren't signal
        c.sessionHistory += t.songId to skipped
        c.scope.launch {
            c.db.playEvents().insert(
                PlayEventEntity(
                    songId = t.songId, artistId = t.artistId, albumId = t.albumId, genre = t.genre,
                    startedAt = t.startedAt, listenedMs = t.listenedMs, durationMs = t.durationMs,
                    skipped = skipped, completed = completed, source = null,
                )
            )
        }
    }

    /** 1 Hz: accumulates listened time (seeking doesn't count) and submits scrobbles. */
    private suspend fun ticker() {
        while (scope.isActive) {
            delay(1000)
            val t = current ?: continue
            if (!active.isPlaying) continue
            t.listenedMs += (1000 * active.playbackParameters.speed).toLong()
            val threshold = minOf(t.durationMs / 2, 240_000L).coerceAtLeast(30_000L)
            if (!t.scrobbled && t.listenedMs >= threshold) {
                t.scrobbled = true
                val at = t.startedAt
                c.scope.launch {
                    c.db.songs().markPlayed(t.songId, System.currentTimeMillis())
                    t.albumId?.let { c.db.albums().markPlayed(it, System.currentTimeMillis()) }
                    if (c.settingsState.value.scrobble) c.library.queueScrobble(t.songId, at)
                }
            }
            if (t.listenedMs % 10_000 < 1000) {
                saveQueueSoon()
                currentSong?.takeIf { c.audiobooks.isAudiobook(it) }?.let { song ->
                    val pos = player.currentPosition
                    c.scope.launch { c.audiobooks.savePosition(song, pos) }
                }
            }
        }
    }

    // ---------------- current file: full caching + waveform ----------------

    private var fileJob: Job? = null

    /**
     * Makes the whole current track local (so the bar can show real load progress and, once
     * complete, a loudness waveform). Local/downloaded files are complete immediately. Slices of
     * CUE images are skipped — caching a whole image just for a bar isn't worth it.
     */
    private fun prepareCurrentFile(item: MediaItem?) {
        fileJob?.cancel()
        val id = item?.mediaId
        c.trackFile.value = TrackFileState(id)
        if (item == null || remote) return
        val cfg = item.localConfiguration ?: return
        val clipped = item.clippingConfiguration.endPositionMs != C.TIME_END_OF_SOURCE
        val instance = item.mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_INSTANCE) ?: id!!
        fun publish(progress: Float, wave: FloatArray?) {
            val cur = c.trackFile.value
            if (cur.mediaId == id) c.trackFile.value = cur.copy(progress = progress, waveform = wave ?: cur.waveform)
        }
        fileJob = c.scope.launch(Dispatchers.IO) {
            when (cfg.uri.scheme) {
                "file", "content" -> {
                    publish(1f, null)
                    if (!clipped) c.waveforms.fromUri(instance, cfg.uri)?.let { publish(1f, it) }
                }
                else -> {
                    val key = cfg.customCacheKey ?: return@launch
                    c.waveforms.cached(instance)?.let { publish(1f, it); return@launch }
                    if (clipped) return@launch
                    val spec = DataSpec.Builder().setUri(cfg.uri).setKey(key).build()
                    var last = 0f
                    val writer = CacheWriter(c.cacheDataSourceFactory.createDataSource(), spec, null) { total, cached, _ ->
                        if (total > 0) {
                            val p = cached.toFloat() / total
                            if (p - last >= 0.02f || p >= 1f) { last = p; publish(p, null) }
                        }
                    }
                    coroutineContext[Job]?.invokeOnCompletion { if (it != null) writer.cancel() }
                    val ok = runCatching { writer.cache() }.isSuccess
                    if (!ok) return@launch
                    publish(1f, null)
                    c.waveforms.fromCache(instance, key)?.let { publish(1f, it) }
                }
            }
        }
    }

    // ---------------- multi-source failover ----------------

    private val triedCopies = HashMap<String, MutableSet<String>>()

    /**
     * The copy we were playing failed (server down, file gone): mark its source unhealthy and
     * swap in the same recording from another source at the same position. True if handled.
     */
    private fun failover(): Boolean {
        val item = player.currentMediaItem ?: return false
        val failed = item.mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_INSTANCE) ?: return false
        val tried = triedCopies.getOrPut(item.mediaId) { mutableSetOf() }.apply { add(failed) }
        if (tried.size > 4) return false
        c.sources.markDown(dev.slurpdrome.data.source.Gid.source(failed))
        val index = player.currentMediaItemIndex
        val pos = player.currentPosition
        scope.launch {
            val song = c.library.songsByIds(listOf(item.mediaId)).firstOrNull() ?: return@launch
            val replacement = c.mediaItems.playable(song, avoid = tried)
            val newInstance = replacement.mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_INSTANCE)
            if (newInstance == null || newInstance in tried) {
                if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
                return@launch
            }
            Log.i("Playback", "failover ${item.mediaId}: $failed → $newInstance")
            player.replaceMediaItem(index, replacement)
            player.seekTo(index, pos)
            player.prepare()
            player.play()
        }
        return true
    }

    // ---------------- Chromecast ----------------

    /** Hands the session over to a Cast device and back, carrying queue, index and position. */
    private fun setupCast() {
        val ctx = runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(this) }.getOrNull() ?: return
        val cp = androidx.media3.cast.CastPlayer(ctx)
        cp.setSessionAvailabilityListener(object : androidx.media3.cast.SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                c.dlna.active.value = null   // one remote at a time
                switchTo(cp)
            }
            override fun onCastSessionUnavailable() = switchTo(player)
        })
        castPlayer = cp
        c.castAvailable.value = true
        if (cp.isCastSessionAvailable) switchTo(cp)
    }

    // ---------------- DLNA ----------------

    /** Picking a renderer moves the queue there; picking "this phone" (null) brings it back. */
    private suspend fun followDlna() {
        c.dlna.active.collect { r ->
            val old = dlnaPlayer
            if (r == null) {
                if (old != null && session.player === old) switchTo(player) { old.release() }
                else old?.release()
                dlnaPlayer = null
                return@collect
            }
            if (old?.renderer?.udn == r.udn) return@collect
            val p = dev.slurpdrome.player.dlna.DlnaPlayer(this, mainLooper, c.dlna.client, r)
            dlnaPlayer = p
            switchTo(p) { old?.release() }
        }
    }

    private fun switchTo(target: Player, after: () -> Unit = {}) {
        val from = session.player
        if (from === target) return
        val ids = (0 until from.mediaItemCount).map { from.getMediaItemAt(it).mediaId }
        val currentId = from.currentMediaItem?.mediaId
        val pos = from.currentPosition
        val play = from.playWhenReady
        from.pause()
        scope.launch {
            val songs = c.library.songsByIds(ids)
            val toCast = target === castPlayer
            // Remote targets fetch the stream themselves: they need plain server URLs (local-only files are skipped).
            val toRemote = target !== player
            val pairs = songs.mapNotNull { s -> (if (toRemote) c.mediaItems.castItem(s) else c.mediaItems.playable(s))?.let { s.id to it } }
            val idx = pairs.indexOfFirst { it.first == currentId }.coerceAtLeast(0)
            session.player = target
            if (pairs.isNotEmpty()) {
                target.setMediaItems(pairs.map { it.second }, idx, if (pairs.getOrNull(idx)?.first == currentId) pos else 0)
                target.prepare()
                target.playWhenReady = play
            }
            if (from === player) from.clearMediaItems() else from.stop()
            c.castActive.value = toCast
            after()
        }
    }

    /** Books and podcasts get their own speed; music keeps the normal one. */
    private fun applySpeed() {
        val s = c.settingsState.value
        val speed = if (c.audiobooks.isAudiobook(currentSong)) s.audiobookSpeed else s.playbackSpeed
        if (player.playbackParameters.speed != speed) player.playbackParameters = PlaybackParameters(speed)
    }

    private fun applyReplayGain(item: MediaItem?) {
        val mode = c.settingsState.value.replayGain
        if (mode == ReplayGainMode.OFF || item == null) { mainGain = 1f; player.volume = fadeIn; return }
        scope.launch {
            val s = c.library.songsByIds(listOf(item.mediaId)).firstOrNull() ?: return@launch
            val gain = (if (mode == ReplayGainMode.ALBUM) s.albumGain ?: s.trackGain else s.trackGain ?: s.albumGain) ?: 0f
            // Amplification above unity would clip without a limiter, so only attenuate.
            mainGain = 10f.pow(gain / 20f).coerceIn(0.05f, 1f)
            player.volume = mainGain * fadeIn
        }
    }

    // ---------------- crossfade ----------------

    /** ReplayGain of the main player's track, and the fade-in factor applied on top of it. */
    private var mainGain = 1f
    private var fadeIn = 1f
    private var fading = false
    /** True while the incoming player takes over: its first item transition counts as "auto". */
    private var handover = false

    /** Starts a crossfade when the end of the current track gets within the configured window. */
    private suspend fun crossfadeWatcher() {
        while (scope.isActive) {
            delay(120)
            val sec = c.settingsState.value.crossfadeSec
            if (sec <= 0 || fading || remote) continue
            val p = player
            if (!p.isPlaying || p.repeatMode == Player.REPEAT_MODE_ONE || p.shuffleModeEnabled) continue
            val next = p.currentMediaItemIndex + 1
            if (next >= p.mediaItemCount) continue
            val window = sec * 1000L
            val dur = p.duration
            if (dur == C.TIME_UNSET || dur < window * 2 + 10_000) continue
            val remaining = ((dur - p.currentPosition) / p.playbackParameters.speed).toLong()
            if (remaining > window || remaining < 400) continue
            // Albums meant to flow into each other (live, concept, DJ mixes) stay gapless.
            val curAlbum = p.currentMediaItem?.mediaMetadata?.extras?.getString(MediaItemFactory.EXTRA_ALBUM_ID)
            val nextAlbum = p.getMediaItemAt(next).mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_ALBUM_ID)
            if (curAlbum != null && curAlbum == nextAlbum) continue
            if (c.audiobooks.isAudiobook(currentSong) || c.sleepTimer.state.value.endOfTrack) continue
            crossfade(remaining.coerceAtMost(window))
        }
    }

    /**
     * The spare player starts the next track silently and becomes the session's player right
     * away (notification, UI, Auto follow it); the old one fades out on the side and stops.
     * Equal-power curves keep the perceived loudness steady through the overlap.
     */
    private suspend fun crossfade(durationMs: Long) {
        val out = player
        val inc = spare
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        val next = out.currentMediaItemIndex + 1
        val outGain = mainGain
        fading = true
        try {
            out.removeListener(listener)
            out.setAudioAttributes(musicAttrs, false)
            inc.setAudioAttributes(musicAttrs, true)
            inc.repeatMode = out.repeatMode
            inc.playbackParameters = out.playbackParameters
            inc.skipSilenceEnabled = out.skipSilenceEnabled
            inc.volume = 0f
            fadeIn = 0f
            player = inc
            spare = out
            inc.addListener(listener)
            handover = true
            try { inc.setMediaItems(items, next, 0) } finally { handover = false }
            inc.prepare()
            inc.play()
            session.player = inc
            c.sleepTimer.player = inc
            Log.i("Playback", "crossfade ${durationMs} ms → ${items[next].mediaId}")

            // Don't start fading until the new track actually sounds (it's usually pre-cached).
            withTimeoutOrNull(3000) { while (!inc.isPlaying) delay(25) }
            val start = System.currentTimeMillis()
            while (true) {
                val t = ((System.currentTimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                // Paused, skipped or stopped meanwhile: drop the tail at once.
                if (player !== inc || !inc.playWhenReady || out.playbackState == Player.STATE_ENDED) break
                fadeIn = kotlin.math.sin(t * Math.PI / 2).toFloat()
                inc.volume = mainGain * fadeIn
                out.volume = outGain * kotlin.math.cos(t * Math.PI / 2).toFloat()
                if (t >= 1f) break
                delay(25)
            }
        } finally {
            fadeIn = 1f
            if (player === inc) inc.volume = mainGain
            out.stop()
            out.clearMediaItems()
            out.volume = 1f
            fading = false
        }
    }

    /** Fully caches the next two streamed tracks so short connectivity gaps don't interrupt playback. */
    private fun precacheUpcoming() {
        precacheJob?.cancel()
        val next = (1..2).mapNotNull { off ->
            val idx = player.currentMediaItemIndex + off
            if (idx < player.mediaItemCount) player.getMediaItemAt(idx) else null
        }.filter { it.localConfiguration?.uri?.scheme?.startsWith("http") == true }
        if (next.isEmpty()) return
        precacheJob = c.scope.launch(Dispatchers.IO) {
            for (item in next) {
                val cfg = item.localConfiguration ?: continue
                runCatching {
                    val spec = DataSpec.Builder().setUri(cfg.uri).setKey(cfg.customCacheKey).build()
                    CacheWriter(c.cacheDataSourceFactory.createDataSource(), spec, null, null).cache()
                }.onFailure { Log.d("Precache", "skip ${item.mediaId}: ${it.message}") }
            }
        }
    }

    /**
     * Endless playback. When ≤2 tracks are left: radio (if switched on) → more of the same mix →
     * nothing for the user's own playlists → otherwise radio seeded by this session. Everything
     * that has been in the queue is excluded, so continuations never repeat.
     */
    private fun maybeExtendRadio() {
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining >= 3 || radioJob?.isActive == true) return
        val context = c.player.playContext.value
        val mode = when {
            c.radioEnabled.value -> "radio"
            context is PlayContext.Mix -> "mix"
            context is PlayContext.Playlist -> return
            c.settingsState.value.autoplay -> "radio"
            else -> return
        }
        radioJob = scope.launch {
            val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val session = synchronized(c.sessionHistory) { c.sessionHistory.takeLast(30) }
            // Fresh process (restored queue): no session yet — seed from what's queued/playing.
            val history = session.ifEmpty {
                (0..player.currentMediaItemIndex).mapNotNull { i -> player.getMediaItemAt(i).mediaId.takeIf { it.isNotEmpty() }?.let { it to false } }.takeLast(10)
            }
            val exclude = queued + history.map { it.first }
            val ids = withContext(Dispatchers.Default) {
                val more = if (mode == "mix") c.mixes.extend((context as PlayContext.Mix).key, exclude) else emptyList()
                more.ifEmpty { c.mixes.continueRadio(history, exclude) }
            }.filter { it !in exclude }
            if (ids.isEmpty()) { Log.i("Playback", "continuation ($mode): nothing left to add"); return@launch }
            Log.i("Playback", "continuing ($mode): +${ids.size}")
            val items = c.library.songsByIds(ids).map { c.mediaItems.playable(it) }
            player.addMediaItems(items)
        }
    }

    // ---- queue persistence ----
    private var saveJob: Job? = null

    private fun saveQueueSoon() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(1500); saveQueueNow() }
    }

    /** The player actually driving playback (ExoPlayer, or CastPlayer while casting). */
    private val active: Player get() = if (this::session.isInitialized) session.player else player

    private fun saveQueueNow() {
        if (!this::player.isInitialized) return
        val p = active
        if (p.mediaItemCount == 0 && p !== player) return   // mid-handover: keep the last good queue
        val ids = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        val idx = p.currentMediaItemIndex
        val pos = p.currentPosition
        c.scope.launch {
            c.db.queue().replace(ids)
            c.settings.saveQueuePosition(idx, pos)
        }
    }

    private suspend fun restoreQueue() {
        if (!c.settingsState.value.resumeQueue || player.mediaItemCount > 0) return
        val ids = c.db.queue().all().map { it.songId }
        if (ids.isEmpty()) return
        val songs = c.library.songsByIds(ids)
        if (songs.isEmpty() || player.mediaItemCount > 0) return
        val (idx, pos) = c.settings.queuePosition()
        player.setMediaItems(songs.map { c.mediaItems.playable(it) }, idx.coerceIn(0, songs.lastIndex), pos)
        player.prepare()
        prepareCurrentFile(player.currentMediaItem)
    }
}
