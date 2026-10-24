package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.vesper.flipper.BuildConfig
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VMetricRow
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/** About: version, source, and the honest disclaimer. */
fun LazyListScope.aboutSection() {
    item(key = "settings.about.header") {
        VSectionHeader(title = "About")
    }
    item(key = "settings.about.card") {
        AboutCard()
    }
}

@Composable
private fun AboutCard() {
    val colors = Vesper.colors
    val uriHandler = LocalUriHandler.current

    VCard(level = 1) {
        Text(
            text = "VESPER",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary
        )
        Text(
            text = "Command center for the Flipper Zero",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

        VMetricRow(label = "Version", value = BuildConfig.VERSION_NAME)
        VMetricRow(label = "Minimum Android", value = "8.0")

        Spacer(Modifier.height(VesperSpace.sm))

        Text(
            text = "Every action is risk-classified, diffed, and logged before it touches the device. " +
                "Use it only on hardware you own or have written permission to test.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary
        )

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching { uriHandler.openUri("https://github.com/elder-plinius/V3SP3R") }
                }
                .padding(vertical = VesperSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Source and issue tracker",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "github.com/elder-plinius/V3SP3R",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary
                )
            }
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
