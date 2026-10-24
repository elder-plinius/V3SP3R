package com.vesper.flipper.ui.screen.oracle

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.vesper.flipper.domain.model.AnalysisType
import com.vesper.flipper.domain.model.OracleRiskLevel
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.domain.model.VulnerabilitySeverity
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.viewmodel.ProtocolInfo
import kotlin.math.roundToInt

fun analysisTypeIcon(type: AnalysisType): ImageVector = when (type) {
    AnalysisType.FULL_ANALYSIS -> Icons.Rounded.Tune
    AnalysisType.PROTOCOL_ID -> Icons.Rounded.Search
    AnalysisType.VULNERABILITY_SCAN -> Icons.Rounded.Shield
    AnalysisType.EXPLOIT_GEN -> Icons.Rounded.Bolt
    AnalysisType.MANUFACTURER_ID -> Icons.Rounded.Business
    AnalysisType.THREAT_ASSESSMENT -> Icons.Rounded.Warning
    AnalysisType.COUNTER_MEASURES -> Icons.Rounded.Lock
    AnalysisType.PATTERN_LEARN -> Icons.Rounded.ShowChart
}

/** Sentence-case label; the domain enum keeps title case for data exports. */
fun analysisTypeLabel(type: AnalysisType): String = when (type) {
    AnalysisType.FULL_ANALYSIS -> "Full analysis"
    AnalysisType.PROTOCOL_ID -> "Protocol identification"
    AnalysisType.VULNERABILITY_SCAN -> "Vulnerability scan"
    AnalysisType.EXPLOIT_GEN -> "Exploit generation"
    AnalysisType.MANUFACTURER_ID -> "Manufacturer identification"
    AnalysisType.THREAT_ASSESSMENT -> "Threat assessment"
    AnalysisType.COUNTER_MEASURES -> "Countermeasures"
    AnalysisType.PATTERN_LEARN -> "Pattern learning"
}

fun analysisTypeDescription(type: AnalysisType): String = when (type) {
    AnalysisType.FULL_ANALYSIS -> "Protocol, weaknesses, exploits, and next steps in one report."
    AnalysisType.PROTOCOL_ID -> "Match the timing against known protocols and encodings."
    AnalysisType.VULNERABILITY_SCAN -> "List weaknesses and rate how serious each one is."
    AnalysisType.EXPLOIT_GEN -> "Describe the attack paths and the timing needed to reproduce them."
    AnalysisType.MANUFACTURER_ID -> "Guess the manufacturer from the signal fingerprint."
    AnalysisType.THREAT_ASSESSMENT -> "Rate the risk and the impact if the signal is cloned."
    AnalysisType.COUNTER_MEASURES -> "Defensive steps for this protocol."
    AnalysisType.PATTERN_LEARN -> "Pull out the features that make this capture distinct."
}

fun severityLabel(severity: VulnerabilitySeverity): String = when (severity) {
    VulnerabilitySeverity.CRITICAL -> "Critical"
    VulnerabilitySeverity.HIGH -> "High"
    VulnerabilitySeverity.MEDIUM -> "Medium"
    VulnerabilitySeverity.LOW -> "Low"
    VulnerabilitySeverity.INFO -> "Info"
}

@Composable
fun severityColor(severity: VulnerabilitySeverity): Color = when (severity) {
    VulnerabilitySeverity.CRITICAL, VulnerabilitySeverity.HIGH -> Vesper.colors.riskHigh
    VulnerabilitySeverity.MEDIUM -> Vesper.colors.riskMedium
    VulnerabilitySeverity.LOW -> Vesper.colors.riskLow
    VulnerabilitySeverity.INFO -> Vesper.colors.info
}

fun threatRiskLevel(level: OracleRiskLevel): RiskLevel = when (level) {
    OracleRiskLevel.CRITICAL, OracleRiskLevel.HIGH -> RiskLevel.HIGH
    OracleRiskLevel.ELEVATED, OracleRiskLevel.MODERATE -> RiskLevel.MEDIUM
    OracleRiskLevel.LOW, OracleRiskLevel.MINIMAL -> RiskLevel.LOW
}

@Composable
fun threatColor(level: OracleRiskLevel): Color = when (level) {
    OracleRiskLevel.CRITICAL, OracleRiskLevel.HIGH -> Vesper.colors.riskHigh
    OracleRiskLevel.ELEVATED, OracleRiskLevel.MODERATE -> Vesper.colors.riskMedium
    OracleRiskLevel.LOW, OracleRiskLevel.MINIMAL -> Vesper.colors.riskLow
}

fun securityLabel(info: ProtocolInfo): String = when {
    info.isEncrypted && info.isRollingCode -> "Encrypted + rolling"
    info.isEncrypted -> "Encrypted"
    info.isRollingCode -> "Rolling code"
    else -> "None detected"
}

fun confidenceLabel(info: ProtocolInfo): String =
    "${(info.confidence * 100f).roundToInt()}% confidence"

/** Strips the light markdown the model returns so paragraphs read as prose. */
fun cleanAiText(text: String): String = text
    .replace("**", "")
    .replace("__", "")
    .replace("`", "")
    .replace(Regex("""(?m)^\s*[-*•]\s+"""), "")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()
