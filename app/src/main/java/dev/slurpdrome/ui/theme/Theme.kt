package dev.slurpdrome.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.slurpdrome.data.prefs.Settings
import dev.slurpdrome.data.prefs.ThemeMode

/**
 * A theme = a light + dark colour pair. New themes are added by appending to [AppThemes.all];
 * the settings screen lists them automatically.
 */
data class ThemeSpec(val id: String, val name: String, val light: ColorScheme, val dark: ColorScheme)

/** Extra semantic colours Material doesn't model. */
data class ExtraColors(val focusRing: Color, val favorite: Color, val offline: Color)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors(Color(0xFFFF6B35), Color(0xFFE5484D), Color(0xFF2E9E6B)) }

object AppThemes {
    // "Signal" — warm graphite surfaces with a phosphor-orange accent.
    private val slurpLight = lightColorScheme(
        primary = Color(0xFFB8420F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBCC),
        onPrimaryContainer = Color(0xFF3A0C00),
        secondary = Color(0xFF6E5A50),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF7DDD2),
        onSecondaryContainer = Color(0xFF271813),
        tertiary = Color(0xFF1F6B5A),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFA6F2DC),
        onTertiaryContainer = Color(0xFF002019),
        background = Color(0xFFFBF8F6),
        onBackground = Color(0xFF1E1B19),
        surface = Color(0xFFFBF8F6),
        onSurface = Color(0xFF1E1B19),
        surfaceVariant = Color(0xFFEEE6E1),
        onSurfaceVariant = Color(0xFF4F4540),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6F1EE),
        surfaceContainer = Color(0xFFF0EAE6),
        surfaceContainerHigh = Color(0xFFEAE3DF),
        surfaceContainerHighest = Color(0xFFE4DCD8),
        outline = Color(0xFF81756F),
        outlineVariant = Color(0xFFD3C4BC),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
    )
    private val slurpDark = darkColorScheme(
        primary = Color(0xFFFF8A57),
        onPrimary = Color(0xFF5A1B00),
        primaryContainer = Color(0xFF7E2D05),
        onPrimaryContainer = Color(0xFFFFDBCC),
        secondary = Color(0xFFDBC1B6),
        onSecondary = Color(0xFF3D2C25),
        secondaryContainer = Color(0xFF55433B),
        onSecondaryContainer = Color(0xFFF7DDD2),
        tertiary = Color(0xFF6FD9BA),
        onTertiary = Color(0xFF00382D),
        tertiaryContainer = Color(0xFF005142),
        onTertiaryContainer = Color(0xFFA6F2DC),
        background = Color(0xFF121010),
        onBackground = Color(0xFFEDE0DA),
        surface = Color(0xFF121010),
        onSurface = Color(0xFFEDE0DA),
        surfaceVariant = Color(0xFF3A322E),
        onSurfaceVariant = Color(0xFFD3C4BC),
        surfaceContainerLowest = Color(0xFF0C0A0A),
        surfaceContainerLow = Color(0xFF1A1716),
        surfaceContainer = Color(0xFF1F1B1A),
        surfaceContainerHigh = Color(0xFF292524),
        surfaceContainerHighest = Color(0xFF34302E),
        outline = Color(0xFF9C8E87),
        outlineVariant = Color(0xFF4F4540),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

    val all: List<ThemeSpec> = listOf(
        ThemeSpec("slurp", "Signal", slurpLight, slurpDark),
        seeded("phosphor", "Phosphor", Color(0xFF39FF7A), surfaceSat = 0.18f),
        seeded("nord", "Nord", Color(0xFF88C0D0), surfaceSat = 0.16f),
        seeded("ocean", "Ocean", Color(0xFF3D8BFF)),
        seeded("sakura", "Sakura", Color(0xFFFF6F9A)),
        mono(),
    )

    /** Swatches offered for a custom accent. */
    val accentSwatches = listOf(
        0xFFFF6B35, 0xFFE5484D, 0xFFFF6F9A, 0xFFB46CFF, 0xFF6E6BFF, 0xFF3D8BFF,
        0xFF22B8CF, 0xFF2FBF71, 0xFF39FF7A, 0xFFC6E03B, 0xFFFFC233, 0xFF9C8E87,
    )

    fun seeded(id: String, name: String, seed: Color, surfaceSat: Float = 0.08f) =
        ThemeSpec(id, name, schemeFromSeed(seed, dark = false, surfaceSat), schemeFromSeed(seed, dark = true, surfaceSat))

    private fun mono(): ThemeSpec {
        val light = lightColorScheme(
            primary = Color(0xFF111111), onPrimary = Color.White, primaryContainer = Color(0xFFDDDDDD), onPrimaryContainer = Color.Black,
            secondary = Color(0xFF333333), onSecondary = Color.White, secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color.Black,
            tertiary = Color(0xFF444444), background = Color.White, onBackground = Color.Black, surface = Color.White, onSurface = Color.Black,
            surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF222222), outline = Color(0xFF555555), outlineVariant = Color(0xFFBBBBBB),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F7), surfaceContainer = Color(0xFFF0F0F0),
            surfaceContainerHigh = Color(0xFFE8E8E8), surfaceContainerHighest = Color(0xFFE0E0E0),
        )
        val dark = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black, primaryContainer = Color(0xFF333333), onPrimaryContainer = Color.White,
            secondary = Color(0xFFCCCCCC), onSecondary = Color.Black, secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = Color.White,
            tertiary = Color(0xFFBBBBBB), background = Color.Black, onBackground = Color.White, surface = Color.Black, onSurface = Color.White,
            surfaceVariant = Color(0xFF222222), onSurfaceVariant = Color(0xFFDDDDDD), outline = Color(0xFFAAAAAA), outlineVariant = Color(0xFF444444),
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0D0D0D), surfaceContainer = Color(0xFF141414),
            surfaceContainerHigh = Color(0xFF1C1C1C), surfaceContainerHighest = Color(0xFF262626),
        )
        return ThemeSpec("mono", "Mono", light, dark)
    }

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

/**
 * Small tonal-palette generator: derives a full Material scheme from one seed colour by
 * fixing lightness per role (like Material's tonal palettes) and keeping the seed's hue.
 */
fun schemeFromSeed(seed: Color, dark: Boolean, surfaceSat: Float = 0.08f): ColorScheme {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]; val s = hsl[1].coerceIn(0.35f, 0.95f)
    fun c(l: Float, sat: Float = s, hue: Float = h) = Color.hsl(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    return if (dark) darkColorScheme(
        primary = c(0.70f), onPrimary = c(0.14f), primaryContainer = c(0.28f, s * 0.7f), onPrimaryContainer = c(0.90f),
        secondary = c(0.76f, 0.25f), onSecondary = c(0.16f, 0.25f), secondaryContainer = c(0.26f, 0.2f), onSecondaryContainer = c(0.90f, 0.25f),
        tertiary = c(0.72f, 0.5f, h + 60f), onTertiary = c(0.15f, 0.5f, h + 60f), tertiaryContainer = c(0.28f, 0.4f, h + 60f), onTertiaryContainer = c(0.90f, 0.5f, h + 60f),
        background = c(0.07f, surfaceSat), onBackground = c(0.91f, 0.1f), surface = c(0.07f, surfaceSat), onSurface = c(0.91f, 0.1f),
        surfaceVariant = c(0.22f, 0.1f), onSurfaceVariant = c(0.79f, 0.1f), outline = c(0.58f, 0.08f), outlineVariant = c(0.30f, 0.08f),
        surfaceContainerLowest = c(0.04f, surfaceSat), surfaceContainerLow = c(0.09f, surfaceSat), surfaceContainer = c(0.11f, surfaceSat),
        surfaceContainerHigh = c(0.15f, surfaceSat), surfaceContainerHighest = c(0.19f, surfaceSat),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    ) else lightColorScheme(
        primary = c(0.40f), onPrimary = Color.White, primaryContainer = c(0.90f), onPrimaryContainer = c(0.12f),
        secondary = c(0.40f, 0.2f), onSecondary = Color.White, secondaryContainer = c(0.90f, 0.3f), onSecondaryContainer = c(0.12f, 0.3f),
        tertiary = c(0.38f, 0.45f, h + 60f), onTertiary = Color.White, tertiaryContainer = c(0.90f, 0.45f, h + 60f), onTertiaryContainer = c(0.12f, 0.45f, h + 60f),
        background = c(0.98f, surfaceSat * 2), onBackground = c(0.11f, 0.1f), surface = c(0.98f, surfaceSat * 2), onSurface = c(0.11f, 0.1f),
        surfaceVariant = c(0.91f, 0.15f), onSurfaceVariant = c(0.30f, 0.08f), outline = c(0.50f, 0.06f), outlineVariant = c(0.80f, 0.1f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = c(0.96f, surfaceSat * 1.5f), surfaceContainer = c(0.94f, surfaceSat * 1.5f),
        surfaceContainerHigh = c(0.92f, surfaceSat * 1.5f), surfaceContainerHighest = c(0.89f, surfaceSat * 1.5f),
        error = Color(0xFFBA1A1A), onError = Color.White,
    )
}

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/** Monospace for timecodes, bitrates and the hotkey overlay — a small nod to the cyberdeck crowd. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 0.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SlurpTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val spec = AppThemes.byId(settings.themeId)
    val ctx = LocalContext.current
    var scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        settings.accentSeed != 0L -> schemeFromSeed(Color(settings.accentSeed.toInt()), dark)
        dark -> spec.dark
        else -> spec.light
    }
    if (dark && settings.pureBlack) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black,
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF101010),
        )
    }
    val extra = ExtraColors(
        focusRing = scheme.primary,
        favorite = if (dark) Color(0xFFFF6B70) else Color(0xFFD62F3A),
        offline = scheme.tertiary,
    )
    CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
