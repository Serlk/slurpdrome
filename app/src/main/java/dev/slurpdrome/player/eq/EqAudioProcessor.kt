package dev.slurpdrome.player.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parametric EQ inside ExoPlayer's audio pipeline: cascaded RBJ biquads (peaking / shelves),
 * preamp, and a smooth peak limiter so boosts never clip. Works on 16-bit and float PCM.
 * The profile can be swapped at any time from another thread ([profile] is volatile);
 * coefficients are recomputed on the audio thread, filter state is preserved to avoid clicks.
 */
@UnstableApi
class EqAudioProcessor : BaseAudioProcessor() {
    @Volatile var profile: EqProfile? = null
    @Volatile var enabled: Boolean = false

    private var applied: EqProfile? = null
    private var appliedEnabled = false
    private var sampleRate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    private var coeffs = arrayOf<DoubleArray>()          // per band: b0 b1 b2 a1 a2 (normalised)
    private var state = arrayOf<DoubleArray>()           // per band*channel: x1 x2 y1 y2
    private var preamp = 1.0
    private var limiterGain = 1.0
    private var limiterOn = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        applied = null
        return inputAudioFormat
    }

    private fun rebuild() {
        val p = profile
        applied = p
        appliedEnabled = enabled
        val bands = if (enabled && p != null) p.bands.filter { it.gainDb != 0f && it.freq < sampleRate / 2f } else emptyList()
        preamp = if (enabled && p != null) 10.0.pow(p.preampDb / 20.0) else 1.0
        limiterOn = enabled && (p?.limiter ?: true)
        val newCoeffs = bands.map { design(it) }.toTypedArray()
        if (newCoeffs.size != coeffs.size) state = Array(newCoeffs.size * channels) { DoubleArray(4) }
        coeffs = newCoeffs
    }

    /** RBJ Audio-EQ-Cookbook biquads. */
    private fun design(b: EqBand): DoubleArray {
        val a = 10.0.pow(b.gainDb / 40.0)
        val w0 = 2 * PI * b.freq / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val q = b.q.toDouble().coerceAtLeast(0.1)
        val alpha = sw / (2 * q)
        val (b0, b1, b2, a0, a1, a2) = when (b.type) {
            FilterType.PEAK -> Six(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            FilterType.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                Six(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s)
            }
            FilterType.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                Six(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s)
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private data class Six(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double)

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (applied !== profile || appliedEnabled != enabled) rebuild()
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val bypass = !appliedEnabled || (coeffs.isEmpty() && preamp == 1.0)
        if (bypass) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val release = 1.0 - 1.0 / (0.08 * sampleRate)     // ~80 ms limiter release
        var ch = 0
        while (input.hasRemaining()) {
            var x = if (encoding == C.ENCODING_PCM_16BIT) input.short / 32768.0 else input.float.toDouble()
            x *= preamp
            for (i in coeffs.indices) {
                val k = coeffs[i]; val st = state[i * channels + ch]
                val y = k[0] * x + k[1] * st[0] + k[2] * st[1] - k[3] * st[2] - k[4] * st[3]
                st[1] = st[0]; st[0] = x; st[3] = st[2]; st[2] = y
                x = y
            }
            if (limiterOn) {
                val peak = abs(x) * limiterGain
                if (peak > CEILING) limiterGain = CEILING / abs(x)       // instant attack
                else limiterGain = (limiterGain / release).coerceAtMost(1.0) // smooth release
                x *= limiterGain
            }
            if (encoding == C.ENCODING_PCM_16BIT) out.putShort((x.coerceIn(-1.0, 0.99997) * 32768.0).toInt().toShort())
            else out.putFloat(x.toFloat())
            ch = (ch + 1) % channels
        }
        out.flip()
    }

    override fun onFlush() { state.forEach { it.fill(0.0) }; limiterGain = 1.0 }
    override fun onReset() { applied = null; coeffs = arrayOf(); state = arrayOf() }

    private companion object { const val CEILING = 0.977 }   // ≈ -0.2 dBFS
}
