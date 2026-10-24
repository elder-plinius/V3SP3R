package com.vesper.flipper.ui.screen.alchemy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vesper.flipper.domain.model.PayloadType
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Icons for payload types. The domain enum carries emoji strings from the
 * original prototype; the UI maps to Material icons instead.
 */
fun PayloadType.icon(): ImageVector = when (this) {
    PayloadType.SUB_GHZ -> Icons.Rounded.Sensors
    PayloadType.INFRARED -> Icons.Rounded.SettingsRemote
    PayloadType.NFC -> Icons.Rounded.Nfc
    PayloadType.RFID -> Icons.Rounded.Tag
    PayloadType.BAD_USB -> Icons.Rounded.Usb
    PayloadType.IBUTTON -> Icons.Rounded.Key
    PayloadType.UNKNOWN -> Icons.Rounded.InsertDriveFile
}

/**
 * Risk label for a generated payload.
 *
 * This is a UI classification, not the executor's: commands are classified by
 * the filesystem operation, while a payload's danger comes from what it does
 * once the Flipper runs it. The badge is labelled "Payload risk" wherever it
 * appears so the two are not confused.
 */
fun PayloadType.riskLevel(): RiskLevel = when (this) {
    PayloadType.BAD_USB -> RiskLevel.HIGH
    PayloadType.SUB_GHZ -> RiskLevel.MEDIUM
    PayloadType.NFC -> RiskLevel.MEDIUM
    PayloadType.RFID -> RiskLevel.MEDIUM
    PayloadType.IBUTTON -> RiskLevel.MEDIUM
    PayloadType.INFRARED -> RiskLevel.LOW
    PayloadType.UNKNOWN -> RiskLevel.MEDIUM
}

/** One-line reason for the payload risk badge. */
fun PayloadType.riskReason(): String = when (this) {
    PayloadType.BAD_USB -> "Types commands into a host over USB"
    PayloadType.SUB_GHZ -> "Transmits on Sub-GHz frequencies"
    PayloadType.NFC -> "Reads or emulates NFC cards"
    PayloadType.RFID -> "Reads or emulates low-frequency tags"
    PayloadType.IBUTTON -> "Reads or emulates iButton keys"
    PayloadType.INFRARED -> "Sends infrared codes to appliances"
    PayloadType.UNKNOWN -> "Behaviour depends on the file contents"
}

/** Monospace, scrollable code surface. */
@Composable
fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 220.dp
) {
    val colors = Vesper.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(MaterialTheme.shapes.small)
            .background(colors.surface1)
            .border(1.dp, colors.hairlineSoft, MaterialTheme.shapes.small)
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(VesperSpace.sm)
        )
    }
}

/** Dialog shell matching the settings screens. */
@Composable
fun AlchemyDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        VCard(
            level = 3,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(VesperSpace.lg)
        ) {
            content()
        }
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))

fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
}

fun lineCount(code: String): Int =
    if (code.isBlank()) 0 else code.lines().size
