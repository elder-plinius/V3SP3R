package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ai.VesperAgent
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class AlchemyLabViewModel @Inject constructor(
    private val fileSystem: FlipperFileSystem,
    private val vesperAgent: VesperAgent
) : ViewModel() {

    // ═══════════════════════════════════════════════════════
    // THE FORGE — AI Crafter State
    // ═══════════════════════════════════════════════════════

    private val _forgeInput = MutableStateFlow("")
    val forgeInput: StateFlow<String> = _forgeInput.asStateFlow()

    private val _isForging = MutableStateFlow(false)
    val isForging: StateFlow<Boolean> = _isForging.asStateFlow()

    private val _currentBlueprint = MutableStateFlow<ForgeBlueprint?>(null)
    val currentBlueprint: StateFlow<ForgeBlueprint?> = _currentBlueprint.asStateFlow()

    private val _forgeHistory = MutableStateFlow<List<ForgeBlueprint>>(emptyList())
    val forgeHistory: StateFlow<List<ForgeBlueprint>> = _forgeHistory.asStateFlow()

    private val _forgeError = MutableStateFlow<String?>(null)
    val forgeError: StateFlow<String?> = _forgeError.asStateFlow()

    // ═══════════════════════════════════════════════════════
    // THE WORKBENCH — Visual Editor State
    // ═══════════════════════════════════════════════════════

    private val _editingSection = MutableStateFlow<Int?>(null)
    val editingSection: StateFlow<Int?> = _editingSection.asStateFlow()

    private val _editingLoot = MutableStateFlow<LootCard?>(null)
    val editingLoot: StateFlow<LootCard?> = _editingLoot.asStateFlow()

    private val _editContent = MutableStateFlow("")
    val editContent: StateFlow<String> = _editContent.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // ═══════════════════════════════════════════════════════
    // THE VAULT — Loot Inventory State
    // ═══════════════════════════════════════════════════════

    private val _lootCards = MutableStateFlow<List<LootCard>>(emptyList())
    val lootCards: StateFlow<List<LootCard>> = _lootCards.asStateFlow()

    private val _selectedFilter = MutableStateFlow<PayloadType?>(null)
    val selectedFilter: StateFlow<PayloadType?> = _selectedFilter.asStateFlow()

    private val _isLoadingVault = MutableStateFlow(false)
    val isLoadingVault: StateFlow<Boolean> = _isLoadingVault.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // Filtered loot
    val filteredLoot: StateFlow<List<LootCard>> = combine(
        _lootCards,
        _selectedFilter
    ) { cards, filter ->
        if (filter == null) cards
        else cards.filter { it.payloadType == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Vault stats
    val vaultStats: StateFlow<Map<PayloadType, Int>> = _lootCards.map { cards ->
        cards.groupBy { it.payloadType }.mapValues { it.value.size }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Legacy support
    private val _project = MutableStateFlow(createDefaultProject())
    val project: StateFlow<AlchemyProject> = _project.asStateFlow()
    private val _waveformPreview = MutableStateFlow<List<Float>>(emptyList())
    val waveformPreview: StateFlow<List<Float>> = _waveformPreview.asStateFlow()
    private val _selectedLayerIndex = MutableStateFlow<Int?>(null)
    val selectedLayerIndex: StateFlow<Int?> = _selectedLayerIndex.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _showExportDialog = MutableStateFlow(false)
    val showExportDialog: StateFlow<Boolean> = _showExportDialog.asStateFlow()
    private val _exportedCode = MutableStateFlow<String?>(null)
    val exportedCode: StateFlow<String?> = _exportedCode.asStateFlow()

    init {
        loadVault()
        viewModelScope.launch {
            _project.collect { proj ->
                _waveformPreview.value = SignalAlchemist.generateWaveformPreview(proj, 300)
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // FORGE ACTIONS
    // ═══════════════════════════════════════════════════════

    fun updateForgeInput(text: String) {
        _forgeInput.value = text
    }

    fun forge() {
        val input = _forgeInput.value.trim()
        if (input.isEmpty()) return

        viewModelScope.launch {
            _isForging.value = true
            _forgeError.value = null

            try {
                // Use AI to generate blueprint
                val result = vesperAgent.sendMessage(
                    buildForgePrompt(input)
                )

                val aiResponse = result.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content ?: ""

                val blueprint = parseAIBlueprint(input, aiResponse)
                _currentBlueprint.value = blueprint
                _forgeHistory.value = listOf(blueprint) + _forgeHistory.value.take(9)

            } catch (e: Exception) {
                _forgeError.value = "Forge failed: ${e.message}"
                // Generate a fallback blueprint
                _currentBlueprint.value = generateFallbackBlueprint(input)
            } finally {
                _isForging.value = false
            }
        }
    }

    fun deployBlueprint() {
        val blueprint = _currentBlueprint.value ?: return

        viewModelScope.launch {
            _currentBlueprint.value = blueprint.copy(status = ForgeStatus.FORGING)

            try {
                // Ensure parent directory exists on the Flipper before writing
                val dirPath = blueprint.flipperPath.substringBeforeLast("/")
                if (dirPath.isNotEmpty() && dirPath != blueprint.flipperPath) {
                    fileSystem.createDirectory(dirPath)
                }

                val result = fileSystem.writeFile(blueprint.flipperPath, blueprint.generatedCode)
                // Re-read current blueprint to avoid overwriting edits made during deploy
                val current = _currentBlueprint.value ?: blueprint
                if (result.isSuccess) {
                    _currentBlueprint.value = current.copy(status = ForgeStatus.FORGED)
                    _message.value = "Forged to ${blueprint.flipperPath}"
                    loadVault() // Refresh vault to show new loot
                } else {
                    _currentBlueprint.value = current.copy(status = ForgeStatus.FAILED)
                    _message.value = "Deploy failed: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                val current = _currentBlueprint.value ?: blueprint
                _currentBlueprint.value = current.copy(status = ForgeStatus.FAILED)
                _message.value = "Deploy error: ${e.message}"
            }
        }
    }

    fun clearBlueprint() {
        _currentBlueprint.value = null
        _forgeInput.value = ""
    }

    fun editBlueprintSection(index: Int) {
        _editingSection.value = index
    }

    fun updateBlueprintSection(index: Int, newValue: String) {
        val blueprint = _currentBlueprint.value ?: return
        val sections = blueprint.sections.toMutableList()
        if (index in sections.indices) {
            sections[index] = sections[index].copy(value = newValue)
            _currentBlueprint.value = blueprint.copy(sections = sections)
        }
        _editingSection.value = null
    }

    // ═══════════════════════════════════════════════════════
    // WORKBENCH ACTIONS
    // ═══════════════════════════════════════════════════════

    fun openInWorkbench(loot: LootCard) {
        _editingLoot.value = loot
        viewModelScope.launch {
            try {
                val content = fileSystem.readFile(loot.path)
                if (content.isSuccess) {
                    _editContent.value = content.getOrDefault("")
                }
            } catch (e: Exception) {
                _message.value = "Failed to read: ${e.message}"
            }
        }
    }

    fun updateEditContent(content: String) {
        _editContent.value = content
    }

    fun saveWorkbench() {
        val loot = _editingLoot.value ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val result = fileSystem.writeFile(loot.path, _editContent.value)
                if (result.isSuccess) {
                    _message.value = "Saved ${loot.name}"
                    _editingLoot.value = null
                } else {
                    _message.value = "Save failed: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _message.value = "Save error: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun closeWorkbench() {
        _editingLoot.value = null
        _editContent.value = ""
    }

    // ═══════════════════════════════════════════════════════
    // VAULT ACTIONS
    // ═══════════════════════════════════════════════════════

    fun setFilter(type: PayloadType?) {
        _selectedFilter.value = type
    }

    fun loadVault() {
        viewModelScope.launch {
            _isLoadingVault.value = true
            val allLoot = mutableListOf<LootCard>()

            val scanDirs = listOf(
                "/ext/subghz" to PayloadType.SUB_GHZ,
                "/ext/infrared" to PayloadType.INFRARED,
                "/ext/nfc" to PayloadType.NFC,
                "/ext/lfrfid" to PayloadType.RFID,
                "/ext/badusb" to PayloadType.BAD_USB,
                "/ext/ibutton" to PayloadType.IBUTTON
            )

            for ((dir, expectedType) in scanDirs) {
                try {
                    val result = fileSystem.listDirectory(dir)
                    if (result.isSuccess) {
                        val entries = result.getOrNull().orEmpty()
                        for (entry in entries.filter { !it.isDirectory }) {
                            val type = LootClassifier.detectPayloadType(entry.name, entry.path)
                            val actualType = if (type == PayloadType.UNKNOWN) expectedType else type

                            // Try to read first few lines for metadata
                            var metadata = emptyMap<String, String>()
                            var tags = emptyList<String>()
                            var preview: String? = null
                            try {
                                val content = fileSystem.readFile(entry.path)
                                if (content.isSuccess) {
                                    val text = content.getOrDefault("")
                                    metadata = LootClassifier.parseMetadata(actualType, text)
                                    tags = LootClassifier.autoTag(actualType, text)
                                    preview = text.take(200)
                                }
                            } catch (_: Exception) {}

                            val rarity = LootClassifier.classifyRarity(actualType, metadata)

                            allLoot.add(
                                LootCard(
                                    name = entry.name.substringBeforeLast("."),
                                    fileName = entry.name,
                                    path = entry.path,
                                    payloadType = actualType,
                                    rarity = rarity,
                                    metadata = metadata,
                                    size = entry.size,
                                    capturedAt = entry.modifiedTimestamp ?: System.currentTimeMillis(),
                                    tags = tags,
                                    previewData = preview
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            _lootCards.value = allLoot.sortedByDescending { it.capturedAt }
            _isLoadingVault.value = false
        }
    }

    fun deleteLoot(loot: LootCard) {
        viewModelScope.launch {
            try {
                val result = fileSystem.deleteFile(loot.path)
                if (result.isSuccess) {
                    _lootCards.value = _lootCards.value.filter { it.id != loot.id }
                    _message.value = "Deleted ${loot.name}"
                }
            } catch (e: Exception) {
                _message.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun duplicateLoot(loot: LootCard) {
        viewModelScope.launch {
            try {
                val newName = "${loot.name}_copy"
                val ext = loot.fileName.substringAfterLast(".", "")
                val dir = loot.path.substringBeforeLast("/")
                val newPath = "$dir/${newName}.$ext"

                val content = fileSystem.readFile(loot.path)
                if (content.isSuccess) {
                    val writeResult = fileSystem.writeFile(newPath, content.getOrDefault(""))
                    if (writeResult.isSuccess) {
                        _message.value = "Duplicated as $newName"
                        loadVault()
                    }
                }
            } catch (e: Exception) {
                _message.value = "Duplicate failed: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    // ═══════════════════════════════════════════════════════
    // AI PROMPT BUILDER
    // ═══════════════════════════════════════════════════════

    private fun buildForgePrompt(userRequest: String): String {
        return """You are V3SP3R's Alchemy Lab AI forge. The user wants to craft a Flipper Zero payload.

USER REQUEST: "$userRequest"

Analyze the request and generate the EXACT file content for a Flipper Zero payload file.

RESPOND IN THIS EXACT FORMAT (no extra text, just the structured output):

TYPE: [one of: SUB_GHZ, INFRARED, NFC, RFID, BAD_USB, IBUTTON]
TITLE: [short descriptive title]
FILENAME: [filename with extension]
RARITY: [COMMON, UNCOMMON, RARE, EPIC, or LEGENDARY]
DESCRIPTION: [1-2 sentence description]
SECTION:label=Frequency|value=433920000|editable=true|type=NUMBER
SECTION:label=Protocol|value=RAW|editable=true|type=TEXT
---PAYLOAD---
[exact file content for Flipper Zero]
---END---

For SUB_GHZ: Generate valid .sub file format
For INFRARED: Generate valid .ir file format
For BAD_USB: Generate valid DuckyScript
For NFC: Generate valid .nfc file format
For RFID: Generate valid .rfid file format

Generate real, working Flipper Zero payload content."""
    }

    private fun parseAIBlueprint(userInput: String, aiResponse: String): ForgeBlueprint {
        val lines = aiResponse.lines()

        var type = PayloadType.SUB_GHZ
        var title = "Crafted Payload"
        var filename = "forge_output.sub"
        var rarity = LootRarity.COMMON
        var description = "AI-crafted payload from: $userInput"
        val sections = mutableListOf<BlueprintSection>()
        var payloadContent = ""

        var inPayload = false
        val payloadLines = mutableListOf<String>()

        for (line in lines) {
            when {
                inPayload -> {
                    if (line.trim() == "---END---") {
                        inPayload = false
                        payloadContent = payloadLines.joinToString("\n")
                    } else {
                        payloadLines.add(line)
                    }
                }
                line.trim() == "---PAYLOAD---" -> inPayload = true
                line.startsWith("TYPE:") -> {
                    val typeStr = line.substringAfter(":").trim()
                    type = try { PayloadType.valueOf(typeStr) } catch (_: Exception) { PayloadType.SUB_GHZ }
                }
                line.startsWith("TITLE:") -> title = line.substringAfter(":").trim()
                line.startsWith("FILENAME:") -> filename = line.substringAfter(":").trim()
                line.startsWith("RARITY:") -> {
                    val rarityStr = line.substringAfter(":").trim()
                    rarity = try { LootRarity.valueOf(rarityStr) } catch (_: Exception) { LootRarity.COMMON }
                }
                line.startsWith("DESCRIPTION:") -> description = line.substringAfter(":").trim()
                line.startsWith("SECTION:") -> {
                    val sectionData = line.substringAfter("SECTION:")
                    val params = sectionData.split("|").associate {
                        val (k, v) = it.split("=", limit = 2)
                        k to v
                    }
                    sections.add(
                        BlueprintSection(
                            label = params["label"] ?: "Field",
                            value = params["value"] ?: "",
                            editable = params["editable"]?.toBooleanStrictOrNull() ?: true,
                            fieldType = try {
                                BlueprintFieldType.valueOf(params["type"] ?: "TEXT")
                            } catch (_: Exception) { BlueprintFieldType.TEXT }
                        )
                    )
                }
            }
        }

        // If payload parsing failed, use the whole response as content
        if (payloadContent.isBlank()) {
            payloadContent = aiResponse
        }

        val flipperPath = "${type.flipperDir}/${filename}"

        return ForgeBlueprint(
            title = title,
            description = description,
            payloadType = type,
            sections = sections,
            generatedCode = payloadContent,
            flipperPath = flipperPath,
            rarity = rarity
        )
    }

    private fun generateFallbackBlueprint(userInput: String): ForgeBlueprint {
        val lower = userInput.lowercase()
        return when {
            lower.contains("badusb") || lower.contains("bad usb") || lower.contains("ducky") || lower.contains("keystroke") -> {
                ForgeBlueprint(
                    title = "BadUSB Script",
                    description = "Generated BadUSB script from: $userInput",
                    payloadType = PayloadType.BAD_USB,
                    sections = listOf(
                        BlueprintSection("Script Type", "BadUSB / DuckyScript"),
                        BlueprintSection("Target OS", "Windows", fieldType = BlueprintFieldType.DROPDOWN),
                        BlueprintSection("Delay", "500", fieldType = BlueprintFieldType.NUMBER)
                    ),
                    generatedCode = buildString {
                        appendLine("REM V3SP3R Alchemy Lab - BadUSB Payload")
                        appendLine("REM Request: $userInput")
                        appendLine("DELAY 1000")
                        appendLine("GUI r")
                        appendLine("DELAY 500")
                        appendLine("STRING cmd")
                        appendLine("ENTER")
                        appendLine("DELAY 500")
                        appendLine("STRING echo V3SP3R was here")
                        appendLine("ENTER")
                    },
                    flipperPath = "/ext/badusb/vesper_script.txt",
                    rarity = LootRarity.EPIC
                )
            }
            lower.contains("sub") || lower.contains("433") || lower.contains("315") || lower.contains("garage") || lower.contains("signal") -> {
                val freq = when {
                    lower.contains("315") -> 315000000L
                    lower.contains("868") -> 868350000L
                    else -> 433920000L
                }
                ForgeBlueprint(
                    title = "Sub-GHz Signal",
                    description = "Generated Sub-GHz payload from: $userInput",
                    payloadType = PayloadType.SUB_GHZ,
                    sections = listOf(
                        BlueprintSection("Frequency", freq.toString(), fieldType = BlueprintFieldType.FREQUENCY),
                        BlueprintSection("Modulation", "OOK 650kHz"),
                        BlueprintSection("Protocol", "RAW")
                    ),
                    generatedCode = buildString {
                        appendLine("Filetype: Flipper SubGhz RAW File")
                        appendLine("Version: 1")
                        appendLine("# Generated by V3SP3R Alchemy Lab")
                        appendLine("Frequency: $freq")
                        appendLine("Preset: FuriHalSubGhzPresetOok650Async")
                        appendLine("Protocol: RAW")
                        appendLine("RAW_Data: 350 -350 350 -350 350 -350 350 -1050 350 -350 350 -1050")
                    },
                    flipperPath = "/ext/subghz/vesper_forge.sub",
                    rarity = LootRarity.RARE
                )
            }
            lower.contains("ir") || lower.contains("infrared") || lower.contains("remote") || lower.contains("tv") -> {
                ForgeBlueprint(
                    title = "IR Remote Signal",
                    description = "Generated IR payload from: $userInput",
                    payloadType = PayloadType.INFRARED,
                    sections = listOf(
                        BlueprintSection("Protocol", "NEC"),
                        BlueprintSection("Address", "04", fieldType = BlueprintFieldType.HEX),
                        BlueprintSection("Command", "08", fieldType = BlueprintFieldType.HEX)
                    ),
                    generatedCode = buildString {
                        appendLine("Filetype: IR signals file")
                        appendLine("Version: 1")
                        appendLine("# Generated by V3SP3R Alchemy Lab")
                        appendLine("#")
                        appendLine("name: Power")
                        appendLine("type: parsed")
                        appendLine("protocol: NEC")
                        appendLine("address: 04 00 00 00")
                        appendLine("command: 08 00 00 00")
                    },
                    flipperPath = "/ext/infrared/vesper_remote.ir",
                    rarity = LootRarity.UNCOMMON
                )
            }
            lower.contains("nfc") || lower.contains("tag") || lower.contains("mifare") || lower.contains("ntag") -> {
                ForgeBlueprint(
                    title = "NFC Tag",
                    description = "Generated NFC payload from: $userInput",
                    payloadType = PayloadType.NFC,
                    sections = listOf(
                        BlueprintSection("Device Type", "NTAG215"),
                        BlueprintSection("UID", "04 A1 B2 C3 D4 E5 F6", fieldType = BlueprintFieldType.HEX)
                    ),
                    generatedCode = buildString {
                        appendLine("Filetype: Flipper NFC device")
                        appendLine("Version: 4")
                        appendLine("# Generated by V3SP3R Alchemy Lab")
                        appendLine("Device type: NTAG215")
                        appendLine("UID: 04 A1 B2 C3 D4 E5 F6")
                        appendLine("ATQA: 44 00")
                        appendLine("SAK: 00")
                    },
                    flipperPath = "/ext/nfc/vesper_tag.nfc",
                    rarity = LootRarity.RARE
                )
            }
            else -> {
                ForgeBlueprint(
                    title = "Custom Payload",
                    description = userInput,
                    payloadType = PayloadType.BAD_USB,
                    sections = listOf(
                        BlueprintSection("Type", "BadUSB"),
                        BlueprintSection("Description", userInput)
                    ),
                    generatedCode = buildString {
                        appendLine("REM V3SP3R Alchemy Lab")
                        appendLine("REM $userInput")
                        appendLine("DELAY 1000")
                        appendLine("STRING $userInput")
                        appendLine("ENTER")
                    },
                    flipperPath = "/ext/badusb/vesper_custom.txt",
                    rarity = LootRarity.COMMON
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // LEGACY METHODS (for backward compat)
    // ═══════════════════════════════════════════════════════

    fun updateProjectName(name: String) { _project.value = _project.value.copy(name = name) }
    fun updateFrequency(frequency: Long) { _project.value = _project.value.copy(frequency = frequency) }
    fun selectPreset(preset: SignalPreset) { _project.value = _project.value.copy(preset = preset, frequency = preset.frequency) }
    fun updateModulation(modulation: ModulationType) { _project.value = _project.value.copy(modulation = modulation) }

    fun addLayer(type: LayerType) {
        val layers = _project.value.layers.toMutableList()
        layers.add(createDefaultLayer(type))
        _project.value = _project.value.copy(layers = layers)
        _selectedLayerIndex.value = layers.size - 1
    }
    fun removeLayer(index: Int) {
        val layers = _project.value.layers.toMutableList()
        if (index in layers.indices) { layers.removeAt(index); _project.value = _project.value.copy(layers = layers); _selectedLayerIndex.value = null }
    }
    fun selectLayer(index: Int?) { _selectedLayerIndex.value = index }
    fun toggleLayerEnabled(index: Int) { updateLayer(index) { it.copy(enabled = !it.enabled) } }
    fun updateLayerVolume(index: Int, volume: Float) { updateLayer(index) { it.copy(volume = volume.coerceIn(0f, 1f)) } }
    fun updateLayerBitDuration(index: Int, duration: Int) { updateLayer(index) { l -> l.copy(pattern = l.pattern.copy(bitDuration = duration.coerceIn(50, 5000))) } }
    fun updateLayerEncoding(index: Int, encoding: BitEncoding) { updateLayer(index) { l -> l.copy(pattern = l.pattern.copy(encoding = encoding)) } }
    fun updateLayerRepeatCount(index: Int, count: Int) { updateLayer(index) { l -> l.copy(timing = l.timing.copy(repeatCount = count.coerceIn(1, 100))) } }
    fun updateLayerBits(index: Int, hexPattern: String) {
        updateLayer(index) { layer ->
            val bits = hexPattern.flatMap { char -> val nibble = char.toString().toIntOrNull(16) ?: 0; (3 downTo 0).map { (nibble shr it) and 1 == 1 } }
            layer.copy(pattern = layer.pattern.copy(bits = bits))
        }
    }
    fun moveLayerUp(index: Int) {
        if (index > 0) { val layers = _project.value.layers.toMutableList(); val t = layers[index]; layers[index] = layers[index - 1]; layers[index - 1] = t; _project.value = _project.value.copy(layers = layers); _selectedLayerIndex.value = index - 1 }
    }
    fun moveLayerDown(index: Int) {
        val layers = _project.value.layers; if (index < layers.size - 1) { val m = layers.toMutableList(); val t = m[index]; m[index] = m[index + 1]; m[index + 1] = t; _project.value = _project.value.copy(layers = m); _selectedLayerIndex.value = index + 1 }
    }
    fun duplicateLayer(index: Int) {
        val layers = _project.value.layers; if (index in layers.indices) { val m = layers.toMutableList(); m.add(index + 1, layers[index].copy(id = java.util.UUID.randomUUID().toString(), name = "${layers[index].name} (copy)")); _project.value = _project.value.copy(layers = m) }
    }
    private fun updateLayer(index: Int, transform: (SignalLayer) -> SignalLayer) {
        val layers = _project.value.layers.toMutableList(); if (index in layers.indices) { layers[index] = transform(layers[index]); _project.value = _project.value.copy(layers = layers) }
    }

    fun playPreview() { viewModelScope.launch { _isPlaying.value = true; kotlinx.coroutines.delay(2000); _isPlaying.value = false; _message.value = "Signal preview complete" } }
    fun showExport() { _exportedCode.value = SignalAlchemist.exportToFlipperFormat(_project.value); _showExportDialog.value = true }
    fun hideExport() { _showExportDialog.value = false }

    fun saveToFlipper() {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val content = SignalAlchemist.exportToFlipperFormat(_project.value)
                val filename = _project.value.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
                val path = "/ext/subghz/alchemy_$filename.sub"
                val result = fileSystem.writeFile(path, content)
                if (result.isSuccess) { _message.value = "Saved to $path"; _showExportDialog.value = false }
                else _message.value = "Failed to save: ${result.exceptionOrNull()?.message}"
            } catch (e: Exception) { _message.value = "Error: ${e.message}" }
            finally { _isSaving.value = false }
        }
    }

    fun newProject() { _project.value = createDefaultProject(); _selectedLayerIndex.value = null }

    fun addPrincetonTemplate() {
        _project.value = _project.value.copy(name = "Princeton Clone", frequency = 433_920_000, modulation = ModulationType.OOK_650, preset = SignalPreset.CAR_KEY_433, layers = listOf(
            SignalLayer(name = "Preamble", type = LayerType.PREAMBLE, pattern = PatternPresets.PRINCETON_PREAMBLE, timing = TimingConfig(repeatCount = 1), color = 0xFF4CAF50),
            SignalLayer(name = "Sync", type = LayerType.SYNC, pattern = PatternPresets.GARAGE_SYNC, timing = TimingConfig(delayBefore = 100), color = 0xFF2196F3),
            SignalLayer(name = "Data", type = LayerType.DATA, pattern = PatternPresets.customBits("A5F0", 350), timing = TimingConfig(delayBefore = 50, repeatCount = 5, repeatDelay = 10000), color = 0xFFFF6B00)
        ))
    }
    fun addJammerTemplate() {
        _project.value = _project.value.copy(name = "Test Jammer", frequency = 433_920_000, modulation = ModulationType.OOK_650, preset = SignalPreset.CAR_KEY_433, layers = listOf(
            SignalLayer(name = "Noise Burst 1", type = LayerType.NOISE, pattern = PatternPresets.NOISE_BURST, timing = TimingConfig(repeatCount = 10, repeatDelay = 1000), color = 0xFFF44336),
            SignalLayer(name = "Sweep", type = LayerType.SWEEP, pattern = BitPattern(List(50) { true }, 200), timing = TimingConfig(delayBefore = 5000, repeatCount = 5), color = 0xFF9C27B0)
        ))
    }
    fun addGarageDoorTemplate() {
        _project.value = _project.value.copy(name = "Garage Door", frequency = 315_000_000, modulation = ModulationType.OOK_650, preset = SignalPreset.GARAGE_315, layers = listOf(
            SignalLayer(name = "Preamble", type = LayerType.PREAMBLE, pattern = BitPattern(List(20) { it % 2 == 0 }, 400), timing = TimingConfig(), color = 0xFF4CAF50),
            SignalLayer(name = "Code", type = LayerType.DATA, pattern = PatternPresets.customBits("DEADBEEF", 400), timing = TimingConfig(delayBefore = 100, repeatCount = 8, repeatDelay = 15000), color = 0xFFFF6B00)
        ))
    }

    private fun createDefaultProject(): AlchemyProject = AlchemyProject(
        name = "New Signal", frequency = 433_920_000, modulation = ModulationType.OOK_650, preset = SignalPreset.CAR_KEY_433,
        layers = listOf(createDefaultLayer(LayerType.PREAMBLE), createDefaultLayer(LayerType.DATA))
    )

    private fun createDefaultLayer(type: LayerType): SignalLayer {
        val color = when (type) { LayerType.CARRIER -> 0xFF9E9E9E; LayerType.DATA -> 0xFFFF6B00; LayerType.PREAMBLE -> 0xFF4CAF50; LayerType.SYNC -> 0xFF2196F3; LayerType.NOISE -> 0xFFF44336; LayerType.SWEEP -> 0xFF9C27B0; LayerType.BURST -> 0xFFFFEB3B }
        val pattern = when (type) { LayerType.PREAMBLE -> BitPattern(List(12) { it % 2 == 0 }, 400); LayerType.SYNC -> BitPattern(listOf(true, true, true, false), 500); LayerType.DATA -> BitPattern(List(16) { Random.nextBoolean() }, 400); LayerType.NOISE -> PatternPresets.NOISE_BURST; LayerType.SWEEP -> BitPattern(List(30) { true }, 200); LayerType.BURST -> BitPattern(List(15) { it % 3 == 0 }, 150); LayerType.CARRIER -> BitPattern(listOf(true), 5000) }
        return SignalLayer(name = type.displayName, type = type, pattern = pattern, timing = TimingConfig(repeatCount = 1), color = color)
    }

    companion object {
        fun formatFrequency(hz: Long): String = when {
            hz >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.3f GHz", hz / 1_000_000_000.0)
            hz >= 1_000_000 -> String.format(java.util.Locale.US, "%.3f MHz", hz / 1_000_000.0)
            hz >= 1_000 -> String.format(java.util.Locale.US, "%.3f kHz", hz / 1_000.0)
            else -> "$hz Hz"
        }
    }
}
