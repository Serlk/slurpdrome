package dev.slurpdrome.data.repo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.imageLoader
import coil.request.ImageRequest
import dev.slurpdrome.R
import dev.slurpdrome.SlurpApp
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.api.await
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.DownloadEntity
import dev.slurpdrome.data.db.DownloadState
import dev.slurpdrome.data.db.OfflinePinEntity
import dev.slurpdrome.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

object PinKind {
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
    const val ARTIST = "artist"
    const val SMART = "smart"
    const val LOCAL_PLAYLIST = "localplaylist"
}

/**
 * Explicit offline storage. A song is kept on disk when *any* rule wants it:
 *  - the user downloaded it by hand (manual flag),
 *  - it's a favourite and "auto-download favourites" is on (default: off),
 *  - it belongs to a pinned album / artist / playlist / smart playlist.
 * [reconcile] computes that set and queues/deletes files to match.
 */
class DownloadManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val smartPlaylists: SmartPlaylistRepository,
    scope: CoroutineScope,
) {
    private val reconcileMutex = Mutex()

    val downloads: StateFlow<Map<String, DownloadEntity>> = db.downloads().observeAll()
        .map { list -> list.associateBy { it.songId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val pins: StateFlow<Set<Pair<String, String>>> = db.pins().observeAll()
        .map { list -> list.map { it.kind to it.targetId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val musicDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "music").apply { mkdirs() }

    /** Local file for a song if fully downloaded — used by the player to bypass the network. */
    fun localFile(songId: String): File? {
        val d = downloads.value[Gid.parent(songId)] ?: return null
        if (d.state != DownloadState.DONE) return null
        return d.filePath?.let(::File)?.takeIf { it.exists() }
    }

    fun isPinned(kind: String, id: String) = (kind to id) in pins.value

    suspend fun download(songIdsIn: List<String>) {
        // Local files already live on the device; a CUE track downloads its whole image once.
        val songIds = songIdsIn.filterNot(Gid::isLocal).map(Gid::parent).distinct()
        if (songIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = downloads.value
        db.downloads().upsertAll(songIds.map { id ->
            val e = existing[id]
            if (e != null && e.state == DownloadState.DONE) e.copy(manual = true)
            else DownloadEntity(id, DownloadState.QUEUED, true, null, 0, 0, null, now)
        })
        schedule()
    }

    suspend fun remove(songIds: List<String>) {
        songIds.forEach { id ->
            db.downloads().byId(id)?.filePath?.let { File(it).delete() }
            db.downloads().delete(id)
        }
        // A rule may still want some of them — reconcile re-queues those.
        reconcile()
    }

    suspend fun setPinned(kind: String, id: String, pinned: Boolean) {
        if (pinned) db.pins().insert(OfflinePinEntity(kind, id)) else db.pins().delete(kind, id)
        reconcile()
        if (pinned) schedule(userInitiated = true)
    }

    suspend fun retryFailed() {
        db.downloads().requeueStuck()
        schedule()
    }

    suspend fun reconcile() = reconcileMutex.withLock {
        withContext(Dispatchers.IO) {
            val s = settings.current()
            val wanted = mutableSetOf<String>()
            if (s.autoDownloadFavorites) wanted += db.songs().starredIds()
            for (pin in db.pins().all()) {
                when (pin.kind) {
                    PinKind.ALBUM -> wanted += db.songs().byAlbum(pin.targetId).map { it.id }
                    PinKind.ARTIST -> wanted += db.songs().byArtist(pin.targetId).map { it.id }
                    PinKind.PLAYLIST -> wanted += db.playlists().entryIds(pin.targetId)
                    PinKind.SMART -> pin.targetId.toLongOrNull()?.let { wanted += smartPlaylists.evaluateIds(it) }
                    PinKind.LOCAL_PLAYLIST -> pin.targetId.toLongOrNull()?.let { id -> wanted += db.localPlaylists().entries(id).map { it.songId } }
                }
            }
            wanted.removeAll { Gid.isLocal(it) }
            val parents = wanted.filter(Gid::isVirtual).map(Gid::parent)
            wanted.removeAll(Gid::isVirtual); wanted.addAll(parents)
            val current = db.downloads().all().associateBy { it.songId }
            val now = System.currentTimeMillis()
            val toQueue = wanted.filter { it !in current }
                .map { DownloadEntity(it, DownloadState.QUEUED, false, null, 0, 0, null, now) }
            if (toQueue.isNotEmpty()) db.downloads().upsertAll(toQueue)

            current.values.filter { !it.manual && it.songId !in wanted }.forEach {
                it.filePath?.let { p -> File(p).delete() }
                db.downloads().delete(it.songId)
            }
        }
        schedule()
    }

    suspend fun clearAll() {
        db.downloads().all().forEach { it.filePath?.let { p -> File(p).delete() } ; db.downloads().delete(it.songId) }
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun usedBytes(): Long = musicDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** One-shot user override: download the current queue over mobile data too. */
    @Volatile var mobileAllowedOnce = false
        private set

    /**
     * Queues the worker. [userInitiated] (tap on Download / Keep offline) runs it as expedited work:
     * it starts at once and keeps going in the background with a notification, even if the app
     * is swiped away. Network constraint follows the settings (Wi‑Fi only / small files / any).
     */
    suspend fun schedule(userInitiated: Boolean = false, forceMobile: Boolean = false) {
        if (db.downloads().pendingCount() == 0) return
        if (forceMobile) mobileAllowedOnce = true
        val mode = settings.current().downloadNetwork
        val anyNetwork = forceMobile || mobileAllowedOnce || mode != dev.slurpdrome.data.prefs.DownloadNetwork.WIFI_ONLY
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (anyNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .apply { if (userInitiated || forceMobile) setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        // A waiting Wi‑Fi‑only job must be replaced when the user allows mobile data.
        val policy = if (forceMobile) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, req)
    }

    /** Queued items that can't start right now because we're on mobile data. */
    fun waitingForWifi(pending: Int, unmetered: Boolean): Boolean {
        if (pending == 0 || unmetered || mobileAllowedOnce) return false
        return runBlockingSettings().downloadNetwork != dev.slurpdrome.data.prefs.DownloadNetwork.ANY
    }

    private fun runBlockingSettings() = kotlinx.coroutines.runBlocking { settings.current() }

    internal fun finishedQueue() { mobileAllowedOnce = false }

    companion object { const val WORK = "downloads" }
}

class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val container get() = (applicationContext as SlurpApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(applicationContext.getString(R.string.notif_download_preparing), 0, 0)

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notif_download_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(total, done, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    override suspend fun doWork(): Result {
        val c = container
        val db: AppDatabase = c.db
        val http = c.downloadHttp
        val dm = c.downloads
        runCatching { setForeground(foreground(applicationContext.getString(R.string.notif_download_preparing), 0, 0)) }

        // Anything left RUNNING was interrupted by process death.
        db.downloads().all().filter { it.state == DownloadState.RUNNING }
            .forEach { db.downloads().upsert(it.copy(state = DownloadState.QUEUED)) }

        var done = 0
        val deferred = HashSet<String>()     // too big for mobile data right now
        while (!isStopped) {
            val item = db.downloads().queued(200).firstOrNull { it.songId !in deferred } ?: break
            val song = db.songs().byId(item.songId)
            if (song == null) { db.downloads().delete(item.songId); continue }
            val settings = c.settings.current()
            val metered = !c.network.state.value.unmetered
            if (metered && !dm.mobileAllowedOnce) {
                val mode = settings.downloadNetwork
                val estimate = if (settings.downloadOriginal) song.size else song.duration.toLong() * settings.downloadBitrate * 125
                val tooBig = mode == dev.slurpdrome.data.prefs.DownloadNetwork.WIFI_ONLY ||
                    (mode == dev.slurpdrome.data.prefs.DownloadNetwork.MOBILE_SMALL && estimate > settings.downloadMobileMaxMb * 1_048_576L)
                if (tooBig) { deferred += item.songId; continue }
            }
            val total = done + db.downloads().pendingCount()
            runCatching { setForeground(foreground("${song.artist} — ${song.title}", done, total)) }
            db.downloads().upsert(item.copy(state = DownloadState.RUNNING, updatedAt = System.currentTimeMillis()))

            val s = c.settings.current()
            val api = c.sources.apiFor(song.id)
            if (api == null) { db.downloads().delete(item.songId); continue }   // source gone / local
            val raw = Gid.raw(song.id)
            val url = if (s.downloadOriginal) api.downloadUrl(raw) else api.streamUrl(raw, s.downloadBitrate, s.downloadFormat)
            val ext = if (s.downloadOriginal) (song.suffix ?: "bin") else s.downloadFormat
            val safeName = song.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dm.musicDir, "$safeName.$ext")
            val tmp = File(dm.musicDir, "$safeName.part")
            try {
                http.newCall(Request.Builder().url(url).build()).await().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val len = body.contentLength()
                    // Navidrome replies with JSON on errors (e.g. transcoding misconfigured).
                    if (body.contentType()?.subtype == "json") throw IllegalStateException(body.string().take(200))
                    body.byteStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) } }
                    if (!tmp.renameTo(target)) throw IOException("rename failed")
                    db.downloads().upsert(
                        item.copy(
                            state = DownloadState.DONE, filePath = target.absolutePath,
                            bytes = target.length(), totalBytes = if (len > 0) len else target.length(),
                            error = null, updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                // Warm Coil's disk cache so artwork is available offline too.
                c.sources.artUrl(song.coverArt)?.let { u ->
                    runCatching { applicationContext.imageLoader.execute(ImageRequest.Builder(applicationContext).data(u).build()) }
                }
                done++
            } catch (e: IOException) {
                http.onStall()
                tmp.delete()
                Log.w("Download", "network error on ${song.id}", e)
                db.downloads().upsert(item.copy(state = DownloadState.QUEUED, updatedAt = System.currentTimeMillis()))
                return Result.retry()
            } catch (e: CancellationException) {
                tmp.delete()
                db.downloads().upsert(item.copy(state = DownloadState.QUEUED, updatedAt = System.currentTimeMillis()))
                throw e
            } catch (e: Exception) {
                tmp.delete()
                db.downloads().upsert(item.copy(state = DownloadState.FAILED, error = e.message, updatedAt = System.currentTimeMillis()))
            }
        }
        if (deferred.isNotEmpty()) {
            // Big files wait for Wi‑Fi: a follow-up job with an unmetered constraint picks them up.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "downloads-wifi", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build(),
            )
        } else dm.finishedQueue()
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
    }
}
