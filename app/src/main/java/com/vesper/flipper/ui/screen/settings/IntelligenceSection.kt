package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.KeyTestResult
import com.vesper.flipper.ui.viewmodel.SettingsViewModel
import com.vesper.flipper.voice.OpenRouterTtsService

/**
 * Intelligence: model access, the reasoning loop, and voice.
 *
 * The key field is draft-based. It writes to the store on Done, on focus loss,
 * or on the explicit save, never on each keystroke.
 */
fun LazyListScope.intelligenceSection(viewModel: SettingsViewModel) {
    item(key = "settings.intelligence.header") {
        VSectionHeader(title = "Intelligence", eyebrow = "MODEL ACCESS")
    }
    item(key = "settings.intelligence.key") {
        ApiKeyCard(viewModel)
    }
    item(key = "settings.intelligence.model") {
        ModelAndVoiceCard(viewModel)
    }
}

@Composable
private fun ApiKeyCard(viewModel: SettingsViewModel) {
    val colors = Vesper.colors
    val state by viewModel.state.collectAsState()
    val keyUi by viewModel.keyUi.collectAsState()
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    var hadFocus by remember { mutableStateOf(false) }

    val saveDraft: () -> Unit = {
        if (keyUi.draft.isNotBlank()) viewModel.saveApiKey()
    }

    VCard(level = 1) {
        SettingsGroupLabel("API key")
        Spacer(Modifier.height(VesperSpace.xs))
        OutlinedTextField(
            value = keyUi.draft,
            onValueChange = viewModel::updateKeyDraft,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (hadFocus && !focusState.isFocused) saveDraft()
                    hadFocus = focusState.isFocused
                },
            label = { Text("OpenRouter key") },
            placeholder = {
                Text(
                    if (state.hasApiKey && state.apiKeyHint.isNotBlank()) {
                        "Saved key " + state.apiKeyHint
                    } else {
                        "sk-or-v1-..."
                    }
                )
            },
            singleLine = true,
            isError = keyUi.formatError != null,
            supportingText = {
                Text(keyUi.formatError ?: "Starts with sk-or-. Encrypted on this device before it is stored.")
            },
            visualTransformation = if (keyUi.revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    saveDraft()
                }
            ),
            trailingIcon = {
                IconButton(onClick = viewModel::toggleKeyReveal) {
                    Icon(
                        imageVector = if (keyUi.revealed) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (keyUi.revealed) "Hide key" else "Show key",
                        tint = colors.textSecondary
                    )
                }
            },
            shape = MaterialTheme.shapes.medium,
            colors = vesperFieldColors()
        )

        Spacer(Modifier.height(VesperSpace.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VPrimaryButton(
                text = "Save key",
                onClick = viewModel::saveApiKey,
                modifier = Modifier.weight(1f),
                enabled = keyUi.draft.isNotBlank(),
                loading = keyUi.saving,
                compact = true
            )
            VSecondaryButton(
                text = "Test connection",
                onClick = viewModel::testConnection,
                modifier = Modifier.weight(1f),
                enabled = !keyUi.testing
            )
        }

        when {
            keyUi.testing -> {
                Spacer(Modifier.height(VesperSpace.xs))
                VInlineLoading(text = "Checking the key")
            }
            keyUi.testResult is KeyTestResult.Success -> {
                Spacer(Modifier.height(VesperSpace.xs))
                SettingsNotice(
                    text = (keyUi.testResult as KeyTestResult.Success).message,
                    tone = NoticeTone.SUCCESS
                )
            }
            keyUi.testResult is KeyTestResult.Failure -> {
                Spacer(Modifier.height(VesperSpace.xs))
                SettingsNotice(
                    text = (keyUi.testResult as KeyTestResult.Failure).message,
                    tone = NoticeTone.ERROR
                )
            }
            keyUi.saved -> {
                Spacer(Modifier.height(VesperSpace.xs))
                SettingsNotice(
                    text = "Key saved. New requests use it immediately.",
                    tone = NoticeTone.SUCCESS
                )
            }
        }

        Spacer(Modifier.height(VesperSpace.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VQuietButton(
                text = "Get a key at openrouter.ai",
                onClick = {
                    runCatching { uriHandler.openUri("https://openrouter.ai/keys") }
                }
            )
            Spacer(Modifier.weight(1f))
            if (state.hasApiKey && keyUi.draft.isBlank()) {
                VQuietButton(text = "Remove", onClick = viewModel::clearApiKey)
            }
        }

        if (state.hasApiKey) {
            SettingsHint("Stored key " + state.apiKeyHint + " is used for chat, the Forge, and voice.")
        }
    }
}

@Composable
private fun ModelAndVoiceCard(viewModel: SettingsViewModel) {
    val colors = Vesper.colors
    val state by viewModel.state.collectAsState()
    val models by viewModel.availableModels.collectAsState()
    var showModelPicker by remember { mutableStateOf(false) }

    VCard(level = 1) {
        SettingsGroupLabel("Model")
        Spacer(Modifier.height(VesperSpace.xxs))
        SettingsValueRow(
            title = modelNameFor(models, state.selectedModel),
            subtitle = providerLabel(state.selectedModel) + " - active model",
            value = "Change",
            onClick = { showModelPicker = true }
        )
        SettingsHint("Tool calls fall back across models when the active one refuses a tool.")

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

        SettingsGroupLabel("Reasoning loop")
        Spacer(Modifier.height(VesperSpace.xxs))
        SettingsChipRow(
            options = listOf(
                "6" to "6", "8" to "8", "10" to "10",
                "12" to "12", "15" to "15", "20" to "20"
            ),
            selectedId = state.aiMaxIterations.toString(),
            onSelect = { viewModel.setAiMaxIterations(it.toInt()) }
        )
        SettingsHint(
            "Rounds the agent may take before it stops. " +
                state.aiMaxIterations + " is the current ceiling; more is more persistent, not faster."
        )

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

        SettingsGroupLabel("Voice")
        Spacer(Modifier.height(VesperSpace.xxs))
        SettingsToggle(
            title = "Speak replies aloud",
            subtitle = "Reads answers with OpenRouter audio using the same key",
            checked = state.ttsEnabled,
            onCheckedChange = viewModel::setTtsEnabled
        )
        if (state.ttsEnabled) {
            Spacer(Modifier.height(VesperSpace.xs))
            SettingsChipRow(
                options = OpenRouterTtsService.AVAILABLE_VOICES.map { it.id to it.name },
                selectedId = state.ttsVoiceId,
                onSelect = viewModel::setTtsVoiceId
            )
            val voice = OpenRouterTtsService.AVAILABLE_VOICES.find { it.id == state.ttsVoiceId }
            SettingsHint(voice?.description ?: "Pick a voice for spoken replies.")
            Spacer(Modifier.height(VesperSpace.xs))
            SettingsToggle(
                title = "Auto-speak",
                subtitle = "Speaks every completed answer without being asked",
                checked = state.ttsAutoSpeak,
                onCheckedChange = viewModel::setTtsAutoSpeak
            )
        } else {
            Text(
                text = "Off means replies stay text-only.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary
            )
        }
    }

    if (showModelPicker) {
        val query by viewModel.modelQuery.collectAsState()
        val filtered by viewModel.filteredModels.collectAsState()
        val refreshing by viewModel.isRefreshingModels.collectAsState()
        ModelPickerSheet(
            models = filtered,
            query = query,
            onQueryChange = viewModel::setModelQuery,
            selectedModel = state.selectedModel,
            isRefreshing = refreshing,
            onRefresh = viewModel::refreshAvailableModels,
            onSelect = { modelId ->
                viewModel.setSelectedModel(modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}

