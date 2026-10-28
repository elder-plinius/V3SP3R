package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vesper.flipper.data.AiConnectionConfig
import com.vesper.flipper.data.AiProvider
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.data.ProviderModelPresets
import com.vesper.flipper.data.modelIdValidationError
import com.vesper.flipper.data.apiKeyValidationError
import com.vesper.flipper.ui.viewmodel.SettingsState

/** Memory-only drafts; persistence and paid tests are separate, explicit actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderConfiguration(
    state: SettingsState,
    models: List<ModelInfo>,
    refreshing: Boolean,
    onProvider: (AiProvider) -> Unit,
    onSave: (String?, String) -> Unit,
    onSaveKey: (AiProvider, String) -> Unit,
    onDraftEdited: (AiProvider, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var providerExpanded by remember { mutableStateOf(false) }
    var drafts by remember(state.provider) {
        mutableStateOf(ProviderDraftState(state.apiKey, state.selectedModel))
    }
    val observed = drafts.observe(state.apiKey, state.selectedModel)
    if (observed != drafts) drafts = observed
    val keyDraft = drafts.key
    val modelDraft = drafts.model
    var showKey by remember(state.provider) { mutableStateOf(false) }
    var modelExpanded by remember(state.provider) { mutableStateOf(false) }
    var presetExpanded by remember(state.provider) { mutableStateOf(false) }
    var filter by remember(state.provider) { mutableStateOf("") }
    var localMessage by remember(state.provider) { mutableStateOf<String?>(null) }
    val dirty = drafts.dirty
    val keyDirty = drafts.keyDirty
    val configError = AiConnectionConfig(state.provider, keyDraft, modelDraft).validationError()
    val modelError = modelIdValidationError(modelDraft)
    val keyError = apiKeyValidationError(keyDraft)
    val saveError = keyError ?: modelError.takeIf { modelDraft.isNotEmpty() }
    val actionsEnabled = state.providerReady && !state.isSavingKey &&
        !state.isSavingProviderSettings && !state.isTestingProvider
    val presets = ProviderModelPresets.forProvider(state.provider)
    val catalogNeedsKey = state.provider != AiProvider.OPENROUTER && state.apiKey.isEmpty()

    fun editModel(id: String) {
        drafts = drafts.editModel(id)
        localMessage = null
        onDraftEdited(state.provider, false)
    }
    fun dismissInput() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    ExposedDropdownMenuBox(
        expanded = providerExpanded,
        onExpandedChange = { if (actionsEnabled) providerExpanded = it }
    ) {
        OutlinedTextField(
            value = state.provider.displayName,
            onValueChange = {}, readOnly = true,
            label = { Text("AI provider") },
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
            AiProvider.entries.forEach { provider ->
                DropdownMenuItem(text = { Text(provider.displayName) }, enabled = actionsEnabled, onClick = {
                    providerExpanded = false
                    localMessage = null
                    onProvider(provider)
                })
            }
        }
    }
    Text(state.provider.baseUrl, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Each provider keeps its own key and model. Save edits before switching providers.",
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = keyDraft,
        onValueChange = {
            drafts = drafts.editKey(it)
            localMessage = null
            onDraftEdited(state.provider, true)
        },
        label = { Text("${state.provider.displayName} API key") },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.providerReady,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showKey) "Hide API key" else "Show API key")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        isError = keyDraft.isNotEmpty() && keyError != null,
        supportingText = { if (keyDraft.isNotEmpty() && keyError != null) Text(keyError) },
        shape = RoundedCornerShape(12.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            dismissInput()
            onSaveKey(state.provider, keyDraft)
        }, enabled = actionsEnabled && keyError == null) {
            if (state.isSavingKey) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp)); Text("Saving…")
            } else Text("Save key")
        }
        if (!state.isSavingKey && !keyDirty && state.apiKey.isNotEmpty() && !state.keySaveIsError) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Saved", color = MaterialTheme.colorScheme.primary)
        } else if (keyDirty) {
            Text("Unsaved key edits", style = MaterialTheme.typography.bodySmall)
        }
    }
    val keyMessage = when {
        state.isSavingKey -> "Saving key on this device…"
        state.keySaveIsError -> state.keySaveMessage
        keyDirty -> "Key edits are not saved. Save key does not change the model."
        state.apiKey.isNotEmpty() -> state.keySaveMessage ?: "Key saved on this device."
        else -> "No saved key for this provider."
    }
    keyMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (state.keySaveIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Saving is not a connection test. Use this provider's own key; it is never reused for another provider.",
        style = MaterialTheme.typography.bodySmall)
    TextButton(
        onClick = { dismissInput(); onSave(null, state.selectedModel) },
        enabled = actionsEnabled && state.apiKey.isNotEmpty()
    ) {
        Text("Remove saved key")
    }
    Spacer(Modifier.height(12.dp))

    Text("Suggested models (offline presets)", style = MaterialTheme.typography.titleSmall)
    ExposedDropdownMenuBox(expanded = presetExpanded,
        onExpandedChange = { if (state.providerReady) presetExpanded = it }) {
        OutlinedTextField(
            value = "Choose a suggested model", onValueChange = {}, readOnly = true,
            enabled = state.providerReady,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(presetExpanded) }
        )
        ExposedDropdownMenu(expanded = presetExpanded, onDismissRequest = { presetExpanded = false }) {
            presets.forEach { model ->
                DropdownMenuItem(text = {
                    Column {
                        Text(model.displayName)
                        Text(model.id, style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = { editModel(model.id); presetExpanded = false })
            }
        }
    }
    Text("Offline suggestions, not live availability. Choosing one only edits the exact ID below; save it explicitly.",
        style = MaterialTheme.typography.bodySmall)
    if (state.apiKey.isEmpty()) {
        Text("A saved ${state.provider.displayName} API key is required to use a model, including an offline suggestion.",
            style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = modelDraft,
        onValueChange = ::editModel,
        label = { Text("Exact model ID") },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.providerReady,
        singleLine = true,
        isError = modelDraft.isNotEmpty() && modelError != null,
        supportingText = {
            Text(modelError ?: "Sent exactly as shown: capitalization, prefixes and suffixes are preserved.")
        },
        keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Ascii),
        shape = RoundedCornerShape(12.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            try {
                val text = clipboard.getText()?.text
                if (text.isNullOrEmpty()) localMessage = "Clipboard has no text. Copy the model ID first."
                else editModel(text)
            } catch (_: Exception) {
                localMessage = "Clipboard unavailable. Long-press the model field to paste."
            }
        }, enabled = state.providerReady) {
            Icon(Icons.Default.ContentPaste, contentDescription = null)
            Spacer(Modifier.width(4.dp)); Text("Paste ID")
        }
        TextButton(onClick = {
            try { uriHandler.openUri(state.provider.catalogUrl) }
            catch (_: Exception) { localMessage = "No browser available to open the provider catalog." }
        }) {
            Icon(Icons.Default.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(4.dp)); Text("Open catalog")
        }
    }

    Text("Live provider model list", style = MaterialTheme.typography.titleSmall)
    TextButton(onClick = onRefresh, enabled = actionsEnabled && !refreshing && !catalogNeedsKey) {
        if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(6.dp)); Text(if (refreshing) "Loading models…" else "Load provider model list")
    }
    Text(if (catalogNeedsKey) "Save a ${state.provider.displayName} API key above to load its live catalog. Offline presets remain available."
        else if (keyDirty) "Live catalog uses the saved key, not unsaved key edits. Model edits do not need to be saved to refresh."
        else if (state.provider == AiProvider.OPENROUTER && state.apiKey.isEmpty()) "OpenRouter's public live catalog can be loaded without a key. Using a model still requires a saved key."
        else "Live catalog uses the saved provider key. Loading does not select or save a model.",
        style = MaterialTheme.typography.bodySmall)
    if (models.isNotEmpty()) {
        OutlinedTextField(value = filter, onValueChange = { filter = it },
            label = { Text("Filter live models") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        val filtered = models.filter {
            it.id.contains(filter, ignoreCase = true) || it.displayName.contains(filter, ignoreCase = true)
        }
        ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
            OutlinedTextField(
                value = "Choose from ${filtered.size} matching models", onValueChange = {}, readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) }
            )
            ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                filtered.take(100).forEach { model ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(model.displayName)
                            Text(model.id, style = MaterialTheme.typography.bodySmall)
                        }
                    }, onClick = { editModel(model.id); modelExpanded = false })
                }
                if (filtered.size > 100) {
                    DropdownMenuItem(text = { Text("First 100 shown; use the filter to narrow results.") },
                        onClick = {}, enabled = false)
                }
            }
        }
    }
    state.catalogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text("No silent model substitution. Device control requires a model that supports function/tool calling.",
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { dismissInput(); onSave(keyDraft, modelDraft) },
            enabled = actionsEnabled && saveError == null) {
            Text(if (state.isSavingProviderSettings) "Saving…" else "Save key + model")
        }
        OutlinedButton(onClick = { dismissInput(); onTest() },
            enabled = actionsEnabled && !dirty && configError == null) {
            if (state.isTestingProvider) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(if (state.isTestingProvider) "Testing…" else "Test model")
        }
    }
    if (dirty) Text("Unsaved changes. Save key + model before testing. Save key alone keeps model edits here.",
        style = MaterialTheme.typography.bodySmall)
    Text("Test model sends a small API request and may incur a charge. It does not operate the Flipper.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    localMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    state.visibleProviderMessage(dirty)?.takeUnless { it == state.keySaveMessage }?.let {
        Text(it, color = if (state.providerMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
    }
}
