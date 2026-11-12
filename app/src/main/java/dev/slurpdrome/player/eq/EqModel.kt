package dev.slurpdrome.player.eq

import androidx.annotation.StringRes
import dev.slurpdrome.R
import kotlinx.serialization.Serializable

enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF }

@Serializable
data class EqBand(val type: FilterType = FilterType.PEAK, val freq: Float, val gainDb: Float, val q: Float = 1.41f)

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val preampDb: Float = 0f,
    val bands: List<EqBand> = emptyList(),
    val origin: String = "manual",       // manual | builtin | autoeq
    val limiter: Boolean = true,
) {
    val isFlat get() = preampDb == 0f && bands.all { it.gainDb == 0f }
}

object EqPresets {
    /** ISO-ish 10-band graphic EQ centres used by the band editor. */
    val graphicFreqs = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    fun graphic(id: String, name: String, gains: List<Float>, preamp: Float = 0f, origin: String = "builtin") =
        EqProfile(id, name, preamp, graphicFreqs.zip(gains).map { (f, g) -> EqBand(FilterType.PEAK, f, g, 1.41f) }, origin)

    /** Localized display names of [builtins] by id; the `name` field is only a fallback. */
    val builtinNameRes: Map<String, Int> = mapOf(
        "flat" to R.string.eq_preset_flat,
        "bass" to R.string.eq_preset_bass,
        "treble" to R.string.eq_preset_treble,
        "vocal" to R.string.eq_preset_vocal,
        "loudness" to R.string.eq_preset_loudness,
        "rock" to R.string.eq_preset_rock,
        "electronic" to R.string.eq_preset_electronic,
        "podcast" to R.string.eq_preset_podcast,
    )

    @StringRes
    fun nameRes(p: EqProfile): Int? = if (p.origin == "builtin") builtinNameRes[p.id] else null

    val builtins = listOf(
        graphic("flat", "Flat", List(10) { 0f }),
        graphic("bass", "Bass boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), -4f),
        graphic("treble", "Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3f, 4.5f, 5f), -4f),
        graphic("vocal", "Vocal", listOf(-2f, -2f, -1f, 1f, 3f, 3.5f, 3f, 1.5f, 0f, -1f), -3f),
        graphic("loudness", "Loudness", listOf(6f, 4f, 2f, 0f, -1f, 0f, 0f, 2f, 4f, 5f), -5f),
        graphic("rock", "Rock", listOf(4f, 3f, 1.5f, 0f, -1f, -0.5f, 1f, 2.5f, 3.5f, 4f), -4f),
        graphic("electronic", "Electronic", listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f), -5f),
        graphic("podcast", "Speech", listOf(-6f, -4f, -2f, 0f, 2f, 3f, 3f, 2f, 0f, -2f), -3f),
    )

    /** Parses AutoEq "ParametricEQ.txt" (Preamp + Filter N: ON PK/LSC/HSC Fc … Gain … Q …). */
    fun parseAutoEq(id: String, name: String, text: String): EqProfile? {
        var preamp = 0f
        val bands = mutableListOf<EqBand>()
        val filter = Regex("""Filter\s+\d+:\s+ON\s+(PK|LSC|HSC|LS|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB(?:\s+Q\s+([\d.]+))?""")
        for (line in text.lines()) {
            Regex("""Preamp:\s*(-?[\d.]+)\s*dB""").find(line)?.let { preamp = it.groupValues[1].toFloat() }
            filter.find(line)?.let { m ->
                val type = when (m.groupValues[1]) { "LSC", "LS" -> FilterType.LOW_SHELF; "HSC", "HS" -> FilterType.HIGH_SHELF; else -> FilterType.PEAK }
                bands += EqBand(type, m.groupValues[2].toFloat(), m.groupValues[3].toFloat(), m.groupValues[4].toFloatOrNull() ?: 0.71f)
            }
        }
        return if (bands.isEmpty()) null else EqProfile(id, name, preamp, bands, "autoeq")
    }
}
