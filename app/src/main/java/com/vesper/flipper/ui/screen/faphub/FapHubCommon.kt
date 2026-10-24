package com.vesper.flipper.ui.screen.faphub

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import com.vesper.flipper.domain.model.FapCategory
import com.vesper.flipper.domain.model.FlipperResourceType
import com.vesper.flipper.ui.theme.Vesper
import java.util.Locale

/** Material icon for each app category. The domain enum also carries emoji. */
fun FapCategory.icon(): ImageVector = when (this) {
    FapCategory.GAMES -> Icons.Rounded.SportsEsports
    FapCategory.TOOLS -> Icons.Rounded.Build
    FapCategory.NFC -> Icons.Rounded.Nfc
    FapCategory.SUBGHZ -> Icons.Rounded.Sensors
    FapCategory.INFRARED -> Icons.Rounded.SettingsRemote
    FapCategory.GPIO -> Icons.Rounded.Memory
    FapCategory.BLUETOOTH -> Icons.Rounded.Bluetooth
    FapCategory.USB -> Icons.Rounded.Usb
    FapCategory.MEDIA -> Icons.Rounded.MusicNote
    FapCategory.MISC -> Icons.Rounded.Widgets
}

fun FlipperResourceType.icon(): ImageVector = when (this) {
    FlipperResourceType.IR_REMOTE -> Icons.Rounded.SettingsRemote
    FlipperResourceType.SUB_GHZ -> Icons.Rounded.Sensors
    FlipperResourceType.BAD_USB -> Icons.Rounded.Usb
    FlipperResourceType.NFC_FILES -> Icons.Rounded.Nfc
    FlipperResourceType.EVIL_PORTAL -> Icons.Rounded.Wifi
    FlipperResourceType.MUSIC -> Icons.Rounded.MusicNote
    FlipperResourceType.ANIMATIONS -> Icons.Rounded.Movie
    FlipperResourceType.GPIO_TOOLS -> Icons.Rounded.Memory
}

/** Shared search field for both tabs. */
@Composable
fun FapHubSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.textTertiary
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Clear,
                        contentDescription = "Clear the search",
                        tint = colors.textSecondary
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.medium,
        colors = fapHubFieldColors()
    )
}

@Composable
fun fapHubFieldColors(): TextFieldColors {
    val colors = Vesper.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedContainerColor = colors.surface1,
        unfocusedContainerColor = colors.surface1,
        cursorColor = colors.gold,
        focusedBorderColor = colors.gold,
        unfocusedBorderColor = colors.hairline,
        focusedPlaceholderColor = colors.textTertiary,
        unfocusedPlaceholderColor = colors.textTertiary
    )
}

fun formatDownloads(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000f)
    count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000f)
    else -> count.toString()
}

fun formatStars(count: Int): String = when {
    count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000f)
    else -> count.toString()
}

fun formatRating(rating: Float): String = String.format(Locale.US, "%.1f", rating)

fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "Unknown"
    bytes < 1024L -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
}
