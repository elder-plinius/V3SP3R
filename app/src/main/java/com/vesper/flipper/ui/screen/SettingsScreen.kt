package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.Permission
import com.vesper.flipper.ui.theme.*
import com.vesper.flipper.ui.viewmodel.SettingsViewModel
import com.vesper.flipper.voice.OpenRouterTtsService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val isRefreshingModels by viewModel.isRefreshingModels.collectAsState()
    var showApiKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Configuration Section
            item {
                SettingsSection(title = "API Configuration") {
                    // API Key
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = { viewModel.setApiKey(it) },
                        label = { Text("OpenRouter API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "Hide" else "Show"
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text(
                        text = "Paste your OpenRouter key here (starts with \"sk-or-\").",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Model Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "AI Model",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { viewModel.refreshAvailableModels() },
                            enabled = !isRefreshingModels
                        ) {
                            if (isRefreshingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refreshing")
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Refresh")
                            }
                        }
                    }
                    Text(
                        text = "Tool calls auto-fallback across multiple models; if it still fails, pick a different model and retry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    var modelExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = viewModel.getModelDisplayName(state.selectedModel),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Model (OpenRouter)") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                            },
                            supportingText = { Text(state.selectedModel) },
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(model.displayName)
                                            Text(
                                                model.id,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSelectedModel(model.id)
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    var iterationsExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = iterationsExpanded,
                        onExpandedChange = { iterationsExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${state.aiMaxIterations} rounds",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("AI Max Iterations") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = iterationsExpanded)
                            },
                            supportingText = {
                                Text("Higher = more persistence, but slower responses.")
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = iterationsExpanded,
                            onDismissRequest = { iterationsExpanded = false }
                        ) {
                            listOf(6, 8, 10, 12, 15, 20).forEach { iterations ->
                                DropdownMenuItem(
                                    text = { Text("$iterations rounds") },
                                    onClick = {
                                        viewModel.setAiMaxIterations(iterations)
                                        iterationsExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        text = "Range: ${SettingsStore.MIN_AI_MAX_ITERATIONS}-${SettingsStore.MAX_AI_MAX_ITERATIONS}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Connection Section
            item {
                SettingsSection(title = "Connection") {
                    SettingsSwitch(
                        title = "Auto-connect",
                        subtitle = "Automatically connect to last device",
                        checked = state.autoConnect,
                        onCheckedChange = { viewModel.setAutoConnect(it) }
                    )
                }
            }

            // Permissions Section
            item {
                SettingsSection(title = "Permissions") {
                    OutlinedTextField(
                        value = state.defaultProjectPath,
                        onValueChange = { viewModel.setDefaultProjectPath(it) },
                        label = { Text("Default Project Path") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Permission Duration
                    var durationExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = durationExpanded,
                        onExpandedChange = { durationExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = formatDuration(state.permissionDuration),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Permission Duration") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = durationExpanded)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = durationExpanded,
                            onDismissRequest = { durationExpanded = false }
                        ) {
                            listOf(
                                Permission.DURATION_5_MINUTES to "5 minutes",
                                Permission.DURATION_15_MINUTES to "15 minutes",
                                Permission.DURATION_1_HOUR to "1 hour",
                                Permission.DURATION_SESSION to "Session (24h)"
                            ).forEach { (duration, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.setPermissionDuration(duration)
                                        durationExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.grantProjectPermission() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VesperAccent
                        )
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Project Scope")
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = "Auto-Approve",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Skip the approval dialog for these risk tiers. Blocked paths always require a settings unlock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsSwitch(
                        title = "Medium risk",
                        subtitle = "File writes within project scope",
                        checked = state.autoApproveMedium,
                        onCheckedChange = { viewModel.setAutoApproveMedium(it) }
                    )

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    SettingsSwitch(
                        title = "High risk",
                        subtitle = "Deletes, moves, overwrites, mass ops",
                        checked = state.autoApproveHigh,
                        onCheckedChange = { viewModel.setAutoApproveHigh(it) }
                    )
                    if (state.autoApproveHigh) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = RiskHigh.copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = RiskHigh,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Destructive actions will execute without confirmation.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = RiskHigh
                                )
                            }
                        }
                    }
                }
            }

            // Active Permissions
            if (state.activePermissions.isNotEmpty()) {
                item {
                    SettingsSection(title = "Active Permissions") {
                        state.activePermissions.forEach { permission ->
                            PermissionItem(
                                permission = permission,
                                onRevoke = { viewModel.revokePermission(permission.id) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        TextButton(
                            onClick = { viewModel.revokeAllPermissions() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = RiskHigh
                            )
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Revoke All")
                        }
                    }
                }
            }

            // UI Section
            item {
                SettingsSection(title = "User Interface") {
                    SettingsSwitch(
                        title = "Dark Mode",
                        subtitle = "Use dark theme",
                        checked = state.darkMode,
                        onCheckedChange = { viewModel.setDarkMode(it) }
                    )

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    SettingsSwitch(
                        title = "Haptic Feedback",
                        subtitle = "Vibrate on confirmations",
                        checked = state.hapticFeedback,
                        onCheckedChange = { viewModel.setHapticFeedback(it) }
                    )
                }
            }

            // Voice / TTS Section
            item {
                SettingsSection(title = "Voice (OpenRouter TTS)") {
                    SettingsSwitch(
                        title = "Enable Voice Output",
                        subtitle = "Speak agent responses via OpenRouter audio",
                        checked = state.ttsEnabled,
                        onCheckedChange = { viewModel.setTtsEnabled(it) }
                    )
                    Text(
                        text = "Uses your OpenRouter API key — no extra key needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Voice Selection
                    var voiceExpanded by remember { mutableStateOf(false) }
                    val currentVoice = OpenRouterTtsService.AVAILABLE_VOICES
                        .find { it.id == state.ttsVoiceId }

                    ExposedDropdownMenuBox(
                        expanded = voiceExpanded,
                        onExpandedChange = { voiceExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = currentVoice?.let { "${it.name} — ${it.description}" } ?: "Shimmer",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voice") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = voiceExpanded,
                            onDismissRequest = { voiceExpanded = false }
                        ) {
                            OpenRouterTtsService.AVAILABLE_VOICES.forEach { voice ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(voice.name, fontWeight = FontWeight.Medium)
                                            Text(
                                                voice.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setTtsVoiceId(voice.id)
                                        voiceExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsSwitch(
                        title = "Auto-Speak",
                        subtitle = "Automatically read agent responses aloud",
                        checked = state.ttsAutoSpeak,
                        onCheckedChange = { viewModel.setTtsAutoSpeak(it) }
                    )
                }
            }

            // Data Section
            item {
                SettingsSection(title = "Data") {
                    var retentionExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = retentionExpanded,
                        onExpandedChange = { retentionExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${state.auditRetentionDays} days",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Audit Log Retention") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = retentionExpanded)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = retentionExpanded,
                            onDismissRequest = { retentionExpanded = false }
                        ) {
                            listOf(7, 14, 30, 60, 90).forEach { days ->
                                DropdownMenuItem(
                                    text = { Text("$days days") },
                                    onClick = {
                                        viewModel.setAuditRetentionDays(days)
                                        retentionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // About Section
            item {
                SettingsSection(title = "About") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Version")
                        Text(com.vesper.flipper.BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = VesperOrange
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VesperOrange,
                checkedTrackColor = VesperOrange.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun PermissionItem(
    permission: Permission,
    onRevoke: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = VesperAccent
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission.pathPattern,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Expires in ${formatRemainingTime(permission.remainingTimeMs())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Revoke",
                    tint = RiskHigh
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    return when (durationMs) {
        Permission.DURATION_5_MINUTES -> "5 minutes"
        Permission.DURATION_15_MINUTES -> "15 minutes"
        Permission.DURATION_1_HOUR -> "1 hour"
        Permission.DURATION_SESSION -> "Session (24h)"
        else -> "${durationMs / 60000} minutes"
    }
}

private fun formatRemainingTime(remainingMs: Long): String {
    val minutes = remainingMs / 60000
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
