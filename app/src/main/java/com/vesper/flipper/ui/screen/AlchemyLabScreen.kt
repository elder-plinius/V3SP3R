@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vesper.flipper.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.ui.theme.*
import com.vesper.flipper.ui.viewmodel.AlchemyLabViewModel

@Composable
fun AlchemyLabScreen(
    viewModel: AlchemyLabViewModel = hiltViewModel()
) {
    val forgeInput by viewModel.forgeInput.collectAsState()
    val isForging by viewModel.isForging.collectAsState()
    val currentBlueprint by viewModel.currentBlueprint.collectAsState()
    val forgeError by viewModel.forgeError.collectAsState()
    val editingLoot by viewModel.editingLoot.collectAsState()
    val editContent by viewModel.editContent.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val filteredLoot by viewModel.filteredLoot.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val isLoadingVault by viewModel.isLoadingVault.collectAsState()
    val vaultStats by viewModel.vaultStats.collectAsState()
    val message by viewModel.message.collectAsState()
    val project by viewModel.project.collectAsState()
    val waveformPreview by viewModel.waveformPreview.collectAsState()
    val selectedLayerIndex by viewModel.selectedLayerIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val showExportDialog by viewModel.showExportDialog.collectAsState()
    val exportedCode by viewModel.exportedCode.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VesperBackdropBrush)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ═══════════════════ HEADER ═══════════════════
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Alchemy Lab",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Surface(
                            color = VesperOrange.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "AI FORGE",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = VesperOrange,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    Text(
                        "Craft payloads. Manage your arsenal. Forge the future.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ═══════════════════ THE FORGE ═══════════════════
            item {
                TheForgeSection(
                    input = forgeInput,
                    onInputChange = { viewModel.updateForgeInput(it) },
                    isForging = isForging,
                    onForge = { viewModel.forge() },
                    error = forgeError
                )
            }

            // Blueprint Card
            item {
                AnimatedVisibility(
                    visible = currentBlueprint != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    currentBlueprint?.let { bp ->
                        BlueprintCard(
                            blueprint = bp,
                            onDeploy = { viewModel.deployBlueprint() },
                            canOpenInEditor = viewModel.canOpenBlueprintInEditor(bp),
                            onOpenInEditor = { viewModel.openBlueprintInEditor() },
                            onEditSection = { viewModel.editBlueprintSection(it) },
                            onDismiss = { viewModel.clearBlueprint() }
                        )
                    }
                }
            }

            item {
                RfEditorSection(
                    project = project,
                    waveformPreview = waveformPreview,
                    selectedLayerIndex = selectedLayerIndex,
                    isPlaying = isPlaying,
                    isSaving = isSaving,
                    onProjectNameChange = { viewModel.updateProjectName(it) },
                    onFrequencyChange = { viewModel.updateFrequency(it) },
                    onPresetChange = { viewModel.selectPreset(it) },
                    onModulationChange = { viewModel.updateModulation(it) },
                    onSelectLayer = { viewModel.selectLayer(it) },
                    onToggleLayer = { viewModel.toggleLayerEnabled(it) },
                    onDuplicateLayer = { viewModel.duplicateLayer(it) },
                    onMoveLayerUp = { viewModel.moveLayerUp(it) },
                    onMoveLayerDown = { viewModel.moveLayerDown(it) },
                    onRemoveLayer = { viewModel.removeLayer(it) },
                    onAddLayer = { viewModel.addLayer(it) },
                    onLayerVolumeChange = { index, volume -> viewModel.updateLayerVolume(index, volume) },
                    onLayerBitDurationChange = { index, duration -> viewModel.updateLayerBitDuration(index, duration) },
                    onLayerEncodingChange = { index, encoding -> viewModel.updateLayerEncoding(index, encoding) },
                    onLayerRepeatCountChange = { index, count -> viewModel.updateLayerRepeatCount(index, count) },
                    onLayerBitsChange = { index, hex -> viewModel.updateLayerBits(index, hex) },
                    onNewProject = { viewModel.newProject() },
                    onPreview = { viewModel.playPreview() },
                    onExport = { viewModel.showExport() },
                    onSave = { viewModel.saveToFlipper() }
                )
            }

            // ═══════════════════ THE VAULT ═══════════════════
            item {
                VaultHeader(
                    stats = vaultStats,
                    selectedFilter = selectedFilter,
                    onFilterChange = { viewModel.setFilter(it) },
                    onRefresh = { viewModel.loadVault() },
                    isLoading = isLoadingVault
                )
            }

            if (isLoadingVault) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = VesperOrange)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Scanning vault...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (filteredLoot.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🗄", style = MaterialTheme.typography.displayLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Vault is empty", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Connect your Flipper to scan your arsenal,\nor use The Forge to craft new payloads",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(filteredLoot, key = { it.id }) { loot ->
                    LootCardItem(
                        loot = loot,
                        onTap = { viewModel.openInWorkbench(loot) },
                        onDelete = { viewModel.deleteLoot(loot) },
                        onDuplicate = { viewModel.duplicateLoot(loot) }
                    )
                }
            }
        }

        // Message Toast
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = VesperAccent.copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(message ?: "", color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Workbench Dialog
        editingLoot?.let { loot ->
            WorkbenchDialog(
                loot = loot,
                content = editContent,
                onContentChange = { viewModel.updateEditContent(it) },
                onSave = { viewModel.saveWorkbench() },
                onDismiss = { viewModel.closeWorkbench() },
                isSaving = isSaving
            )
        }

        if (showExportDialog) {
            ExportDialog(
                content = exportedCode.orEmpty(),
                isSaving = isSaving,
                onSave = { viewModel.saveToFlipper() },
                onDismiss = { viewModel.hideExport() }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// THE FORGE — AI Crafter Section
// ═══════════════════════════════════════════════════════════

@Composable
private fun TheForgeSection(
    input: String,
    onInputChange: (String) -> Unit,
    isForging: Boolean,
    onForge: () -> Unit,
    error: String?
) {
    val focusManager = LocalFocusManager.current

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, VesperOrange.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = VesperOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("THE FORGE", style = MaterialTheme.typography.labelLarge, color = VesperOrange, letterSpacing = 2.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What do you want to craft?", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                minLines = 2,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VesperOrange, cursorColor = VesperOrange),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); if (input.isNotBlank()) onForge() })
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Suggestions
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val suggestions = listOf(
                    "a BadUSB script that opens a terminal",
                    "a 433MHz garage signal clone",
                    "an IR remote for Samsung TV",
                    "an NFC tag with contact info"
                )
                items(suggestions) { s ->
                    AssistChip(
                        onClick = { onInputChange(s) },
                        label = { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onForge,
                modifier = Modifier.fillMaxWidth(),
                enabled = input.isNotBlank() && !isForging,
                colors = ButtonDefaults.buttonColors(containerColor = VesperOrange),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isForging) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Forging...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Forge It", fontWeight = FontWeight.Bold)
                }
            }

            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = RiskHigh, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// BLUEPRINT CARD
// ═══════════════════════════════════════════════════════════

@Composable
private fun BlueprintCard(
    blueprint: ForgeBlueprint,
    onDeploy: () -> Unit,
    canOpenInEditor: Boolean,
    onOpenInEditor: () -> Unit,
    onEditSection: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val rarityColor = Color(blueprint.rarity.color)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, rarityColor.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(
                            Brush.linearGradient(listOf(Color(blueprint.payloadType.color), Color(blueprint.payloadType.color).copy(alpha = 0.6f)))
                        ),
                        contentAlignment = Alignment.Center
                    ) { Text(blueprint.payloadType.icon, style = MaterialTheme.typography.titleLarge) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(blueprint.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(blueprint.rarity.glowColor), shape = RoundedCornerShape(4.dp)) {
                                Text(blueprint.rarity.displayName, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = rarityColor, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(blueprint.payloadType.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(blueprint.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Editable Sections
            if (blueprint.sections.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("BLUEPRINT PARAMETERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                blueprint.sections.forEachIndexed { index, section ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable(enabled = section.editable) { onEditSection(index) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(section.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(section.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Medium)
                            if (section.editable) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp), tint = VesperOrange.copy(alpha = 0.6f))
                            }
                        }
                    }
                    if (index < blueprint.sections.lastIndex) Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Code Preview
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0E14)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(blueprint.generatedCode, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = VesperAccent.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Target: ${blueprint.flipperPath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canOpenInEditor) {
                    OutlinedButton(
                        onClick = onOpenInEditor,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Tune, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open in RF Editor", fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = onDeploy,
                    modifier = Modifier.weight(1f),
                    enabled = blueprint.status == ForgeStatus.BLUEPRINT,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (blueprint.status) {
                            ForgeStatus.FORGED -> RiskLow; ForgeStatus.FAILED -> RiskHigh; else -> VesperOrange
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    when (blueprint.status) {
                        ForgeStatus.BLUEPRINT -> { Icon(Icons.Default.RocketLaunch, null); Spacer(modifier = Modifier.width(8.dp)); Text("Deploy", fontWeight = FontWeight.Bold) }
                        ForgeStatus.FORGING -> { CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)); Text("Deploying...") }
                        ForgeStatus.FORGED -> { Icon(Icons.Default.CheckCircle, null); Spacer(modifier = Modifier.width(8.dp)); Text("Deployed!") }
                        ForgeStatus.FAILED -> { Icon(Icons.Default.Error, null); Spacer(modifier = Modifier.width(8.dp)); Text("Failed") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RfEditorSection(
    project: AlchemyProject,
    waveformPreview: List<Float>,
    selectedLayerIndex: Int?,
    isPlaying: Boolean,
    isSaving: Boolean,
    onProjectNameChange: (String) -> Unit,
    onFrequencyChange: (Long) -> Unit,
    onPresetChange: (SignalPreset) -> Unit,
    onModulationChange: (ModulationType) -> Unit,
    onSelectLayer: (Int?) -> Unit,
    onToggleLayer: (Int) -> Unit,
    onDuplicateLayer: (Int) -> Unit,
    onMoveLayerUp: (Int) -> Unit,
    onMoveLayerDown: (Int) -> Unit,
    onRemoveLayer: (Int) -> Unit,
    onAddLayer: (LayerType) -> Unit,
    onLayerVolumeChange: (Int, Float) -> Unit,
    onLayerBitDurationChange: (Int, Int) -> Unit,
    onLayerEncodingChange: (Int, BitEncoding) -> Unit,
    onLayerRepeatCountChange: (Int, Int) -> Unit,
    onLayerBitsChange: (Int, String) -> Unit,
    onNewProject: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onSave: () -> Unit
) {
    val selectedLayer = selectedLayerIndex?.let { project.layers.getOrNull(it) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var modulationMenuOpen by remember { mutableStateOf(false) }
    var encodingMenuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        border = BorderStroke(1.dp, VesperAccent.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = VesperAccent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RF EDITOR", style = MaterialTheme.typography.labelLarge, color = VesperAccent, letterSpacing = 2.sp)
                }
                Text("${project.layers.size} layers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            OutlinedTextField(
                value = project.name,
                onValueChange = onProjectNameChange,
                label = { Text("Project") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VesperAccent, cursorColor = VesperAccent)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = project.frequency.toString(),
                    onValueChange = { it.toLongOrNull()?.let(onFrequencyChange) },
                    label = { Text("Frequency Hz") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VesperAccent, cursorColor = VesperAccent)
                )
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { modulationMenuOpen = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(project.modulation.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = modulationMenuOpen, onDismissRequest = { modulationMenuOpen = false }) {
                        ModulationType.entries.forEach { modulation ->
                            DropdownMenuItem(
                                text = { Text(modulation.displayName) },
                                onClick = { onModulationChange(modulation); modulationMenuOpen = false }
                            )
                        }
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SignalPreset.entries) { preset ->
                    FilterChip(
                        selected = project.preset == preset,
                        onClick = { onPresetChange(preset) },
                        label = { Text(preset.displayName) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = VesperAccent, selectedLabelColor = Color.White)
                    )
                }
            }

            RfWaveformPreview(samples = waveformPreview, modifier = Modifier.fillMaxWidth().height(120.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNewProject, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(modifier = Modifier.width(6.dp)); Text("New") }
                OutlinedButton(onClick = onPreview, enabled = !isPlaying, modifier = Modifier.weight(1f)) {
                    if (isPlaying) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isPlaying) "Playing" else "Preview")
                }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Icon(Icons.Default.IosShare, null); Spacer(modifier = Modifier.width(6.dp)); Text("Export") }
            }
            Button(
                onClick = onSave,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VesperOrange)
            ) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.Save, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save to Flipper")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("LAYERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                Box {
                    TextButton(onClick = { addMenuOpen = true }) { Icon(Icons.Default.Add, null); Spacer(modifier = Modifier.width(4.dp)); Text("Add Layer") }
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        listOf(LayerType.CARRIER, LayerType.PREAMBLE, LayerType.SYNC, LayerType.DATA, LayerType.BURST).forEach { type ->
                            DropdownMenuItem(text = { Text(type.displayName) }, onClick = { onAddLayer(type); addMenuOpen = false })
                        }
                    }
                }
            }

            project.layers.forEachIndexed { index, layer ->
                RfLayerRow(
                    layer = layer,
                    selected = index == selectedLayerIndex,
                    canMoveUp = index > 0,
                    canMoveDown = index < project.layers.lastIndex,
                    onSelect = { onSelectLayer(index) },
                    onToggle = { onToggleLayer(index) },
                    onDuplicate = { onDuplicateLayer(index) },
                    onMoveUp = { onMoveLayerUp(index) },
                    onMoveDown = { onMoveLayerDown(index) },
                    onRemove = { onRemoveLayer(index) }
                )
            }

            selectedLayer?.let { layer ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Text("SELECTED: ${layer.name}", style = MaterialTheme.typography.labelSmall, color = VesperAccent, letterSpacing = 1.sp)
                Text("Volume ${((layer.volume * 100).toInt())}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = layer.volume, onValueChange = { onLayerVolumeChange(selectedLayerIndex, it) }, valueRange = 0f..1f)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = layer.pattern.bitDuration.toString(),
                        onValueChange = { it.toIntOrNull()?.let { value -> onLayerBitDurationChange(selectedLayerIndex, value) } },
                        label = { Text("Bit us") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VesperAccent, cursorColor = VesperAccent)
                    )
                    OutlinedTextField(
                        value = layer.timing.repeatCount.toString(),
                        onValueChange = { it.toIntOrNull()?.let { value -> onLayerRepeatCountChange(selectedLayerIndex, value) } },
                        label = { Text("Repeats") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VesperAccent, cursorColor = VesperAccent)
                    )
                }

                Box {
                    OutlinedButton(onClick = { encodingMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(layer.pattern.encoding.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = encodingMenuOpen, onDismissRequest = { encodingMenuOpen = false }) {
                        BitEncoding.entries.forEach { encoding ->
                            DropdownMenuItem(
                                text = { Text(encoding.displayName) },
                                onClick = { onLayerEncodingChange(selectedLayerIndex, encoding); encodingMenuOpen = false }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = bitsToHex(layer.pattern.bits),
                    onValueChange = { onLayerBitsChange(selectedLayerIndex, it) },
                    label = { Text("Bits as hex") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VesperAccent, cursorColor = VesperAccent)
                )
            }
        }
    }
}

@Composable
private fun RfLayerRow(
    layer: SignalLayer,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val color = Color(layer.color)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onSelect),
        color = if (selected) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) color else Color.Transparent),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = layer.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.size(width = 44.dp, height = 28.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(layer.type.icon, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(layer.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${layer.pattern.bits.size} bits / ${layer.pattern.bitDuration}us / x${layer.timing.repeatCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, null, tint = RiskHigh, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun RfWaveformPreview(samples: List<Float>, modifier: Modifier = Modifier) {
    val waveColor = VesperAccent
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)

    Canvas(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0E14))) {
        val padding = 12f
        val usableWidth = size.width - padding * 2
        val usableHeight = size.height - padding * 2
        repeat(5) { i ->
            val y = padding + usableHeight * i / 4
            drawLine(gridColor, Offset(padding, y), Offset(size.width - padding, y), strokeWidth = 1f)
        }
        if (samples.isEmpty()) return@Canvas

        val path = Path()
        samples.forEachIndexed { index, value ->
            val x = padding + usableWidth * index / (samples.lastIndex.coerceAtLeast(1))
            val y = padding + (1f - value.coerceIn(0f, 1f)) * usableHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = waveColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun bitsToHex(bits: List<Boolean>): String =
    bits.chunked(4).joinToString("") { chunk ->
        chunk.foldIndexed(0) { index, acc, bit -> if (bit) acc or (1 shl (3 - index)) else acc }
            .toString(16)
            .uppercase()
    }

// ═══════════════════════════════════════════════════════════
// THE VAULT HEADER & FILTERS
// ═══════════════════════════════════════════════════════════

@Composable
private fun VaultHeader(
    stats: Map<PayloadType, Int>,
    selectedFilter: PayloadType?,
    onFilterChange: (PayloadType?) -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("THE VAULT", style = MaterialTheme.typography.labelLarge, color = VesperOrange, letterSpacing = 2.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Text("${stats.values.sum()} items", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = VesperOrange)
                else Icon(Icons.Default.Refresh, "Refresh vault", tint = VesperOrange)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { onFilterChange(null) },
                    label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = VesperOrange, selectedLabelColor = Color.White)
                )
            }
            val types = listOf(PayloadType.SUB_GHZ, PayloadType.INFRARED, PayloadType.NFC, PayloadType.RFID, PayloadType.BAD_USB, PayloadType.IBUTTON)
            items(types) { type ->
                FilterChip(
                    selected = selectedFilter == type,
                    onClick = { onFilterChange(type) },
                    label = { Text("${type.icon} ${type.displayName} (${stats[type] ?: 0})") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(type.color), selectedLabelColor = Color.White)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// LOOT CARD ITEM
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LootCardItem(
    loot: LootCard,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    val rarityColor = Color(loot.rarity.color)
    var showActions by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onTap, onLongClick = { showActions = true }),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        border = BorderStroke(1.dp, rarityColor.copy(alpha = 0.2f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(
                    Brush.linearGradient(listOf(Color(loot.payloadType.color), Color(loot.payloadType.color).copy(alpha = 0.5f)))
                ),
                contentAlignment = Alignment.Center
            ) { Text(loot.payloadType.icon, style = MaterialTheme.typography.titleLarge) }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(loot.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(color = Color(loot.rarity.glowColor), shape = RoundedCornerShape(4.dp)) {
                        Text(loot.rarity.displayName, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = rarityColor, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                    }
                }
                val metaLine = buildString {
                    append(loot.payloadType.displayName)
                    loot.metadata.entries.take(2).forEach { (_, v) -> append(" • $v") }
                }
                Text(metaLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (loot.tags.isNotEmpty()) {
                    Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        loot.tags.take(3).forEach { tag ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                                Text(tag, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text(loot.name) },
            text = {
                Column {
                    Text("${loot.rarity.displayName} ${loot.payloadType.displayName}", color = rarityColor, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(loot.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onDuplicate(); showActions = false }) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Duplicate")
                    }
                    TextButton(onClick = { onDelete(); showActions = false }) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = RiskHigh); Spacer(modifier = Modifier.width(4.dp)); Text("Delete", color = RiskHigh)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showActions = false }) { Text("Cancel") } }
        )
    }
}

// ═══════════════════════════════════════════════════════════
// WORKBENCH DIALOG
// ═══════════════════════════════════════════════════════════

@Composable
private fun WorkbenchDialog(
    loot: LootCard,
    content: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(loot.payloadType.icon, style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("THE WORKBENCH", style = MaterialTheme.typography.labelSmall, color = VesperOrange, letterSpacing = 1.sp)
                            Text(loot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }

                Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = Color(loot.rarity.glowColor), shape = RoundedCornerShape(4.dp)) {
                        Text(loot.rarity.displayName, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color(loot.rarity.color), fontWeight = FontWeight.Bold)
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                        Text(loot.payloadType.displayName, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VesperOrange, unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), cursorColor = VesperOrange),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(loot.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = VesperOrange)
                    ) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Save, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save to Flipper")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportDialog(
    content: String,
    isSaving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.IosShare, null, tint = VesperOrange)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("EXPORT RAW", style = MaterialTheme.typography.labelLarge, color = VesperOrange, letterSpacing = 1.sp)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0E14)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(
                            content,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = VesperAccent.copy(alpha = 0.9f),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = VesperOrange)
                    ) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Save, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save to Flipper")
                    }
                }
            }
        }
    }
}
