package com.vesper.flipper.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

private data class StarterPrompt(
    val prompt: String,
    val hint: String,
    val icon: ImageVector
)

private val STARTER_PROMPTS = listOf(
    StarterPrompt(
        prompt = "What's on my Flipper?",
        hint = "List the folders and files on the device",
        icon = Icons.Rounded.FolderOpen
    ),
    StarterPrompt(
        prompt = "Read my Sub-GHz captures",
        hint = "Browse the signals saved on the SD card",
        icon = Icons.Rounded.Sensors
    ),
    StarterPrompt(
        prompt = "Check the battery and firmware version",
        hint = "Read the device status and reported version",
        icon = Icons.Rounded.BatteryChargingFull
    )
)

/**
 * Empty transcript that reflects what the app can actually do right now:
 * pair a device, add a model key, or run one of three real commands.
 */
@Composable
fun EmptyChatState(
    hasDevice: Boolean,
    hasApiKey: Boolean,
    onNavigateToSettings: () -> Unit,
    onSendPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = VesperSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            !hasDevice -> {
                VEmptyState(
                    title = "Connect a Flipper",
                    message = "Pair your device from the Device tab. Chat can then read, write, and transmit on your behalf.",
                    icon = Icons.Rounded.BluetoothDisabled
                )
            }

            !hasApiKey -> {
                VEmptyState(
                    title = "Add your OpenRouter key",
                    message = "Vesper needs a model key to answer. The key is stored encrypted on this phone and never leaves it unencrypted.",
                    icon = Icons.Rounded.Key,
                    actionLabel = "Open settings",
                    onAction = onNavigateToSettings
                )
            }

            else -> {
                VEmptyState(
                    title = "Ask your Flipper anything",
                    message = "Every command is risk-checked before it runs. High-risk actions wait for your hold.",
                    icon = Icons.Rounded.Terminal
                )

                Spacer(Modifier.size(VesperSpace.xs))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = VesperSpace.gutter),
                    verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
                ) {
                    Text(
                        text = "Start with a prompt",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(
                            start = VesperSpace.xxs,
                            bottom = VesperSpace.xxs
                        )
                    )
                    STARTER_PROMPTS.forEach { starter ->
                        VCard(
                            level = 1,
                            onClick = { onSendPrompt(starter.prompt) },
                            contentPadding = PaddingValues(
                                horizontal = VesperSpace.md,
                                vertical = VesperSpace.sm
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = starter.icon,
                                    contentDescription = null,
                                    tint = colors.gold,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(VesperSpace.sm))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = starter.prompt,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = starter.hint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.size(VesperSpace.md))

                Text(
                    text = "You can attach a photo or use voice input.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = VesperSpace.xl)
                )
            }
        }
    }
}
