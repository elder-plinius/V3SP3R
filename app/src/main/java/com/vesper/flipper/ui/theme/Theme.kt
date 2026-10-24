package com.vesper.flipper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════
// VESPER THEME
// Dark ("The Crypt") is the product. Light ("Bone") is a real,
// fully specified alternative — not a broken fallback.
// ═══════════════════════════════════════════════════════════

private val DarkColorScheme = darkColorScheme(
    primary = Wine,
    onPrimary = OnWine,
    primaryContainer = WinePressed,
    onPrimaryContainer = Color(0xFFF6DDE2),
    secondary = Rose,
    onSecondary = Color(0xFF2A0D14),
    secondaryContainer = S3,
    onSecondaryContainer = TextPrimary,
    tertiary = Gold,
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF3A2E12),
    onTertiaryContainer = Gold,
    background = S0,
    onBackground = TextPrimary,
    surface = S1,
    onSurface = TextPrimary,
    surfaceVariant = S2,
    onSurfaceVariant = TextSecondary,
    outline = Hairline,
    outlineVariant = HairlineSoft,
    error = RiskHighText,
    onError = Color(0xFF2A0B10),
    errorContainer = RiskHighContainer,
    onErrorContainer = Color(0xFFFFD9DF),
    inverseSurface = TextPrimary,
    inverseOnSurface = S0,
    inversePrimary = WineLight,
    surfaceTint = Gold,
    scrim = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = Wine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5D9DE),
    onPrimaryContainer = Color(0xFF3B0A14),
    secondary = Color(0xFFA33A50),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E2E6),
    onSecondaryContainer = Color(0xFF3B0A14),
    tertiary = OldGold,
    onTertiary = Color(0xFFFFF9EC),
    tertiaryContainer = Color(0xFFF4E8CC),
    onTertiaryContainer = Color(0xFF2A1F05),
    background = BoneCanvas,
    onBackground = BoneInk,
    surface = BoneS2,
    onSurface = BoneInk,
    surfaceVariant = BoneS1,
    onSurfaceVariant = BoneTextSecondary,
    outline = BoneHairline,
    outlineVariant = Color(0xFFF0E9E0),
    error = Color(0xFF9E2437),
    onError = Color.White,
    errorContainer = Color(0xFFF9E2E6),
    onErrorContainer = Color(0xFF3B0A14),
    inverseSurface = BoneInk,
    inverseOnSurface = BoneCanvas,
    inversePrimary = WineLight,
    surfaceTint = Wine,
    scrim = Color.Black
)

/** Dark canvas gradient. Kept as a value for existing call sites. */
val VesperBackdropBrush = Brush.verticalGradient(
    listOf(S0, Color(0xFF0B0910), Color(0xFF0D0A14), S0)
)

/** Light canvas gradient. */
val VesperBackdropBrushLight = Brush.verticalGradient(
    listOf(BoneCanvas, Color(0xFFF9F5F0), Color(0xFFF5EFE8), BoneCanvas)
)

/** Resolves the canvas gradient for the active theme. */
@Composable
@ReadOnlyComposable
fun vesperBackdrop(): Brush =
    if (LocalVesperColors.current.isDark) VesperBackdropBrush else VesperBackdropBrushLight

/**
 * Access point for Vesper design tokens. Examples:
 * Vesper.colors.textSecondary, Vesper.space.md, Vesper.motion.standard().
 */
object Vesper {
    val colors: VesperColors
        @Composable @ReadOnlyComposable get() = LocalVesperColors.current

    val space: VesperSpace get() = VesperSpace
    val motion: VesperMotion get() = VesperMotion
}

@Composable
fun VesperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkVesperColors else LightVesperColors
    val scheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalVesperColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = VesperTypography,
            shapes = VesperShapes,
            content = content
        )
    }
}
