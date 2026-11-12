package dev.slurpdrome.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetState(val online: Boolean, val unmetered: Boolean)

/**
 * Tracks the default network *as this app sees it*. Android blocks network for backgrounded
 * apps; when the block lifts (app returns to foreground) only onBlockedStatusChanged fires,
 * so it has to be handled or the app stays "offline" forever.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(read(cm.activeNetwork, blocked = false))
    val state: StateFlow<NetState> = _state.asStateFlow()
    @Volatile private var blocked = false
    @Volatile private var current: Network? = cm.activeNetwork

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { current = network; publish() }
            override fun onLost(network: Network) { if (current == network) current = null; publish() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { current = network; publish() }
            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) { current = network; blocked = isBlocked; publish() }
        })
    }

    /** Re-read on demand (e.g. app came to foreground). */
    fun refresh() { current = cm.activeNetwork ?: current; publish() }

    private fun publish() { _state.value = read(current, blocked) }

    private fun read(network: Network?, blocked: Boolean): NetState {
        val caps = network?.let { cm.getNetworkCapabilities(it) } ?: return NetState(online = false, unmetered = false)
        val online = !blocked && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return NetState(online, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}
