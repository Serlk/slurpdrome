package dev.slurpdrome.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Finds DLNA renderers on the Wi-Fi and holds the one the user picked (the service follows [active]). */
class DlnaManager(private val context: Context) {
    private val cm = context.getSystemService(android.net.ConnectivityManager::class.java)

    /** The Wi-Fi/Ethernet network: renderers live on the LAN, even when a VPN owns the default route. */
    private fun lan(): android.net.Network? = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }

    /** Sockets are created on the LAN network at connect time (it may change while we run). */
    private val lanSockets = object : javax.net.SocketFactory() {
        private fun f() = lan()?.socketFactory ?: getDefault()
        override fun createSocket() = f().createSocket()
        override fun createSocket(host: String?, port: Int) = f().createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) = f().createSocket(host, port, localHost, localPort)
        override fun createSocket(host: java.net.InetAddress?, port: Int) = f().createSocket(host, port)
        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int) = f().createSocket(address, port, localAddress, localPort)
    }

    val client = UpnpClient(
        OkHttpClient.Builder().socketFactory(lanSockets).connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    )
    val devices = MutableStateFlow<List<Renderer>>(emptyList())
    val scanning = MutableStateFlow(false)
    /** Renderer to play on; null = this phone. */
    val active = MutableStateFlow<Renderer?>(null)

    suspend fun scan() {
        if (scanning.value) return
        scanning.value = true
        @Suppress("DEPRECATION")
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("slurpdrome:ssdp").apply { setReferenceCounted(false) }
        try {
            runCatching { lock.acquire() }
            val locations = withContext(Dispatchers.IO) { runCatching { Ssdp.search(3500) { s -> lan()?.bindSocket(s) } }.getOrDefault(emptySet()) }
            val found = coroutineScope {
                locations.map { loc -> async(Dispatchers.IO) { runCatching { client.describe(loc) }.onFailure { Log.d("Dlna", "describe $loc: ${it.message}") }.getOrNull() } }.awaitAll()
            }.filterNotNull().distinctBy { it.udn }.sortedBy { it.name.lowercase() }
            // Keep the active device listed even if it didn't answer this round.
            devices.value = (found + listOfNotNull(active.value)).distinctBy { it.udn }
            Log.i("Dlna", "found ${found.size}: ${found.joinToString { it.name }}")
        } finally {
            runCatching { if (lock.isHeld) lock.release() }
            scanning.value = false
        }
    }
}
