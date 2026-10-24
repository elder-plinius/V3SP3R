package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.SettingsViewModel

/** Experience: theme and touch feedback. */
fun LazyListScope.experienceSection(viewModel: SettingsViewModel) {
    item(key = "settings.experience.header") {
        VSectionHeader(title = "Experience", eyebrow = "LOOK AND FEEL")
    }
    item(key = "settings.experience.card") {
        ExperienceCard(viewModel)
    }
}

@Composable
private fun ExperienceCard(viewModel: SettingsViewModel) {
    val colors = Vesper.colors
    val state by viewModel.state.collectAsState()

    VCard(level = 1) {
        SettingsToggle(
            title = "Dark theme",
            subtitle = "The light theme is fully specified, not a fallback",
            checked = state.darkMode,
            onCheckedChange = viewModel::setDarkMode
        )

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.xs))

        SettingsToggle(
            title = "Haptics",
            subtitle = "Short vibration on confirms and destructive holds",
            checked = state.hapticFeedback,
            onCheckedChange = viewModel::setHapticFeedback
        )

        Spacer(Modifier.height(VesperSpace.sm))
        Text(
            text = "Text size follows the system setting. Nothing here is smaller than 12sp.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary
        )
    }
}
