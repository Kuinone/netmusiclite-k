package com.netmusiclite.ui.m3e

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.wear.compose.material3.TimeText
import com.netmusiclite.ui.theme.LocalClockFontScale
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Shapes
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette

// Apply the requested 20% reduction to the preceding +7% color adjustment.
private const val CHROMA_GAIN = 1.07 * 0.80
private const val SURFACE_OPACITY = 0.93f

/** Surface-only transparency; content/foreground colors retain their original opacity. */
fun Color.m3eSurface(): Color = copy(alpha = alpha * SURFACE_OPACITY)

/** Extra song-container transparency is exclusive to the active cover-blur background. */
internal fun Color.m3eSongSurface(additionalTransparency: Float = .20f): Color {
    val coverBlur = com.netmusiclite.data.AppearancePrefs.m3eBackgroundStyle ==
        com.netmusiclite.data.M3eBackgroundStyle.COVER_BLUR
    val applies = !com.netmusiclite.data.AppearancePrefs.m3eBackgroundPlayerOnly ||
        com.netmusiclite.ui.components.AmbientSurface.active
    return if (coverBlur && applies) copy(alpha = (alpha - additionalTransparency).coerceIn(0f, 1f)) else this
}

/** Official Material Color Utilities HCT palettes. Visual literals are restricted to theme tokens. */
fun monetColor(seed: Color, tone: Int, chroma: Double? = null): Color {
    val hct = Hct.fromInt(seed.toArgb())
    return Color(TonalPalette.fromHueAndChroma(hct.hue, (chroma ?: hct.chroma) * CHROMA_GAIN).tone(tone))
}

@Composable
fun M3eTheme(seed: Color, content: @Composable () -> Unit) {
    val colors = remember(seed) {
        val hct = Hct.fromInt(seed.toArgb())
        fun primary(tone: Int) = monetColor(seed, tone, hct.chroma.coerceIn(24.0, 48.0))
        fun secondary(tone: Int) = monetColor(seed, tone, 16.0)
        fun tertiary(tone: Int) = Color(TonalPalette.fromHueAndChroma((hct.hue + 60) % 360, 24.0 * CHROMA_GAIN).tone(tone))
        ColorScheme(
            primary = primary(80).m3eSurface(), primaryDim = primary(70).m3eSurface(), primaryContainer = primary(30).m3eSurface(),
            onPrimary = primary(20), onPrimaryContainer = primary(90),
            secondary = secondary(80).m3eSurface(), secondaryDim = secondary(70).m3eSurface(), secondaryContainer = secondary(30).m3eSurface(),
            onSecondary = secondary(20), onSecondaryContainer = secondary(90),
            tertiary = tertiary(80).m3eSurface(), tertiaryDim = tertiary(70).m3eSurface(), tertiaryContainer = tertiary(30).m3eSurface(),
            onTertiary = tertiary(20), onTertiaryContainer = tertiary(90),
            background = Color.Black, onBackground = Color(0xFFF5F5F5),
            surfaceContainerLow = Color(0xFF141414).m3eSurface(), surfaceContainer = Color(0xFF1C1C1C).m3eSurface(),
            surfaceContainerHigh = Color(0xFF292929).m3eSurface(), onSurface = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFFCAC4D0), outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
        )
    }
    MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(9.dp))) {
        AppScaffold(timeText = { OriginalSizeClock { TimeText() } }) { content() }
    }
}

@Composable
internal fun OriginalSizeClock(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val fontScale = LocalClockFontScale.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
}
