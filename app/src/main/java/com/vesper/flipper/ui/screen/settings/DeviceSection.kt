package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.SettingsViewModel

/** Device: connection behaviour and the folders the executor may touch. */
fun LazyListScope.deviceSection(viewModel: SettingsViewModel) {
    item(key = "settings.device.header") {
        VSectionHeader(title = "Device", eyebrow = "CONNECTION AND SCOPE")
    }
    item(key = "settings.device.connection") {
        ConnectionCard(viewModel)
    }
    item(key = "settings.device.project") {
        ProjectAccessCard(viewModel)
    }
}

@Composable
private fun ConnectionCard(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    VCard(level = 1) {
        SettingsToggle(
            title = "Auto-connect",
            subtitle = "Remember the last Flipper and reconnect when Device opens",
            checked = state.autoConnect,
            onCheckedChange = viewModel::setAutoConnect
        )
    }
}

@Composable
private fun ProjectAccessCard(viewModel: SettingsViewModel) {
    val colors = Vesper.colors
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current
    var pathDraft by remember(state.defaultProjectPath) { mutableStateOf(state.defaultProjectPath) }
    var hadFocus by remember { mutableStateOf(false) }

    val commitPath: () -> Unit = {
        val trimmed = pathDraft.trim()
        if (trimmed.isNotEmpty() && trimmed != state.defaultProjectPath) {
            viewModel.setDefaultProjectPath(trimmed)
        }
    }

    VCard(level = 1) {
        SettingsGroupLabel("Project folder")
        Spacer(Modifier.height(VesperSpace.xs))
        OutlinedTextField(
            value = pathDraft,
            onValueChange = { pathDraft = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (hadFocus && !focusState.isFocused) commitPath()
                    hadFocus = focusState.isFocused
                },
            label = { Text("Path on the Flipper") },
            placeholder = { Text("/ext/apps_data/vesper") },
            singleLine = true,
            supportingText = {
                Text("Writes inside this folder can be MEDIUM. Everything else is HIGH.")
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    commitPath()
                }
            ),
            shape = MaterialTheme.shapes.medium,
            colors = vesperFieldColors()
        )

        Spacer(Modifier.height(VesperSpace.xs))

        VPrimaryButton(
            text = "Grant project scope",
            onClick = viewModel::grantProjectPermission,
            modifier = Modifier.fillMaxWidth(),
            enabled = pathDraft.isNotBlank(),
            compact = true
        )
        SettingsHint("Grants read, write, and create for this folder for 24 hours.")

        val grants = state.activePermissions
        if (grants.isNotEmpty()) {
            VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))
            SettingsGroupLabel("Active grants")
            Spacer(Modifier.height(VesperSpace.xxs))
            grants.forEachIndexed { index, permission ->
                VListRow(
                    title = permission.pathPattern,
                    subtitle = "Expires in " + formatRemaining(permission.remainingTimeMs()),
                    trailing = {
                        IconButton(onClick = { viewModel.revokePermission(permission.id) }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Revoke this grant",
                                tint = colors.riskHigh,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
                if (index < grants.lastIndex) VHairline()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                VQuietButton(
                    text = "Revoke all",
                    onClick = viewModel::revokeAllPermissions
                )
            }
        } else {
            Text(
                text = "No grants. Every write asks first.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = VesperSpace.xs)
            )
        }
    }
}
