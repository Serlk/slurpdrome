package dev.slurpdrome.player

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import com.google.android.gms.cast.framework.CastContext

/** Chromecast via Google Play Services — "full" flavor only (F-Droid builds use the stub in src/foss). */
object CastSupport {
    const val SUPPORTED = true

    /** A CastPlayer wired to session availability, or null when Play Services are missing. */
    fun create(context: Context, onAvailable: (Player) -> Unit, onUnavailable: () -> Unit): Player? {
        val ctx = runCatching { CastContext.getSharedInstance(context) }.getOrNull() ?: return null
        val cp = CastPlayer(ctx)
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = onAvailable(cp)
            override fun onCastSessionUnavailable() = onUnavailable()
        })
        return cp
    }

    fun isSessionAvailable(player: Player) = (player as? CastPlayer)?.isCastSessionAvailable == true

    fun release(player: Player) {
        (player as? CastPlayer)?.setSessionAvailabilityListener(null)
        player.release()
    }

    /** Standard route chooser for picking a Cast device. */
    fun pickDevice(context: Context) {
        val selector = runCatching { CastContext.getSharedInstance(context).mergedSelector }.getOrNull() ?: return
        val dialog = androidx.mediarouter.app.MediaRouteChooserDialog(ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog))
        dialog.routeSelector = selector
        dialog.show()
    }

    fun endSession(context: Context) {
        runCatching { CastContext.getSharedInstance(context).sessionManager.endCurrentSession(true) }
    }
}
