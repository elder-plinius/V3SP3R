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
import androidx.compose.ui.graphics.*
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
                            onEditSection = { viewModel.editBlueprintSection(it) },
                            onDismiss = { viewModel.clearBlueprint() }
                        )
                    }
                }
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

            // Deploy Button
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onDeploy,
                modifier = Modifier.fillMaxWidth(),
                enabled = blueprint.status == ForgeStatus.BLUEPRINT,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (blueprint.status) {
                        ForgeStatus.FORGED -> RiskLow; ForgeStatus.FAILED -> RiskHigh; else -> VesperOrange
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                when (blueprint.status) {
                    ForgeStatus.BLUEPRINT -> { Icon(Icons.Default.RocketLaunch, null); Spacer(modifier = Modifier.width(8.dp)); Text("Deploy to Flipper", fontWeight = FontWeight.Bold) }
                    ForgeStatus.FORGING -> { CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)); Text("Deploying...") }
                    ForgeStatus.FORGED -> { Icon(Icons.Default.CheckCircle, null); Spacer(modifier = Modifier.width(8.dp)); Text("Deployed!") }
                    ForgeStatus.FAILED -> { Icon(Icons.Default.Error, null); Spacer(modifier = Modifier.width(8.dp)); Text("Failed") }
                }
            }
        }
    }
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

                Divider()

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
