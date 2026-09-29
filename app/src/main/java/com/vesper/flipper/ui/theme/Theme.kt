package com.vesper.flipper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════
// FLIPPY BRAND — Dolphin Ocean Blues (from the logo artwork)
// ═══════════════════════════════════════════════════════════

// Primary: Ocean Blue — the dolphin's body
val VesperWine = Color(0xFF1E96D2)           // Bright ocean blue (brand primary)
val VesperWineLight = Color(0xFF4FB6E8)      // Lighter blue for highlights
val VesperWineDark = Color(0xFF155F8C)        // Deep ocean for pressed states

// Legacy alias — keeps existing references working
val VesperOrange = VesperWine
val VesperOrangeDark = VesperWineDark

// Secondary: Gunmetal — cold steel sophistication
val VesperSecondary = Color(0xFF1A1F2E)       // Gunmetal blue-black
val VesperGunmetal = Color(0xFF2A3142)         // Lighter gunmetal

// Accent: Wave Cyan — the glowing curl in the logo
val VesperAccent = Color(0xFF0CC4E0)           // Bright wave cyan
val VesperGold = Color(0xFF0CC4E0)
val VesperGoldMuted = Color(0xFF0E8CA8)        // Muted cyan for secondary accents

// Surface Colors — deep noir palette
val VesperSurface = Color(0xFF0E1118)
val VesperSurfaceVariant = Color(0xFF161B28)
val VesperBackground = Color(0xFF060810)
val VesperBackgroundDeep = Color(0xFF030508)
val VesperBackgroundGlow = Color(0xFF0A1018)   // Very subtle ocean tint

val VesperBackdropBrush = Brush.verticalGradient(
    colors = listOf(
        VesperBackground,
        VesperBackgroundDeep,
        VesperBackgroundGlow,
        VesperBackground
    )
)

// Risk Colors — semantic, kept distinct from brand palette
val RiskLow = Color(0xFF4CAF7D)
val RiskMedium = Color(0xFFD4AF37)   // Amber for medium risk
val RiskHigh = Color(0xFFCF4455)     // Alert red
val RiskBlocked = Color(0xFF6B7394)

// Diff Colors
val DiffAdded = Color(0xFF3CBF88)
val DiffRemoved = Color(0xFFCF4455)
val DiffChanged = Color(0xFFD4AF37)
val DiffAddedBackground = Color(0x403CBF88)
val DiffRemovedBackground = Color(0x40CF4455)

// Chat Colors — distinct from system
val ChatAssistant = Color(0xFF161B28)       // Dark gunmetal bubble
val ChatUser = VesperWine                    // Wine bubble for user
val ChatTool = Color(0xFF121620)             // Even darker for tool results
val ChatToolAccent = VesperGold              // Gold for tool highlights

private val DarkColorScheme = darkColorScheme(
    primary = VesperWine,
    onPrimary = Color(0xFFF2F8FD),
    primaryContainer = VesperWineDark,
    onPrimaryContainer = Color(0xFFD6ECFA),
    secondary = VesperSecondary,
    onSecondary = Color(0xFFCAD0E0),
    secondaryContainer = VesperSurfaceVariant,
    onSecondaryContainer = Color(0xFFD5DEF0),
    tertiary = VesperGold,
    onTertiary = Color(0xFF06222B),
    background = VesperBackground,
    onBackground = Color(0xFFE6E8F0),
    surface = VesperSurface,
    onSurface = Color(0xFFE4E6EE),
    surfaceVariant = VesperSurfaceVariant,
    onSurfaceVariant = Color(0xFF8E96AE),
    outline = Color(0xFF2E3548),
    outlineVariant = Color(0xFF1E2536),
    error = RiskHigh,
    onError = Color(0xFF200909)
)

private val LightColorScheme = lightColorScheme(
    primary = VesperWine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EAF8),
    onPrimaryContainer = Color(0xFF0C3A5A),
    secondary = VesperSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE0F0),
    onSecondaryContainer = Color(0xFF1A1F2E),
    tertiary = VesperGold,
    onTertiary = Color(0xFF06222B),
    background = Color(0xFFF2F5F8),
    onBackground = Color(0xFF121520),
    surface = Color.White,
    onSurface = Color(0xFF111420),
    surfaceVariant = Color(0xFFE8EEF4),
    onSurfaceVariant = Color(0xFF48586A),
    outline = Color(0xFF68788A),
    outlineVariant = Color(0xFFB4C4D4),
    error = RiskHigh,
    onError = Color.White
)

private val VesperShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

private val BaseTypography = androidx.compose.material3.Typography()

val VesperTypography = BaseTypography.copy(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Light,
        fontSize = 52.sp,
        letterSpacing = 1.2.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        letterSpacing = 0.6.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        letterSpacing = 0.3.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.2.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun VesperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VesperTypography,
        shapes = VesperShapes,
        content = content
    )
}
