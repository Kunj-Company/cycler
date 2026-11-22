package com.cycler.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cycler.app.R

/**
 * The look: quiet and neutral. White (or near-black) surfaces, hairline
 * borders instead of shadows, ink-coloured primary buttons, one blue accent
 * for the route and for selection, red only for recording and warnings.
 */
class CyColors(
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val success: Color,
    val warn: Color,
    val subtle: Color,
    val border: Color,
    val isDark: Boolean,
)

private val LightCy = CyColors(
    accent = Color(0xFF2563EB), accentSoft = Color(0xFFEAF1FE), danger = Color(0xFFD4403A),
    success = Color(0xFF2F7D4F), warn = Color(0xFFB7791F), subtle = Color(0xFFF6F6F4),
    border = Color(0xFFE5E5E2), isDark = false,
)
private val DarkCy = CyColors(
    accent = Color(0xFF6C9BF5), accentSoft = Color(0xFF1C2740), danger = Color(0xFFEF6B64),
    success = Color(0xFF5FB583), warn = Color(0xFFE0B252), subtle = Color(0xFF222222),
    border = Color(0xFF2F2F2F), isDark = true,
)

val LocalCy = staticCompositionLocalOf { LightCy }

object Cy {
    val colors: CyColors
        @Composable @ReadOnlyComposable get() = LocalCy.current
}

private fun scheme(cy: CyColors): ColorScheme {
    val ink = if (cy.isDark) Color(0xFFEDEDEB) else Color(0xFF1C1C1A)
    val paper = if (cy.isDark) Color(0xFF141414) else Color(0xFFFFFFFF)
    val surface = if (cy.isDark) Color(0xFF1B1B1B) else Color(0xFFFFFFFF)
    val muted = if (cy.isDark) Color(0xFF9B9A97) else Color(0xFF6F6E69)
    val base = if (cy.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = ink, onPrimary = paper,
        primaryContainer = cy.accentSoft, onPrimaryContainer = ink,
        secondary = cy.accent, onSecondary = Color.White,
        secondaryContainer = cy.subtle, onSecondaryContainer = ink,
        tertiary = cy.accent, onTertiary = Color.White,
        background = paper, onBackground = ink,
        surface = surface, onSurface = ink,
        surfaceVariant = cy.subtle, onSurfaceVariant = muted,
        surfaceTint = surface,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = cy.subtle,
        outline = if (cy.isDark) Color(0xFF4A4A4A) else Color(0xFFCFCFCB),
        outlineVariant = cy.border,
        error = cy.danger, onError = Color.White,
    )
}

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter, weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

private fun style(size: Int, weight: Int, line: Int, tracking: Double = 0.0, tabular: Boolean = false) = TextStyle(
    fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp,
    letterSpacing = tracking.em, fontFeatureSettings = if (tabular) "tnum" else null,
)

private val CyType = Typography(
    displayLarge = style(56, 600, 60, -0.03, true),
    displayMedium = style(44, 600, 48, -0.03, true),
    displaySmall = style(32, 600, 36, -0.02, true),
    headlineLarge = style(28, 600, 34, -0.02),
    headlineMedium = style(24, 600, 30, -0.02),
    headlineSmall = style(22, 600, 28, -0.015),
    titleLarge = style(19, 600, 24, -0.01),
    titleMedium = style(16, 600, 22, -0.005),
    titleSmall = style(14, 600, 20),
    bodyLarge = style(16, 400, 24),
    bodyMedium = style(14, 400, 20),
    bodySmall = style(13, 400, 18),
    labelLarge = style(14, 500, 20),
    labelMedium = style(12, 500, 16),
    labelSmall = style(11, 500, 14, 0.04),
)

/** Numbers that line up in columns and don't jiggle while they change. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

@Composable
fun CyclerTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val cy = if (dark) DarkCy else LightCy
    CompositionLocalProvider(LocalCy provides cy) {
        MaterialTheme(colorScheme = scheme(cy), typography = CyType, content = content)
    }
}
