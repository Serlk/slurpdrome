package dev.slurpdrome.data.api

import android.util.Log
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Some reverse proxies / tunnels in front of self-hosted Navidrome silently break *reused*
 * connections: the first request on a connection works, later ones stall until timeout.
 * Pooled connections are the fast default; after the first stall we switch every client
 * sharing [fresh] (API, streaming, artwork, downloads) to one-connection-per-request.
 */
class AdaptiveHttp(base: OkHttpClient, private val fresh: AtomicBoolean) : Call.Factory {
    private val pooled = base
    private val unpooled: OkHttpClient by lazy {
        base.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    }

    val freshConnections: Boolean get() = fresh.get()

    override fun newCall(request: Request): Call = (if (fresh.get()) unpooled else pooled).newCall(request)

    /** Called on a timeout/reset: drop the poisoned pool and stop reusing connections. */
    fun onStall() {
        if (fresh.compareAndSet(false, true)) Log.i("Http", "connection reuse looks broken — switching to fresh connections")
        pooled.connectionPool.evictAll()
    }
}
