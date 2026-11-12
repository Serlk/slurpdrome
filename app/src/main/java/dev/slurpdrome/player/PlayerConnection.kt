package dev.slurpdrome.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.repo.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the current queue came from — decides what happens when it runs out. */
sealed interface PlayContext {
    /** Album, artist, search, favourites…: continue with radio. */
    data object Other : PlayContext
    /** The user's own playlist (device, server or smart): stop at the end. */
    data object Playlist : PlayContext
    /** A generated mix: keep extending the same mix. */
    data class Mix(val key: String) : PlayContext
}

data class PlayerUiState(
    val connected: Boolean = false,
    val currentId: String? = null,
    val current: SongEntity? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val index: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffled: Boolean = false,
    val queueIds: List<String> = emptyList(),
)

/**
 * UI-side facade over the MediaController. All state is exposed as flows; position is a separate
 * high-frequency flow so that only the seek bar / lyrics recompose every tick.
 */
class PlayerConnection(
    private val context: Context,
    private val library: LibraryRepository,
    private val items: MediaItemFactory,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private val connectMutex = Mutex()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val queueIds = MutableStateFlow<List<String>>(emptyList())

    /** Queue rows observed from Room, so favourite/rating/download changes show up immediately. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val queue: StateFlow<List<SongEntity>> = queueIds
        .flatMapLatest { ids -> library.observeSongsByIds(ids) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Keep state.current live as well (♥ in the player, F hotkey toggling correctly).
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        scope.launch {
            _state.map { it.currentId }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else library.song(id) }
                .collect { song ->
                    val st = _state.value
                    if (song == null || song.id == st.currentId) {
                        _state.value = st.copy(
                            current = song,
                            durationMs = st.durationMs.takeIf { it > 0 } ?: ((song?.duration ?: 0) * 1000L),
                        )
                    }
                }
        }
    }

    private var unshuffledOrder: List<String>? = null
    private var positionJob: Job? = null

    suspend fun connect(): MediaController = connectMutex.withLock {
        controller?.let { return it }
        withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = MediaController.Builder(context, token).buildAsync().await()
            c.addListener(listener)
            controller = c
            refresh()
            c
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch(Dispatchers.Main) { block(connect()) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
    }

    private fun refresh() {
        val c = controller ?: return
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val currentId = c.currentMediaItem?.mediaId
        val old = _state.value
        _state.value = old.copy(
            connected = true,
            currentId = currentId,
            // Keep showing the previous song until the new one loads from Room (a few ms): dropping to
            // null would hide the whole player sheet for a frame and flash the screen behind it.
            current = if (currentId == null) null else old.current,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it > 0 } ?: ((old.current?.takeIf { it.id == currentId }?.duration ?: 0) * 1000L),
            index = c.currentMediaItemIndex,
            repeatMode = c.repeatMode,
            shuffled = unshuffledOrder != null,
            queueIds = ids,
        )
        _position.value = c.currentPosition
        if (old.queueIds != ids) queueIds.value = ids
        if (c.isPlaying) startPositionTicker() else positionJob?.cancel()
    }

    private fun startPositionTicker() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main) {
            while (true) {
                controller?.let { _position.value = it.currentPosition }
                delay(250)
            }
        }
    }

    // ---------------- commands ----------------

    val playContext = MutableStateFlow<PlayContext>(PlayContext.Other)

    fun play(
        songs: List<SongEntity>, startIndex: Int = 0, shuffle: Boolean = false, startPositionMs: Long = 0,
        context: PlayContext = PlayContext.Other,
    ) {
        if (songs.isEmpty()) return
        playContext.value = context
        var list = songs
        var start = startIndex.coerceIn(0, songs.lastIndex)
        unshuffledOrder = null
        if (shuffle) {
            unshuffledOrder = songs.map { it.id }
            list = songs.shuffled()
            start = 0
        }
        withController { c ->
            c.setMediaItems(list.map(items::stub), start, if (shuffle) 0 else startPositionMs)
            c.prepare()
            c.play()
        }
    }

    fun playIds(ids: List<String>, startIndex: Int = 0, shuffle: Boolean = false, context: PlayContext = PlayContext.Other) {
        scope.launch { play(library.songsByIds(ids), startIndex, shuffle, context = context) }
    }

    fun playNext(songs: List<SongEntity>) = withController { c ->
        if (c.mediaItemCount == 0) { play(songs); return@withController }
        c.addMediaItems(c.currentMediaItemIndex + 1, songs.map(items::stub))
    }

    fun addToQueue(songs: List<SongEntity>) = withController { c ->
        if (c.mediaItemCount == 0) { play(songs); return@withController }
        c.addMediaItems(songs.map(items::stub))
    }

    fun togglePlay() = withController { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = withController { it.seekToNext() }

    /** Classic behaviour: restart the track if > 3 s in, otherwise go to previous. */
    fun previous() = withController { it.seekToPrevious() }

    /** Strictly the previous track (swipe gesture), never "restart current". */
    fun previousTrack() = withController { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) }

    fun seekTo(ms: Long) = withController { it.seekTo(ms); _position.value = ms }

    fun seekBy(deltaMs: Long) = withController { c ->
        val target = (c.currentPosition + deltaMs).coerceIn(0, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        c.seekTo(target); _position.value = target
    }

    fun skipTo(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) { c.seekToDefaultPosition(index); c.play() }
    }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * Physical shuffle of everything after the current track (what you see in the queue is what plays).
     * Toggling again restores the original order.
     */
    fun toggleShuffle() = withController { c ->
        val count = c.mediaItemCount
        if (count < 2) return@withController
        val cur = c.currentMediaItemIndex
        val currentIds = (0 until count).map { c.getMediaItemAt(it).mediaId }
        val restore = unshuffledOrder
        val tailIds: List<String>
        if (restore == null) {
            unshuffledOrder = currentIds
            tailIds = currentIds.drop(cur + 1).shuffled()
        } else {
            unshuffledOrder = null
            val tail = currentIds.drop(cur + 1).toSet()
            tailIds = restore.filter { it in tail } + currentIds.drop(cur + 1).filter { it !in restore }
        }
        val itemsById = (0 until count).associate { c.getMediaItemAt(it).mediaId to c.getMediaItemAt(it) }
        val tailItems: List<MediaItem> = tailIds.mapNotNull { itemsById[it] }
        if (cur + 1 < count) c.removeMediaItems(cur + 1, count)
        c.addMediaItems(tailItems.map { MediaItem.Builder().setMediaId(it.mediaId).setMediaMetadata(it.mediaMetadata).build() })
        refresh()
    }

    fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun removeQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun clearUpcoming() = withController { c ->
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
    }

    fun stop() = withController { c -> c.stop(); c.clearMediaItems() }
}
