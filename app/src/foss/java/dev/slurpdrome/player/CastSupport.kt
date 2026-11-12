package dev.slurpdrome.player

import android.content.Context
import androidx.media3.common.Player

/** F-Droid ("foss") build: no Google Play Services, so no Chromecast. DLNA output still works. */
object CastSupport {
    const val SUPPORTED = false
    fun create(context: Context, onAvailable: (Player) -> Unit, onUnavailable: () -> Unit): Player? = null
    fun isSessionAvailable(player: Player) = false
    fun release(player: Player) = player.release()
    fun pickDevice(context: Context) {}
    fun endSession(context: Context) {}
}
