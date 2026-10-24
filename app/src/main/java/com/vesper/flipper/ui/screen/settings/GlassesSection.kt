package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.SettingsViewModel

/** Smart glasses: an optional second screen for the agent. */
fun LazyListScope.glassesCard(viewModel: SettingsViewModel) {
    item(key = "settings.device.glasses") {
        SmartGlassesCard(viewModel)
    }
}

@Composable
private fun SmartGlassesCard(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current
    var urlDraft by remember(state.glassesBridgeUrl) { mutableStateOf(state.glassesBridgeUrl) }
    var hadFocus by remember { mutableStateOf(false) }

    VCard(level = 1) {
        SettingsGroupLabel("Smart glasses")
        Spacer(Modifier.height(VesperSpace.xxs))
        SettingsToggle(
            title = "Connect smart glasses",
            subtitle = "Adds camera and voice actions to the agent",
            checked = state.glassesEnabled,
            onCheckedChange = viewModel::setGlassesEnabled
        )

        if (state.glassesEnabled) {
            Spacer(Modifier.height(VesperSpace.sm))
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (hadFocus && !focusState.isFocused && urlDraft != state.glassesBridgeUrl) {
                            viewModel.setGlassesBridgeUrl(urlDraft.trim())
                        }
                        hadFocus = focusState.isFocused
                    },
                label = { Text("Bridge server") },
                placeholder = { Text("ws://your-server:8089") },
                singleLine = true,
                supportingText = { Text("Your Mentra bridge URL. Use ngrok while developing.") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        viewModel.setGlassesBridgeUrl(urlDraft.trim())
                    }
                ),
                shape = MaterialTheme.shapes.medium,
                colors = vesperFieldColors()
            )

            VHairline(modifier = Modifier.padding(vertical = VesperSpace.xs))

            SettingsToggle(
                title = "Send voice automatically",
                subtitle = "Glasses transcriptions go straight to the agent",
                checked = state.glassesAutoSend,
                onCheckedChange = viewModel::setGlassesAutoSend
            )
            SettingsToggle(
                title = "Reconnect on launch",
                subtitle = "Tries the bridge as soon as the app opens",
                checked = state.glassesAutoConnect,
                onCheckedChange = viewModel::setGlassesAutoConnect
            )
            SettingsToggle(
                title = "Sailor mouth",
                subtitle = "Vesper swears in replies. Off by default",
                checked = state.glassesSailorMouth,
                onCheckedChange = viewModel::setGlassesSailorMouth
            )
            SettingsToggle(
                title = "Mute the glasses mic",
                subtitle = "Pauses listening until you unmute",
                checked = state.glassesMuted,
                onCheckedChange = viewModel::setGlassesMuted
            )
        }
    }
}
