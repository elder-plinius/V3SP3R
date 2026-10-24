package com.vesper.flipper.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════
// VESPER TYPE SCALE
// Seven usable sizes, all defined — no Material defaults leaking
// through. Families resolve to faces guaranteed on every Android
// device (serif -> Noto Serif, sans -> Roboto, mono -> Roboto Mono),
// so the brand is the same on a Xiaomi as on a Pixel.
// Nothing below 12sp. Mono is for data, never for prose.
// ═══════════════════════════════════════════════════════════

private val Display = FontFamily.Serif
private val Text = FontFamily.SansSerif
private val Data = FontFamily.Monospace

private val trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun style(
    family: FontFamily,
    weight: FontWeight,
    size: Int,
    line: Int,
    tracking: Double = 0.0
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = trim
)

val VesperTypography = Typography(
    // Editorial display — title cards, empty states, onboarding
    displayLarge = style(Display, FontWeight.Normal, 40, 44, -0.5),
    displayMedium = style(Display, FontWeight.Normal, 34, 40, -0.4),
    displaySmall = style(Display, FontWeight.Normal, 28, 34, -0.3),

    // Screen + section headers
    headlineLarge = style(Display, FontWeight.Normal, 28, 34, -0.2),
    headlineMedium = style(Display, FontWeight.Normal, 24, 30, -0.2),
    headlineSmall = style(Display, FontWeight.Normal, 20, 26, -0.1),

    // Component titles
    titleLarge = style(Text, FontWeight.SemiBold, 20, 26, -0.1),
    titleMedium = style(Text, FontWeight.Medium, 16, 22, 0.0),
    titleSmall = style(Text, FontWeight.Medium, 14, 20, 0.1),

    // Reading text
    bodyLarge = style(Text, FontWeight.Normal, 16, 24, 0.1),
    bodyMedium = style(Text, FontWeight.Normal, 14, 22, 0.1),
    bodySmall = style(Text, FontWeight.Normal, 12, 18, 0.15),

    // Labels are data: command names, risk codes, timestamps, paths
    labelLarge = style(Data, FontWeight.Medium, 13, 18, 0.5),
    labelMedium = style(Data, FontWeight.Medium, 12, 16, 0.45),
    labelSmall = style(Data, FontWeight.Medium, 12, 16, 0.4)
)
