package com.vesper.flipper.ui.screen.oracle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VRiskBadge
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VStatTile
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.screen.signals.formatFrequency
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.ExploitItem
import com.vesper.flipper.ui.viewmodel.OracleAnalysisResult
import com.vesper.flipper.ui.viewmodel.VulnItem

/** Step 4: the report, split into scannable sections. */
@Composable
fun OracleResultPane(
    result: OracleAnalysisResult,
    onShowRaw: () -> Unit,
    onRunAgain: () -> Unit,
    onNewCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = VesperSpace.gutter,
            end = VesperSpace.gutter,
            top = VesperSpace.xs,
            bottom = VesperSpace.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
    ) {
        item(key = "header") {
            ResultHeader(result)
        }

        item(key = "summary") {
            ResultSection(title = "Summary") {
                Text(
                    text = cleanAiText(result.summary).ifBlank { "No summary was returned." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Vesper.colors.textSecondary
                )
            }
        }

        if (result.vulnerabilities.isNotEmpty()) {
            item(key = "vulnerabilities") {
                ResultSection(
                    title = "Vulnerabilities",
                    count = result.vulnerabilities.size
                ) {
                    result.vulnerabilities.forEachIndexed { index, vulnerability ->
                        if (index > 0) {
                            VHairline(modifier = Modifier.padding(vertical = VesperSpace.xs))
                        }
                        VulnerabilityRow(vulnerability)
                    }
                }
            }
        }

        if (result.exploits.isNotEmpty()) {
            item(key = "exploits") {
                ResultSection(
                    title = "Exploit vectors",
                    count = result.exploits.size
                ) {
                    result.exploits.forEachIndexed { index, exploit ->
                        if (index > 0) {
                            VHairline(modifier = Modifier.padding(vertical = VesperSpace.xs))
                        }
                        ExploitRow(exploit)
                    }
                }
            }
        }

        item(key = "threat") {
            ResultSection(title = "Threat analysis") {
                Text(
                    text = cleanAiText(result.threatAnalysis)
                        .ifBlank { "No threat analysis was returned." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Vesper.colors.textSecondary
                )
            }
        }

        if (result.recommendations.isNotEmpty()) {
            item(key = "recommendations") {
                ResultSection(
                    title = "Recommendations",
                    count = result.recommendations.size
                ) {
                    result.recommendations.forEachIndexed { index, recommendation ->
                        Row(modifier = Modifier.padding(vertical = VesperSpace.xxs)) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Vesper.colors.gold,
                                modifier = Modifier.width(VesperSpace.lg)
                            )
                            Text(
                                text = cleanAiText(recommendation),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Vesper.colors.textSecondary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        item(key = "actions") {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
                    VSecondaryButton(
                        text = "Run again",
                        onClick = onRunAgain,
                        modifier = Modifier.weight(1f)
                    )
                    VSecondaryButton(
                        text = "New capture",
                        onClick = onNewCapture,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(VesperSpace.xs))
                VQuietButton(
                    text = "View raw response",
                    onClick = onShowRaw,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ResultHeader(result: OracleAnalysisResult) {
    val colors = Vesper.colors
    VCard(level = 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.signalName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${analysisTypeLabel(result.analysisType)} · ${formatFrequency(result.frequency)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(VesperSpace.xs))
            VRiskBadge(level = threatRiskLevel(result.threatLevel))
        }
        Spacer(Modifier.height(VesperSpace.sm))
        Text(
            text = "Threat level: ${result.threatLevel.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = threatColor(result.threatLevel)
        )
        Spacer(Modifier.height(VesperSpace.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
            VStatTile(
                label = "Protocol",
                value = result.protocolInfo.name,
                caption = confidenceLabel(result.protocolInfo),
                accent = colors.gold,
                modifier = Modifier.weight(1f)
            )
            VStatTile(
                label = "Encoding",
                value = result.protocolInfo.encoding,
                modifier = Modifier.weight(1f)
            )
            VStatTile(
                label = "Security",
                value = securityLabel(result.protocolInfo),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ResultSection(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = Vesper.colors
    VCard(level = 2, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            if (count != null) {
                Text(
                    text = "$count found",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
            }
        }
        Spacer(Modifier.height(VesperSpace.xs))
        content()
    }
}

@Composable
private fun VulnerabilityRow(vulnerability: VulnItem) {
    val colors = Vesper.colors
    Row(verticalAlignment = Alignment.Top) {
        VStatusPill(
            text = severityLabel(vulnerability.severity),
            color = severityColor(vulnerability.severity)
        )
        Spacer(Modifier.width(VesperSpace.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = vulnerability.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = cleanAiText(vulnerability.description),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExploitRow(exploit: ExploitItem) {
    val colors = Vesper.colors
    Row(verticalAlignment = Alignment.Top) {
        VStatusPill(
            text = exploit.difficulty,
            color = colors.info
        )
        Spacer(Modifier.width(VesperSpace.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exploit.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.gold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = cleanAiText(exploit.description),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
