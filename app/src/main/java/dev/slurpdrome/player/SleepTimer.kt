package dev.slurpdrome.player

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SleepState(val endsAt: Long? = null, val endOfTrack: Boolean = false) {
    val active get() = endsAt != null || endOfTrack
}

/** Sleep timer with a 15-second fade-out. Lives in the app process, drives the service's player. */
class SleepTimer(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SleepState())
    val state: StateFlow<SleepState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile var player: Player? = null

    fun start(minutes: Int) {
        cancel()
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.value = SleepState(endsAt = end)
        job = scope.launch {
            val fadeMs = 15_000L
            delay((end - System.currentTimeMillis() - fadeMs).coerceAtLeast(0))
            withContext(Dispatchers.Main) {
                val p = player ?: return@withContext
                val startVol = p.volume
                val steps = 30
                try {
                    repeat(steps) { i ->
                        p.volume = startVol * (1f - (i + 1f) / steps)
                        delay(fadeMs / steps)
                    }
                    p.pause()
                } finally {
                    p.volume = startVol   // also restores volume if the timer is cancelled mid-fade
                }
            }
            _state.value = SleepState()
        }
    }

    fun startEndOfTrack() {
        cancel()
        _state.value = SleepState(endOfTrack = true)
    }

    /** Called by the service on track transitions. Returns true if playback should stop now. */
    fun consumeEndOfTrack(): Boolean {
        if (!_state.value.endOfTrack) return false
        _state.value = SleepState()
        return true
    }

    fun cancel() {
        job?.cancel(); job = null
        _state.value = SleepState()
    }
}
