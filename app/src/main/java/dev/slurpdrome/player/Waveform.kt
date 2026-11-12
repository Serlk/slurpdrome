package dev.slurpdrome.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** What the player bar shows for the current track. */
data class TrackFileState(
    val mediaId: String? = null,
    /** 0..1 share of the file that is on the device (cache / download / local). */
    val progress: Float = 0f,
    /** Peak levels 0..1 per bin, once the whole file is local. */
    val waveform: FloatArray? = null,
)

/**
 * Computes a compact loudness profile (peak per time bin) by decoding the audio once with the
 * platform decoder. Results are cached on disk, so each file is analysed only once.
 */
@UnstableApi
class WaveformExtractor(private val context: Context, private val cache: () -> SimpleCache) {
    private val dir = File(context.cacheDir, "waveforms").apply { mkdirs() }

    fun cached(key: String): FloatArray? = file(key).takeIf { it.exists() }?.let { f ->
        runCatching {
            val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(b.remaining() / 4) { b.float }
        }.getOrNull()
    }

    /** From a file:// or content:// uri (local music, downloads). */
    suspend fun fromUri(key: String, uri: Uri): FloatArray? = compute(key) { it.setDataSource(context, uri, null) }

    /** From a fully cached stream: reads the cache span files directly (no network). */
    suspend fun fromCache(key: String, cacheKey: String): FloatArray? {
        val c = cache()
        val length = ContentMetadata.getContentLength(c.getContentMetadata(cacheKey))
        if (length <= 0 || !c.isCached(cacheKey, 0, length)) return null
        return compute(key) { it.setDataSource(CacheSpanDataSource(c, cacheKey, length)) }
    }

    private suspend fun compute(key: String, open: (MediaExtractor) -> Unit): FloatArray? = withContext(Dispatchers.Default) {
        cached(key)?.let { return@withContext it }
        val t0 = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            open(extractor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return@withContext null
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            // Sampled decoding: seek to the middle of each bin and decode a few frames there.
            // ~BINS × 4 frames instead of the whole file → about a second regardless of length.
            val rms = FloatArray(BINS)
            val info = MediaCodec.BufferInfo()
            var floatPcm = false
            var rate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            // ~200 ms of audio per point, whatever the codec's frame size (MP3 26 ms, FLAC ~93 ms).
            val wantSamples = { rate / 5 }
            for (bin in 0 until BINS) {
                coroutineContext.ensureActive()
                val t = ((bin + 0.5) / BINS * durationUs).toLong()
                extractor.seekTo(t, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                var inputDone = false; var fed = 0; var guard = 0
                var frames = 0L
                var sumSq = 0.0; var n = 0L
                while (frames < wantSamples() && guard < 200) {
                    guard++
                    if (!inputDone && fed < 40) {
                        val i = codec.dequeueInputBuffer(1_000)
                        if (i >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance(); fed++ }
                        }
                    }
                    when (val o = codec.dequeueOutputBuffer(info, 1_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            floatPcm = f.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, 2) == 4   // ENCODING_PCM_FLOAT
                            rate = f.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, rate)
                            channels = f.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        }
                        else -> if (o >= 0) {
                            val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            out.position(info.offset); out.limit(info.offset + info.size)
                            if (floatPcm) {
                                val fb = out.asFloatBuffer()
                                var k = 0; while (k < fb.limit()) { val v = fb.get(k).toDouble(); sumSq += v * v; n++; k += 2 }
                                frames += fb.limit() / channels.coerceAtLeast(1)
                            } else {
                                val sb = out.asShortBuffer()
                                var k = 0; while (k < sb.limit()) { val v = sb.get(k) / 32768.0; sumSq += v * v; n++; k += 2 }
                                frames += sb.limit() / channels.coerceAtLeast(1)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                rms[bin] = if (n > 0) sqrt(sumSq / n).toFloat() else 0f
            }
            val result = shape(rms)
            Log.i("Waveform", "$key: ${result.count { it > 0.9f } * 100 / BINS}% bins >0.9, ${result.count { it < 0.5f } * 100 / BINS}% <0.5")
            file(key).writeBytes(java.nio.ByteBuffer.allocate(BINS * 4).order(ByteOrder.LITTLE_ENDIAN).apply { result.forEach { putFloat(it) } }.array())
            Log.i("Waveform", "$key: ${System.currentTimeMillis() - t0} ms")
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("Waveform", "failed for $key: ${e.message}")
            null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Loudness → bar height. Loud masters vary only a few dB between verse and chorus, so a fixed
     * window flattens everything near the top. Instead the window spans this track's own spread
     * (5th..98th percentile, at least 6 dB), then a contrast curve separates the sections.
     */
    internal fun shape(rms: FloatArray): FloatArray {
        val raw = FloatArray(rms.size) { 20 * kotlin.math.log10(max(rms[it], 1e-5f)) }
        // Light 1-2-1 smoothing: keeps sections and drops, removes beat-sampling jitter.
        val db = FloatArray(raw.size) { i ->
            val l = raw[maxOf(i - 1, 0)]; val r = raw[minOf(i + 1, raw.lastIndex)]
            (l + 2 * raw[i] + r) / 4
        }
        val sorted = db.sorted()
        fun pct(q: Float) = sorted[((sorted.size - 1) * q).toInt()]
        val hi = pct(0.98f)
        val window = max(hi - pct(0.05f), 6f)
        val lo = hi - window
        return FloatArray(db.size) {
            val v = ((db[it] - lo) / window).coerceIn(0f, 1f)
            0.06f + 0.94f * Math.pow(v.toDouble(), 1.6).toFloat()
        }
    }

    private fun file(key: String) = File(dir, key.hashCode().toUInt().toString(16) + "_" + key.length + ".v4.wf")

    private fun MediaFormat.getIntegerOrDefault(k: String, d: Int) = if (containsKey(k)) getInteger(k) else d

    companion object {
        const val BINS = 160
    }
}

/** Random-access reads over the span files of a fully cached resource. */
@UnstableApi
private class CacheSpanDataSource(private val cache: SimpleCache, private val key: String, private val length: Long) : MediaDataSource() {
    private val spans = cache.getCachedSpans(key).filter { it.isCached && it.file != null }.sortedBy { it.position }
    private var openFile: File? = null
    private var raf: RandomAccessFile? = null

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        val span = spans.lastOrNull { it.position <= position } ?: return -1
        val inSpan = position - span.position
        if (inSpan >= span.length) return -1
        if (openFile != span.file) { raf?.close(); raf = RandomAccessFile(span.file, "r"); openFile = span.file }
        val r = raf!!
        r.seek(inSpan)
        val n = minOf(size.toLong(), span.length - inSpan).toInt()
        return r.read(buffer, offset, n)
    }

    override fun getSize() = length
    override fun close() { raf?.close(); raf = null }
}
