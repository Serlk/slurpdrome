package dev.slurpdrome

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.player.eq.EqAudioProcessor
import dev.slurpdrome.player.eq.EqBand
import dev.slurpdrome.player.eq.EqPresets
import dev.slurpdrome.player.eq.EqProfile
import dev.slurpdrome.player.eq.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

@UnstableApi
class EqAndIdsTest {
    @Test fun globalIdsRoundTrip() {
        val g = Gid.of("s2", "abc~def")      // raw ids may contain the separator
        assertEquals("s2", Gid.source(g))
        assertEquals("abc~def", Gid.raw(g))
        assertTrue(Gid.isLocal(Gid.of("local", "42")))
    }

    @Test fun parsesAutoEqParametric() {
        val text = """
            Preamp: -6.1 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
            Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
            Filter 6: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
        """.trimIndent()
        val p = EqPresets.parseAutoEq("x", "HD 650", text)
        assertNotNull(p); p!!
        assertEquals(-6.1f, p.preampDb, 0.001f)
        assertEquals(listOf(FilterType.LOW_SHELF, FilterType.PEAK, FilterType.HIGH_SHELF), p.bands.map { it.type })
        assertEquals(8800f, p.bands[1].freq, 0.1f)
    }

    private fun rms(eq: EqAudioProcessor, freq: Double, amp: Double): Pair<Double, Double> {
        val rate = 48000
        eq.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        eq.flush()
        val frames = rate / 2
        val inBuf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (sin(2 * PI * freq * i / rate) * amp * 32767).toInt().toShort()
            inBuf.putShort(v); inBuf.putShort(v)
        }
        inBuf.flip()
        eq.queueInput(inBuf)
        val out = eq.output.order(ByteOrder.nativeOrder())
        var sum = 0.0; var n = 0; var peak = 0.0
        var i = 0
        while (out.remaining() >= 2) {
            val s = out.short / 32768.0
            if (i > frames) { sum += s * s; n++; peak = maxOf(peak, abs(s)) }   // skip filter warm-up
            i++
        }
        return sqrt(sum / n) to peak
    }

    @Test fun flatIsTransparentBoostBoostsLimiterHolds() {
        val eq = EqAudioProcessor()
        eq.enabled = true
        eq.profile = EqPresets.builtins.first { it.id == "flat" }
        val (flat, _) = rms(eq, 1000.0, 0.25)
        assertEquals(0.25 / sqrt(2.0), flat, 0.005)

        eq.profile = EqProfile("b", "boost", 0f, listOf(EqBand(FilterType.PEAK, 1000f, 6f, 1.0f)))
        val (boosted, _) = rms(eq, 1000.0, 0.25)
        assertEquals(2.0, boosted / flat, 0.1)          // +6 dB ≈ ×2

        val (_, peak) = rms(eq, 1000.0, 0.9)            // would clip at ×2 → limiter
        assertTrue("peak $peak", peak <= 0.98)
    }
}
