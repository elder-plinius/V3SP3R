package com.vesper.flipper.ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.flipper.domain.model.ProtectedPaths
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.domain.model.ToolCall
import com.vesper.flipper.domain.model.ToolResult
import com.vesper.flipper.ui.components.VRiskBadge
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Tool call with its real action, target, arguments and risk.
 *
 * The JSON here is the same ExecuteCommand payload the agent executed. The
 * risk shown is the executor assessment when this call produced a pending
 * approval; otherwise it is a display estimate derived from the action class.
 * The executor remains authoritative and re-assesses before running anything.
 */
@Composable
fun ToolCallCard(
    toolCall: ToolCall,
    approvedRisk: RiskLevel? = null,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val parsed = remember(toolCall.id, toolCall.arguments) { parseToolCall(toolCall.arguments) }
    var expanded by rememberSaveable(toolCall.id) { mutableStateOf(false) }

    val risk = approvedRisk ?: parsed?.estimatedRisk
    val riskIsEstimated = approvedRisk == null && risk != null

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = colors.surface1,
        border = BorderStroke(1.dp, colors.hairlineSoft)
    ) {
        Column(modifier = Modifier.padding(VesperSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = colors.gold,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(VesperSpace.xs))
                Text(
                    text = parsed?.actionLabel ?: "Tool call",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (risk != null) {
                    Box(
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = (if (riskIsEstimated) "Estimated risk: " else "Assessed risk: ") +
                                risk.name.lowercase()
                        }
                    ) {
                        VRiskBadge(level = risk)
                    }
                }
            }

            val target = parsed?.target
            if (target != null) {
                Spacer(Modifier.size(VesperSpace.xxs))
                Text(
                    text = target,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val argRows = parsed?.args.orEmpty()
            if (argRows.isNotEmpty()) {
                Spacer(Modifier.size(VesperSpace.xxs))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    argRows.take(4).forEach { (label, value) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary,
                                modifier = Modifier.width(92.dp)
                            )
                            Spacer(Modifier.width(VesperSpace.xxs))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = VesperSpace.xs)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.rose,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(VesperSpace.xxs))
                Text(
                    text = if (expanded) "Hide raw payload" else "Show raw payload",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.rose
                )
            }

            AnimatedVisibility(visible = expanded) {
                val vertical = rememberScrollState()
                val horizontal = rememberScrollState()
                val raw = remember(toolCall.arguments) {
                    if (toolCall.arguments.length <= MAX_RAW_CHARS) {
                        toolCall.arguments
                    } else {
                        toolCall.arguments.take(MAX_RAW_CHARS) + "\n\u2026 truncated for display"
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(colors.surface2)
                        .verticalScroll(vertical)
                        .horizontalScroll(horizontal)
                        .padding(VesperSpace.xs)
                ) {
                    Text(
                        text = raw,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}

/** Result block for a tool execution, including a readable failure reason. */
@Composable
fun ToolResultBlock(
    toolResult: ToolResult,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val parsed = remember(toolResult.content, toolResult.success) { parseToolResult(toolResult) }
    val accent = if (parsed.success) colors.success else colors.riskHigh
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (parsed.success) colors.success.copy(alpha = 0.10f) else colors.riskHighContainer,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(VesperSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (parsed.success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(VesperSpace.xxs))
                Text(
                    text = parsed.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (parsed.detail.isNotBlank()) {
                Spacer(Modifier.size(VesperSpace.xxs))
                Text(
                    text = parsed.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private const val MAX_RAW_CHARS = 4_000

private val toolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private data class ParsedToolCall(
    val action: String?,
    val actionLabel: String,
    val target: String?,
    val args: List<Pair<String, String>>,
    val estimatedRisk: RiskLevel?
)

private fun parseToolCall(arguments: String): ParsedToolCall? {
    val root = runCatching { toolJson.parseToJsonElement(arguments).jsonObjectOrNull() }.getOrNull()
        ?: return null
    val action = root["action"].stringValue()
    val args = root["args"].objectValue()

    val path = args?.get("path").stringValue()
    val destination = args?.get("destination_path").stringValue()
    val command = args?.get("command").stringValue()
    val content = args?.get("content").stringValue()
    val artifact = args?.get("artifact_data").stringValue()

    val target = path
        ?: destination
        ?: command?.let { previewText(it, 90) }
        ?: args?.get("app_name").stringValue()?.let { previewText(it, 90) }
        ?: content?.let { previewText(it, 90) }

    val rows = mutableListOf<Pair<String, String>>()
    val consumed = mutableSetOf("path", "destination_path", "command", "content", "artifact_data")
    for ((key, label) in ARG_LABELS) {
        if (key in consumed) continue
        val raw = args?.get(key) ?: continue
        val value = when (raw) {
            is JsonPrimitive -> raw.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            else -> continue
        }
        val rendered = if (key == "frequency") value + " Hz" else previewText(value, 120)
        rows += label to rendered
    }
    if (content != null && content.isNotBlank()) {
        rows += "Content" to (content.length.toString() + " characters")
    }
    if (artifact != null && artifact.isNotBlank()) {
        rows += "Payload" to (artifact.length.toString() + " characters")
    }

    return ParsedToolCall(
        action = action,
        actionLabel = commandActionLabel(action),
        target = target,
        args = rows,
        estimatedRisk = estimateRisk(action, path ?: destination, command ?: content)
    )
}

/**
 * Display-only estimate. Mirrors the action classes in the executor RiskAssessor
 * so the transcript is readable; never used to decide execution.
 */
private fun estimateRisk(action: String?, path: String?, command: String?): RiskLevel? {
    if (action.isNullOrBlank()) return null
    if (path != null && ProtectedPaths.isProtected(path)) return RiskLevel.BLOCKED
    return when (action.lowercase()) {
        "delete", "move", "rename", "install_faphub_app", "badusb_execute" -> RiskLevel.HIGH
        "execute_cli" -> {
            val normalized = command?.trim()?.lowercase().orEmpty()
            when {
                normalized.isBlank() -> RiskLevel.HIGH
                SAFE_CLI_PREFIXES.any { normalized.startsWith(it) } -> RiskLevel.LOW
                MEDIUM_CLI_PREFIXES.any { normalized.startsWith(it) } -> RiskLevel.MEDIUM
                else -> RiskLevel.HIGH
            }
        }
        "write_file", "create_directory", "copy", "push_artifact", "download_resource",
        "forge_payload", "run_runbook", "launch_app", "subghz_transmit", "ir_transmit",
        "nfc_emulate", "rfid_emulate", "ibutton_emulate", "ble_spam" -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}

private val SAFE_CLI_PREFIXES = listOf(
    "help", "version", "device_info", "device info", "info",
    "storage list", "storage ls", "storage read", "storage cat", "storage info", "storage stat",
    "led ", "vibro "
)

private val MEDIUM_CLI_PREFIXES = listOf(
    "loader open", "loader list", "loader info",
    "subghz tx", "subghz tx_from_file", "ir tx", "infrared tx",
    "nfc emulate", "nfc emu", "rfid emulate", "rfid emu",
    "lfrfid emulate", "lfrfid emu", "ibutton emulate", "ibutton emu",
    "ble_spam", "blespam", "ble spam", "ble_scan", "blescan", "ble scan"
)

private val ARG_LABELS = listOf(
    "path" to "Path",
    "destination_path" to "Destination",
    "new_name" to "New name",
    "command" to "Command",
    "app_name" to "App",
    "app_args" to "App arguments",
    "frequency" to "Frequency",
    "protocol" to "Protocol",
    "address" to "Address",
    "signal_name" to "Signal",
    "artifact_type" to "Artifact",
    "resource_type" to "Resource",
    "payload_type" to "Payload type",
    "runbook_id" to "Runbook",
    "repo_id" to "Repository",
    "download_url" to "Download",
    "prompt" to "Prompt",
    "photo_prompt" to "Photo prompt",
    "search_scope" to "Scope",
    "filter" to "Filter",
    "recursive" to "Recursive",
    "enabled" to "Enabled"
)

private data class ParsedToolResult(
    val success: Boolean,
    val title: String,
    val detail: String
)

private fun parseToolResult(toolResult: ToolResult): ParsedToolResult {
    val root = runCatching { toolJson.parseToJsonElement(toolResult.content).jsonObjectOrNull() }.getOrNull()
        ?: return ParsedToolResult(
            success = toolResult.success,
            title = if (toolResult.success) "Command completed" else "Command failed",
            detail = previewText(toolResult.content, 160)
        )

    val success = root["success"]?.booleanValue() ?: toolResult.success
    val action = root["action"].stringValue()
    val error = root["error"].stringValue()
    val data = root["data"].objectValue()
    val message = data?.get("message").stringValue()
    val content = data?.get("content").stringValue()
    val detail = when {
        !error.isNullOrBlank() -> error
        !message.isNullOrBlank() -> message
        !content.isNullOrBlank() -> content.lineSequence().firstOrNull().orEmpty()
        else -> previewText(toolResult.content, 160)
    }
    return ParsedToolResult(
        success = success,
        title = if (action != null) commandActionLabel(action) else if (success) "Command completed" else "Command failed",
        detail = previewText(detail, 400)
    )
}

private fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonElement?.objectValue(): JsonObject? = this as? JsonObject

private fun JsonElement?.booleanValue(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
