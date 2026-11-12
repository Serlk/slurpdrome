package dev.slurpdrome.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.slurpdrome.player.MediaItemFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A Media3 [Player] that plays on a DLNA/UPnP renderer. The queue lives here; the renderer gets
 * one track at a time plus the next one (SetNextAVTransportURI) so it can advance on its own even
 * while the phone sleeps. Renderers without "next" support are advanced when a track stops.
 *
 * All state is touched on the main looper only; network calls run serially on IO.
 */
@UnstableApi
class DlnaPlayer(
    context: Context,
    looper: Looper,
    private val client: UpnpClient,
    val renderer: Renderer,
) : SimpleBasePlayer(looper) {

    private class Entry(val uid: Long, val item: MediaItem)

    private val main = Handler(looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var items = listOf<Entry>()
    private var uidSeq = 0L
    private var index = 0
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var repeat = Player.REPEAT_MODE_OFF
    private var positionMs = 0L
    private var positionAt = SystemClock.elapsedRealtime()
    private var playing = false
    private var volume = 50
    private var muted = false
    private var error: PlaybackException? = null

    /** Entry currently set on the renderer, and whether it has been heard playing. */
    private var loadedUid: Long? = null
    private var sawPlaying = false
    private var rendererDurationMs = 0L
    /** What we've told the renderer to play next (it may switch to it by itself). */
    private var nextUid: Long? = null
    private var nextUri: String? = null
    private var failures = 0
    private var released = false

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slurpdrome:dlna").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION")
    private val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "slurpdrome:dlna").apply { setReferenceCounted(false) }

    init {
        remote { client.volume(renderer)?.let { v -> onMain { volume = v; invalidateState() } } }
        scope.launch { pollLoop() }
    }

    // ---------------- state ----------------

    override fun getState(): State {
        val playlist = items.map { e ->
            MediaItemData.Builder(e.uid)
                .setMediaItem(e.item)
                .setMediaMetadata(e.item.mediaMetadata)
                .setDurationUs(durationOf(e).takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val now = currentPosition()
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else playbackState)
            .setPlaylist(playlist)
            .apply { if (items.isNotEmpty()) setCurrentMediaItemIndex(index.coerceIn(0, items.lastIndex)) }
            .setContentPositionMs(if (playing) PositionSupplier.getExtrapolating(now, 1f) else PositionSupplier.getConstant(now))
            .setRepeatMode(repeat)
            .setDeviceInfo(DEVICE_INFO)
            .setDeviceVolume(volume)
            .setIsDeviceMuted(muted)
            .setPlayerError(error)
            .build()
    }

    private fun currentPosition(): Long =
        if (playing) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs

    private fun setPosition(ms: Long) { positionMs = ms.coerceAtLeast(0); positionAt = SystemClock.elapsedRealtime() }

    private fun durationOf(e: Entry): Long {
        val clip = e.item.clippingConfiguration
        if (clip.endPositionMs != C.TIME_END_OF_SOURCE) return clip.endPositionMs - clip.startPositionMs
        val extra = e.item.mediaMetadata.extras?.getLong(MediaItemFactory.EXTRA_DURATION) ?: 0L
        return if (extra > 0) extra else if (e.uid == loadedUid) rendererDurationMs else 0L
    }

    private fun clipStart(e: Entry) = e.item.clippingConfiguration.startPositionMs
    private fun clipped(e: Entry) = e.item.clippingConfiguration.startPositionMs > 0 ||
        e.item.clippingConfiguration.endPositionMs != C.TIME_END_OF_SOURCE
    private fun uriOf(e: Entry) = e.item.localConfiguration?.uri?.toString()
    private fun didlOf(e: Entry): String {
        val m = e.item.mediaMetadata
        return UpnpClient.didl(
            title = m.title?.toString() ?: "",
            artist = m.artist?.toString(),
            album = m.albumTitle?.toString(),
            artUrl = m.artworkUri?.toString(),
            uri = uriOf(e).orEmpty(),
            mime = e.item.localConfiguration?.mimeType ?: "audio/mpeg",
            durationMs = durationOf(e),
        )
    }

    // ---------------- renderer I/O ----------------

    private fun onMain(block: () -> Unit) { main.post { if (!released) block() } }

    /** Serialised network call; failures are reported back on the main thread. */
    private fun remote(quiet: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                runCatching { block() }.onFailure { e ->
                    Log.w(TAG, "${renderer.name}: ${e.message}")
                    if (!quiet) onMain { onRemoteFailure(e) }
                }
            }
        }
    }

    private fun onRemoteFailure(e: Throwable) {
        if (++failures >= 3) {
            error = PlaybackException("${renderer.name}: ${e.message}", e, PlaybackException.ERROR_CODE_REMOTE_ERROR)
            // Media3 requires IDLE alongside an error; play() clears it and reloads.
            playWhenReady = false; playing = false
            playbackState = Player.STATE_IDLE; loadedUid = null
            updateLocks()
        }
        invalidateState()
    }

    /** Puts the current entry on the renderer, optionally starting at [startMs]. */
    private fun load(startMs: Long) {
        val e = items.getOrNull(index) ?: return
        val uri = uriOf(e) ?: return
        loadedUid = e.uid; sawPlaying = false; rendererDurationMs = 0
        nextUid = null; nextUri = null
        playbackState = Player.STATE_BUFFERING
        playing = false
        setPosition(startMs)
        invalidateState()
        val play = playWhenReady
        val didl = didlOf(e)
        val seekTo = startMs + clipStart(e)
        remote {
            runCatching { client.stop(renderer) }        // some renderers refuse a new URI while playing
            client.setUri(renderer, uri, didl)
            if (play) client.play(renderer)
            if (seekTo > 0) {
                // Most renderers only accept Seek once the stream is open.
                for (attempt in 0 until 20) {
                    val s = runCatching { client.transportState(renderer) }.getOrNull()
                    if (s == "PLAYING" || s == "PAUSED_PLAYBACK") break
                    delay(250)
                }
                client.seek(renderer, seekTo)
            }
            onMain { failures = 0; error = null }
        }
    }

    /** Tells the renderer what comes next, so it can continue gaplessly on its own. */
    private fun scheduleNext() {
        val cur = items.getOrNull(index) ?: return
        val ni = nextIndex() ?: run {
            // Queue end: withdraw a stale "next" so the renderer doesn't run past it.
            if (nextUri != null) remote(quiet = true) { client.setNextUri(renderer, "", "") }
            nextUid = null; nextUri = null; return
        }
        if (ni == index) return   // repeat-one is handled on stop
        val ne = items[ni]
        if (clipped(cur) || clipped(ne)) return   // CUE slices need a seek, can't be queued
        if (ne.uid == nextUid) return
        val uri = uriOf(ne) ?: return
        nextUid = ne.uid; nextUri = uri
        val didl = didlOf(ne)
        remote(quiet = true) { client.setNextUri(renderer, uri, didl) }
    }

    private fun nextIndex(): Int? = when {
        items.isEmpty() -> null
        repeat == Player.REPEAT_MODE_ONE -> index
        index + 1 < items.size -> index + 1
        repeat == Player.REPEAT_MODE_ALL -> 0
        else -> null
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            delay(if (playWhenReady) 1000 else 3000)
            if (released) return
            val snapshot = loadedUid ?: continue
            val result = mutex.withLock {
                runCatching { client.transportState(renderer) to client.position(renderer) }.getOrNull()
            } ?: continue
            onMain { onPoll(snapshot, result.first, result.second) }
        }
    }

    private fun onPoll(polledUid: Long, transport: String?, pos: UpnpClient.Position) {
        if (polledUid != loadedUid) return
        // The renderer moved on to the track we queued with SetNextAVTransportURI.
        val nu = nextUri
        if (nu != null && pos.trackUri == nu) {
            val ni = items.indexOfFirst { it.uid == nextUid }
            if (ni >= 0) {
                index = ni; loadedUid = items[ni].uid; sawPlaying = true; rendererDurationMs = 0
                nextUid = null; nextUri = null
                Log.i(TAG, "${renderer.name} advanced by itself → $ni")
            }
        }
        val cur = items.getOrNull(index) ?: return
        val clipEnd = cur.item.clippingConfiguration.endPositionMs
        if (pos.durationMs > 0) rendererDurationMs = pos.durationMs
        when (transport) {
            "PLAYING" -> {
                sawPlaying = true; playing = true; playbackState = Player.STATE_READY
                setPosition(pos.positionMs - clipStart(cur))
                scheduleNext()
                if (clipEnd != C.TIME_END_OF_SOURCE && pos.positionMs >= clipEnd - 400) { onTrackEnded(); return }
            }
            "PAUSED_PLAYBACK" -> { playing = false; playbackState = Player.STATE_READY; setPosition(pos.positionMs - clipStart(cur)) }
            "TRANSITIONING" -> playbackState = Player.STATE_BUFFERING
            "STOPPED", "NO_MEDIA_PRESENT" -> {
                playing = false
                if (sawPlaying && playWhenReady) { onTrackEnded(); return }
                if (!playWhenReady) playbackState = Player.STATE_READY
            }
        }
        invalidateState()
    }

    private fun onTrackEnded() {
        val ni = nextIndex()
        if (ni == null) {
            playing = false; playbackState = Player.STATE_ENDED; loadedUid = null
            updateLocks()
            invalidateState()
            return
        }
        index = ni
        load(0)
    }

    private fun updateLocks() {
        val hold = playWhenReady && items.isNotEmpty() && !released
        runCatching {
            if (hold) { if (!wakeLock.isHeld) wakeLock.acquire(); if (!wifiLock.isHeld) wifiLock.acquire() }
            else { if (wakeLock.isHeld) wakeLock.release(); if (wifiLock.isHeld) wifiLock.release() }
        }
    }

    /** After queue edits: keep the renderer's "next" in line with ours. */
    private fun refreshNext() {
        if (loadedUid == null || !sawPlaying) return
        val want = nextIndex()?.let { items[it].uid }
        if (want != nextUid) { nextUid = null; nextUri = null; scheduleNext() }
    }

    // ---------------- Player commands ----------------

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        error = null; failures = 0
        updateLocks()
        val cur = items.getOrNull(index)
        when {
            cur == null -> {}
            playWhenReady && (loadedUid != cur.uid || playbackState == Player.STATE_ENDED) ->
                load(if (playbackState == Player.STATE_ENDED) 0 else positionMs)
            playWhenReady -> remote { client.play(renderer) }
            else -> { setPosition(currentPosition()); playing = false; remote { client.pause(renderer) } }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty() && loadedUid == null) load(positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playing = false; playbackState = Player.STATE_IDLE; loadedUid = null; nextUid = null; nextUri = null
        updateLocks()
        remote(quiet = true) { client.stop(renderer) }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        playWhenReady = false
        updateLocks()
        scope.launch { mutex.withLock { runCatching { client.stop(renderer) } }; scope.cancel() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.map { Entry(uidSeq++, it) }
        error = null; failures = 0
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val pos = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        loadedUid = null; nextUid = null; nextUri = null
        setPosition(pos); playing = false
        if (items.isEmpty()) playbackState = Player.STATE_IDLE
        else if (playbackState != Player.STATE_IDLE || playWhenReady) load(pos)
        updateLocks()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val wasEmpty = items.isEmpty()
        val at = index.coerceIn(0, items.size)
        items = items.toMutableList().apply { addAll(at, mediaItems.map { Entry(uidSeq++, it) }) }
        if (!wasEmpty && at <= this.index) this.index += mediaItems.size
        if (wasEmpty) this.index = 0
        refreshNext()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val curUid = items.getOrNull(index)?.uid
        val list = items.toMutableList()
        val moved = (fromIndex until toIndex).map { list.removeAt(fromIndex) }
        list.addAll(newIndex.coerceIn(0, list.size), moved)
        items = list
        index = items.indexOfFirst { it.uid == curUid }.coerceAtLeast(0)
        refreshNext()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val curUid = items.getOrNull(index)?.uid
        items = items.filterIndexed { i, _ -> i !in fromIndex until toIndex }
        val stillThere = items.indexOfFirst { it.uid == curUid }
        if (items.isEmpty()) {
            index = 0; loadedUid = null; playing = false; playbackState = Player.STATE_IDLE
            remote(quiet = true) { client.stop(renderer) }
        } else if (stillThere >= 0) {
            index = stillThere
        } else {
            index = fromIndex.coerceAtMost(items.lastIndex)
            if (playWhenReady) load(0) else { loadedUid = null; setPosition(0) }
        }
        refreshNext()
        updateLocks()
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val curUid = items.getOrNull(index)?.uid
        val list = items.toMutableList()
        repeat(toIndex - fromIndex) { list.removeAt(fromIndex) }
        list.addAll(fromIndex, mediaItems.map { Entry(uidSeq++, it) })
        items = list
        val stillThere = items.indexOfFirst { it.uid == curUid }
        if (stillThere >= 0) index = stillThere
        else if (items.isNotEmpty()) {
            index = index.coerceAtMost(items.lastIndex)
            if (loadedUid != null) load(currentPosition())
        }
        refreshNext()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        val target = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex.coerceIn(0, items.lastIndex)
        val cur = items[target]
        if (target != index || loadedUid != cur.uid || playbackState == Player.STATE_ENDED) {
            index = target
            if (playbackState == Player.STATE_IDLE && !playWhenReady) { setPosition(pos); loadedUid = null } else load(pos)
        } else {
            setPosition(pos)
            remote { client.seek(renderer, pos + clipStart(cur)) }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        refreshNext()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        volume = deviceVolume.coerceIn(0, 100)
        val v = volume
        remote(quiet = true) { client.setVolume(renderer, v) }
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int) = handleSetDeviceVolume(volume + VOLUME_STEP, flags)
    override fun handleDecreaseDeviceVolume(flags: Int) = handleSetDeviceVolume(volume - VOLUME_STEP, flags)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        this.muted = muted
        remote(quiet = true) { client.setMute(renderer, muted) }
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val TAG = "Dlna"
        private const val VOLUME_STEP = 5
        private val DEVICE_INFO = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()
        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).build()
    }
}
