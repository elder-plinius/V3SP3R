package com.vesper.flipper.ui.screen.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.AuditActionType
import com.vesper.flipper.domain.model.AuditEntry
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VRiskBadge
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class AuditDaySection(
    val key: String,
    val label: String,
    val entries: List<AuditEntry>
)

private data class AuditResultStyle(
    val label: String,
    val color: Color
)

/**
 * Audit as a reading timeline: day headers, a time gutter, a result chip,
 * and details only when asked for.
 */
@Composable
fun AuditTimeline(
    entries: List<AuditEntry>,
    replayableTraceIds: Set<String>,
    replayingTraceId: String?,
    onReplay: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sections = remember(entries) { groupByDay(entries) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = VesperSpace.gutter,
            end = VesperSpace.gutter,
            top = VesperSpace.xs,
            bottom = VesperSpace.xxxl
        )
    ) {
        sections.forEach { section ->
            item(key = "day:" + section.key) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = VesperSpace.md, bottom = VesperSpace.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = section.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = Vesper.colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = section.entries.size.toString() + " events",
                        style = MaterialTheme.typography.labelSmall,
                        color = Vesper.colors.textTertiary
                    )
                }
            }
            items(items = section.entries, key = { it.id }) { entry ->
                val expanded = entry.id in expandedIds
                AuditEntryRow(
                    entry = entry,
                    expanded = expanded,
                    traceId = entry.metadata["trace_id"]
                        ?.takeIf { it in replayableTraceIds },
                    isReplaying = replayingTraceId != null &&
                        replayingTraceId == entry.metadata["trace_id"],
                    onToggle = {
                        expandedIds = if (expanded) {
                            expandedIds - entry.id
                        } else {
                            expandedIds + entry.id
                        }
                    },
                    onReplay = onReplay
                )
            }
        }
    }
}

@Composable
private fun AuditEntryRow(
    entry: AuditEntry,
    expanded: Boolean,
    traceId: String?,
    isReplaying: Boolean,
    onToggle: () -> Unit,
    onReplay: (String) -> Unit
) {
    val colors = Vesper.colors
    val style = resultStyle(entry)
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    }
    val time = timeFormatter.format(
        Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault())
    )
    val path = entry.command?.args?.path
    val summary = entry.result?.error?.takeIf { it.isNotBlank() }
        ?: entry.result?.data?.message?.takeIf { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VesperSpace.xs)
    ) {
        Column(
            modifier = Modifier.width(44.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary
            )
            Spacer(Modifier.height(VesperSpace.xxs))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(style.color)
            )
        }
        Spacer(Modifier.width(VesperSpace.sm))
        VCard(
            level = 1,
            onClick = onToggle,
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = actionLabel(entry),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(VesperSpace.xs))
            Row(
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VStatusPill(text = style.label, color = style.color)
                entry.riskLevel?.let { level ->
                    VRiskBadge(level = level)
                }
            }

            if (!path.isNullOrBlank()) {
                Spacer(Modifier.height(VesperSpace.xs))
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(VesperSpace.xs))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (expanded) {
                Spacer(Modifier.height(VesperSpace.sm))
                VHairline()
                Spacer(Modifier.height(VesperSpace.sm))
                AuditDetails(entry = entry)
                if (traceId != null) {
                    Spacer(Modifier.height(VesperSpace.sm))
                    VSecondaryButton(
                        text = if (isReplaying) "Replaying" else "Replay command",
                        onClick = { onReplay(traceId) },
                        enabled = !isReplaying,
                        icon = Icons.Default.Replay
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditDetails(entry: AuditEntry) {
    val colors = Vesper.colors
    Column(verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
        entry.command?.let { command ->
            AuditDetailRow(
                label = "Action",
                value = command.action.name.replace('_', ' ').lowercase()
            )
            command.args.path?.let { path ->
                AuditDetailRow(label = "Path", value = path, mono = true)
            }
            command.args.destinationPath?.let { destination ->
                AuditDetailRow(label = "Destination", value = destination, mono = true)
            }
            AuditDetailRow(label = "Justification", value = command.justification)
            AuditDetailRow(label = "Expected effect", value = command.expectedEffect)
        }
        entry.result?.let { result ->
            AuditDetailRow(
                label = "Result",
                value = if (result.success) "Succeeded" else "Failed"
            )
            result.error?.takeIf { it.isNotBlank() }?.let { error ->
                AuditDetailRow(label = "Error", value = error)
            }
            AuditDetailRow(label = "Duration", value = result.executionTimeMs.toString() + " ms")
        }
        entry.userApproved?.let { approved ->
            AuditDetailRow(label = "Approval", value = if (approved) "Approved" else "Denied")
        }
        AuditDetailRow(label = "Session", value = entry.sessionId.take(12), mono = true)
        entry.metadata.entries.take(6).forEach { (key, value) ->
            AuditDetailRow(label = key.replace('_', ' '), value = value, mono = true)
        }
    }
}

@Composable
private fun AuditDetailRow(label: String, value: String, mono: Boolean = false) {
    val colors = Vesper.colors
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}

@Composable
private fun resultStyle(entry: AuditEntry): AuditResultStyle {
    val colors = Vesper.colors
    return when (entry.actionType) {
        AuditActionType.COMMAND_EXECUTED,
        AuditActionType.APPROVAL_GRANTED,
        AuditActionType.PERMISSION_GRANTED,
        AuditActionType.DEVICE_CONNECTED -> AuditResultStyle("Success", colors.success)

        AuditActionType.COMMAND_FAILED,
        AuditActionType.ERROR,
        AuditActionType.APPROVAL_TIMEOUT -> AuditResultStyle("Failed", colors.danger)

        AuditActionType.COMMAND_BLOCKED -> AuditResultStyle("Blocked", colors.riskBlocked)
        AuditActionType.APPROVAL_DENIED -> AuditResultStyle("Denied", colors.riskHigh)
        AuditActionType.APPROVAL_REQUESTED -> AuditResultStyle("Pending", colors.warning)

        else -> AuditResultStyle("Info", colors.textTertiary)
    }
}

private fun actionLabel(entry: AuditEntry): String {
    val commandAction = entry.command?.action
    val raw = commandAction?.name ?: entry.actionType.name
    return raw.replace('_', ' ')
        .lowercase()
        .replaceFirstChar { character -> character.uppercase() }
}

private fun groupByDay(entries: List<AuditEntry>): List<AuditDaySection> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val grouped = LinkedHashMap<LocalDate, MutableList<AuditEntry>>()
    entries.forEach { entry ->
        val date = Instant.ofEpochMilli(entry.timestamp).atZone(zone).toLocalDate()
        grouped.getOrPut(date) { mutableListOf() }.add(entry)
    }
    val dayFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
    return grouped.map { (date, dayEntries) ->
        val label = when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(dayFormatter)
        }
        AuditDaySection(
            key = date.toString(),
            label = label,
            entries = dayEntries.sortedByDescending { it.timestamp }
        )
    }.sortedByDescending { it.key }
}
