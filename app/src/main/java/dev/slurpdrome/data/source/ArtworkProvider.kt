package dev.slurpdrome.data.source

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import dev.slurpdrome.SlurpApp
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

/**
 * content://dev.slurpdrome.artwork/<coverArtGid> — serves cover art to consumers that can't
 * carry our Subsonic auth (MediaSession notification, Android Auto, widgets) and exposes
 * embedded art of local files. Results are cached on disk.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val gid = uri.lastPathSegment ?: throw FileNotFoundException("no id")
        val dir = File(ctx.cacheDir, "art-provider").apply { mkdirs() }
        val file = File(dir, gid.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".img")
        if (!file.exists() || file.length() == 0L) {
            val bytes = (if (Gid.isLocal(gid)) localArt(Gid.raw(gid)) else remoteArt(gid))
                ?: throw FileNotFoundException(gid)
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun remoteArt(gid: String): ByteArray? {
        val c = (context?.applicationContext as? SlurpApp)?.container ?: return null
        val url = c.sources.artUrl(gid, 600) ?: return null
        return runCatching {
            c.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    private fun localArt(mediaId: String): ByteArray? {
        val ctx = context ?: return null
        val id = mediaId.toLongOrNull() ?: return null
        val audioUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, audioUri)
            r.embeddedPicture?.let { return it }
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val bmp = ctx.contentResolver.loadThumbnail(audioUri, Size(600, 600), null)
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
        return null
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "dev.slurpdrome.artwork"
        fun uri(coverArtGid: String): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(coverArtGid).build()
    }
}
