package com.vesper.flipper.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════
// VESPER — "Wine & Bone"
// One palette, one meaning per color.
//
// Rules:
//  - Wine is a FILL. Wine-colored text is unreadable on dark;
//    use Rose for wine-tinted text.
//  - Gold is TEXT/ICON on dark. Gold as a fill always pairs
//    with Ink, never white.
//  - Risk colors have separate text and container variants.
// ═══════════════════════════════════════════════════════════

// ── Brand ─────────────────────────────────────────────────
val Wine = Color(0xFF8B2038)          // primary fill (8.9:1 with white)
val WinePressed = Color(0xFF5E1625)
val WineLight = Color(0xFFAD3B50)
val Rose = Color(0xFFD9697E)          // wine-tinted text (6.0:1 on S0)
val RoseMuted = Color(0xFFB57785)

val Gold = Color(0xFFE9C97A)          // gold text on dark (12.5:1)
val GoldDeep = Color(0xFFD4AF37)      // heritage gold — fills only
val GoldMuted = Color(0xFF9E8530)
val Ink = Color(0xFF1A0E12)           // text on gold (11.7:1)

// ── Dark surfaces (four step ladder, no shadows) ──────────
val S0 = Color(0xFF08070C)            // canvas
val S1 = Color(0xFF100D16)            // sections
val S2 = Color(0xFF17121F)            // cards
val S3 = Color(0xFF1F1829)            // sheets, dialogs
val Hairline = Color(0xFF2C2436)
val HairlineSoft = Color(0xFF1D1826)

// Dark text
val TextPrimary = Color(0xFFF3EFEA)   // 17.5:1 on S0
val TextSecondary = Color(0xFFA89FB0) // 7.9:1 on S0
val TextTertiary = Color(0xFF7A7186)  // 4.15:1 — captions only
val OnWine = Color(0xFFFDF7F8)

// ── Light surfaces ("Bone") ───────────────────────────────
val BoneCanvas = Color(0xFFF7F3EE)
val BoneS1 = Color(0xFFFFFBF6)
val BoneS2 = Color(0xFFFFFFFF)
val BoneS3 = Color(0xFFFFFFFF)
val BoneHairline = Color(0xFFE6DED4)
val BoneInk = Color(0xFF14100F)       // 17.1:1
val BoneTextSecondary = Color(0xFF5C5566) // 6.45:1
val BoneTextTertiary = Color(0xFF7C7385)
val OldGold = Color(0xFF7A5C12)       // gold text on light (6.24:1)

// ── Risk ──────────────────────────────────────────────────
val RiskLowText = Color(0xFF3CBF88)
val RiskLowContainer = Color(0xFF10291F)
val RiskMediumText = Gold
val RiskMediumContainer = Color(0xFF2A2312)
val RiskHighText = Color(0xFFE4677A)
val RiskHighContainer = Color(0xFF2E1118)
val RiskBlockedText = Color(0xFF8B93AD)
val RiskBlockedContainer = Color(0xFF1A1D28)

// Semantic status
val Success = Color(0xFF3CBF88)
val Warning = GoldDeep
val Danger = Color(0xFFE4677A)
val Info = Color(0xFF8FB4D9)

// Diff
val DiffAdded = Color(0xFF3CBF88)
val DiffRemoved = Color(0xFFE4677A)
val DiffChanged = Gold
val DiffAddedBackground = Color(0x333CBF88)
val DiffRemovedBackground = Color(0x33E4677A)

// ── Legacy aliases (kept so existing screens compile) ─────
// New code should use Vesper.colors.* instead.
val VesperWine = Wine
val VesperWineLight = WineLight
val VesperWineDark = WinePressed
val VesperOrange = Wine
val VesperOrangeDark = WinePressed
val VesperSecondary = Color(0xFF1A1F2E)
val VesperGunmetal = Color(0xFF2A3142)
val VesperAccent = Gold
val VesperGold = Gold
val VesperGoldMuted = GoldMuted
val VesperSurface = S1
val VesperSurfaceVariant = S2
val VesperBackground = S0
val VesperBackgroundDeep = Color(0xFF030508)
val VesperBackgroundGlow = Color(0xFF0D0A14)
val RiskLow = RiskLowText
val RiskMedium = GoldDeep
val RiskHigh = Color(0xFFCF4455)
val RiskBlocked = RiskBlockedText
val ChatAssistant = S2
val ChatUser = Wine
val ChatTool = S1
val ChatToolAccent = Gold

/**
 * Semantic color tokens resolved for the active theme.
 * Access inside composables with [Vesper.colors].
 */
@Immutable
data class VesperColors(
    val isDark: Boolean,
    val canvas: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val hairline: Color,
    val hairlineSoft: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val wine: Color,
    val winePressed: Color,
    val rose: Color,
    val gold: Color,
    val goldDeep: Color,
    val onGold: Color,
    val onWine: Color,
    val riskLow: Color,
    val riskLowContainer: Color,
    val riskMedium: Color,
    val riskMediumContainer: Color,
    val riskHigh: Color,
    val riskHighContainer: Color,
    val riskBlocked: Color,
    val riskBlockedContainer: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color
)

val DarkVesperColors = VesperColors(
    isDark = true,
    canvas = S0,
    surface1 = S1,
    surface2 = S2,
    surface3 = S3,
    hairline = Hairline,
    hairlineSoft = HairlineSoft,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    textTertiary = TextTertiary,
    wine = Wine,
    winePressed = WinePressed,
    rose = Rose,
    gold = Gold,
    goldDeep = GoldDeep,
    onGold = Ink,
    onWine = OnWine,
    riskLow = RiskLowText,
    riskLowContainer = RiskLowContainer,
    riskMedium = RiskMediumText,
    riskMediumContainer = RiskMediumContainer,
    riskHigh = RiskHighText,
    riskHighContainer = RiskHighContainer,
    riskBlocked = RiskBlockedText,
    riskBlockedContainer = RiskBlockedContainer,
    success = Success,
    warning = Warning,
    danger = Danger,
    info = Info
)

val LightVesperColors = VesperColors(
    isDark = false,
    canvas = BoneCanvas,
    surface1 = BoneS1,
    surface2 = BoneS2,
    surface3 = BoneS3,
    hairline = BoneHairline,
    hairlineSoft = Color(0xFFF0E9E0),
    textPrimary = BoneInk,
    textSecondary = BoneTextSecondary,
    textTertiary = BoneTextTertiary,
    wine = Wine,
    winePressed = WinePressed,
    rose = Color(0xFFA33A50),
    gold = OldGold,
    goldDeep = OldGold,
    onGold = Color(0xFFFFF9EC),
    onWine = Color.White,
    riskLow = Color(0xFF1D7A52),
    riskLowContainer = Color(0xFFE3F3EA),
    riskMedium = OldGold,
    riskMediumContainer = Color(0xFFF6EDD8),
    riskHigh = Color(0xFF9E2437),
    riskHighContainer = Color(0xFFF9E2E6),
    riskBlocked = Color(0xFF4E5772),
    riskBlockedContainer = Color(0xFFEBEDF3),
    success = Color(0xFF1D7A52),
    warning = Color(0xFF7A5C12),
    danger = Color(0xFF9E2437),
    info = Color(0xFF2F5F8A)
)

val LocalVesperColors = staticCompositionLocalOf { DarkVesperColors }
