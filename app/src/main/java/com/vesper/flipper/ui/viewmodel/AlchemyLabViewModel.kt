package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.executor.ForgeEngine
import com.vesper.flipper.domain.executor.ForgeValidator
import com.vesper.flipper.domain.model.ApprovalMethod
import com.vesper.flipper.domain.model.AuditActionType
import com.vesper.flipper.domain.model.AuditEntry
import com.vesper.flipper.domain.model.ForgeBlueprint
import com.vesper.flipper.domain.model.ForgeStatus
import com.vesper.flipper.domain.model.LootCard
import com.vesper.flipper.domain.model.LootClassifier
import com.vesper.flipper.domain.model.PayloadType
import com.vesper.flipper.domain.model.ProtectedPaths
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.domain.service.AuditService
import com.vesper.flipper.domain.service.PermissionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the code sheet is editing: a vault file or the blueprint in the Forge. */
sealed interface EditTarget {
    data class Vault(val loot: LootCard) : EditTarget
    data object Blueprint : EditTarget
}

/**
 * Forge (Alchemy).
 *
 * Two surfaces share one screen: the generator that produces a blueprint, and
 * the vault that lists what is already on the device. Both feed the same code
 * sheet, so a blueprint and a saved file are validated by the same rules.
 */
@HiltViewModel
class AlchemyLabViewModel @Inject constructor(
    private val fileSystem: FlipperFileSystem,
    private val forgeEngine: ForgeEngine,
    private val permissionService: PermissionService,
    private val auditService: AuditService
) : ViewModel() {

    // ── Forge ────────────────────────────────────────────────────────────

    private val _forgeInput = MutableStateFlow("")
    val forgeInput: StateFlow<String> = _forgeInput.asStateFlow()

    private val _isForging = MutableStateFlow(false)
    val isForging: StateFlow<Boolean> = _isForging.asStateFlow()

    private val _currentBlueprint = MutableStateFlow<ForgeBlueprint?>(null)
    val currentBlueprint: StateFlow<ForgeBlueprint?> = _currentBlueprint.asStateFlow()

    private val _forgeValidation = MutableStateFlow<ForgeValidator.ValidationResult?>(null)
    val forgeValidation: StateFlow<ForgeValidator.ValidationResult?> = _forgeValidation.asStateFlow()

    private val _forgeError = MutableStateFlow<String?>(null)
    val forgeError: StateFlow<String?> = _forgeError.asStateFlow()

    private val _deployError = MutableStateFlow<String?>(null)
    val deployError: StateFlow<String?> = _deployError.asStateFlow()

    // ── Vault ────────────────────────────────────────────────────────────

    private val _lootCards = MutableStateFlow<List<LootCard>>(emptyList())
    val lootCards: StateFlow<List<LootCard>> = _lootCards.asStateFlow()

    private val _selectedFilter = MutableStateFlow<PayloadType?>(null)
    val selectedFilter: StateFlow<PayloadType?> = _selectedFilter.asStateFlow()

    private val _vaultQuery = MutableStateFlow("")
    val vaultQuery: StateFlow<String> = _vaultQuery.asStateFlow()

    private val _isLoadingVault = MutableStateFlow(false)
    val isLoadingVault: StateFlow<Boolean> = _isLoadingVault.asStateFlow()

    private val _hasLoadedVault = MutableStateFlow(false)
    val hasLoadedVault: StateFlow<Boolean> = _hasLoadedVault.asStateFlow()

    val filteredLoot: StateFlow<List<LootCard>> = combine(
        _lootCards,
        _selectedFilter,
        _vaultQuery
    ) { cards, filter, query ->
        val trimmed = query.trim()
        cards.filter { card ->
            (filter == null || card.payloadType == filter) &&
                (
                    trimmed.isEmpty() ||
                        card.name.contains(trimmed, ignoreCase = true) ||
                        card.fileName.contains(trimmed, ignoreCase = true) ||
                        card.tags.any { tag -> tag.contains(trimmed, ignoreCase = true) }
                    )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vaultStats: StateFlow<Map<PayloadType, Int>> = _lootCards
        .map { cards -> cards.groupBy { it.payloadType }.mapValues { it.value.size } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── Code sheet ───────────────────────────────────────────────────────

    private val _editTarget = MutableStateFlow<EditTarget?>(null)
    val editTarget: StateFlow<EditTarget?> = _editTarget.asStateFlow()

    private val _editContent = MutableStateFlow("")
    val editContent: StateFlow<String> = _editContent.asStateFlow()

    private val _editValidation = MutableStateFlow<ForgeValidator.ValidationResult?>(null)
    val editValidation: StateFlow<ForgeValidator.ValidationResult?> = _editValidation.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _sheetError = MutableStateFlow<String?>(null)
    val sheetError: StateFlow<String?> = _sheetError.asStateFlow()

    // ── Messages ─────────────────────────────────────────────────────────

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadVault()
    }

    // ── Forge actions ────────────────────────────────────────────────────

    fun updateForgeInput(text: String) {
        _forgeInput.value = text
        if (_forgeError.value != null) _forgeError.value = null
    }

    fun forge() {
        val input = _forgeInput.value.trim()
        if (input.isEmpty() || _isForging.value) return

        viewModelScope.launch {
            _isForging.value = true
            _forgeError.value = null
            _deployError.value = null

            try {
                val result = forgeEngine.forge(input)
                _currentBlueprint.value = result.blueprint
                _forgeValidation.value = result.validation

                if (result.validation.errors.isNotEmpty()) {
                    _forgeError.value = "Validation: " + result.validation.errors.joinToString("; ")
                }
                if (result.usedFallback) {
                    _message.value = "The model was unavailable, so an offline template was used"
                }
            } catch (e: Exception) {
                _forgeError.value = "The Forge could not reach a model. An offline template is shown."
                val fallback = runCatching { forgeEngine.fallback(input) }.getOrNull()
                if (fallback != null) {
                    _currentBlueprint.value = fallback.blueprint
                    _forgeValidation.value = fallback.validation
                }
            } finally {
                _isForging.value = false
            }
        }
    }

    /**
     * Writes the blueprint to the Flipper.
     *
     * This is a direct file write through [FlipperFileSystem]; it does not pass
     * through CommandExecutor, so it is not risk-classified or approval-gated.
     * The Forge card shows the payload risk separately.
     */
    fun deployBlueprint() {
        val blueprint = _currentBlueprint.value ?: return
        if (blueprint.status == ForgeStatus.FORGING) return
        val validation = _forgeValidation.value
        if (validation != null && !validation.valid) {
            _deployError.value = "Fix the validation errors before deploying."
            return
        }
        // Same gate the executor applies: a protected path needs an unlock
        // from Settings, even though this write bypasses CommandExecutor.
        if (ProtectedPaths.isProtected(blueprint.flipperPath) &&
            !permissionService.isProtectedPathUnlocked(blueprint.flipperPath)
        ) {
            _deployError.value = "That path is locked. Unlock it in Settings, then deploy."
            return
        }

        viewModelScope.launch {
            _deployError.value = null
            _currentBlueprint.value = blueprint.copy(status = ForgeStatus.FORGING)

            val dirPath = blueprint.flipperPath.substringBeforeLast("/")
            if (dirPath.isNotEmpty() && dirPath != blueprint.flipperPath) {
                runCatching { fileSystem.createDirectory(dirPath) }
            }

            val result = runCatching { fileSystem.writeFile(blueprint.flipperPath, blueprint.generatedCode) }
            val written = result.getOrNull()

            val current = _currentBlueprint.value ?: blueprint
            val succeeded = written != null && written.isSuccess
            if (succeeded) {
                _currentBlueprint.value = current.copy(status = ForgeStatus.FORGED)
                _message.value = "Saved to " + blueprint.flipperPath
                auditService.log(
                    AuditEntry(
                        actionType = AuditActionType.COMMAND_EXECUTED,
                        riskLevel = RiskLevel.MEDIUM,
                        userApproved = true,
                        approvalMethod = ApprovalMethod.HOLD_CONFIRM,
                        sessionId = auditService.getCurrentSessionId() ?: "forge",
                        metadata = mapOf(
                            "source" to "forge",
                            "path" to blueprint.flipperPath,
                            "bytes" to blueprint.generatedCode.length.toString()
                        )
                    )
                )
                loadVault()
            } else {
                _currentBlueprint.value = current.copy(status = ForgeStatus.FAILED)
                val reason = written?.exceptionOrNull()?.message
                    ?: result.exceptionOrNull()?.message
                    ?: "The write did not complete."
                _deployError.value = "Deploy failed: " + reason
            }
        }
    }

    fun clearBlueprint() {
        _currentBlueprint.value = null
        _forgeValidation.value = null
        _forgeError.value = null
        _deployError.value = null
        _forgeInput.value = ""
    }

    fun clearForgeError() {
        _forgeError.value = null
    }

    fun clearDeployError() {
        _deployError.value = null
    }

    // ── Vault actions ────────────────────────────────────────────────────

    fun setFilter(type: PayloadType?) {
        _selectedFilter.value = type
    }

    fun updateVaultQuery(query: String) {
        _vaultQuery.value = query
    }

    fun loadVault() {
        if (_isLoadingVault.value) return
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
                val listing = runCatching { fileSystem.listDirectory(dir) }.getOrNull()
                val entries = listing?.getOrNull().orEmpty()
                for (entry in entries.filter { !it.isDirectory }) {
                    val detected = LootClassifier.detectPayloadType(entry.name, entry.path)
                    val type = if (detected == PayloadType.UNKNOWN) expectedType else detected

                    var metadata = emptyMap<String, String>()
                    var tags = emptyList<String>()
                    var preview: String? = null
                    val content = runCatching { fileSystem.readFile(entry.path) }.getOrNull()
                    val text = content?.getOrNull()
                    if (text != null) {
                        metadata = LootClassifier.parseMetadata(type, text)
                        tags = LootClassifier.autoTag(type, text)
                        preview = text.take(200)
                    }

                    allLoot.add(
                        LootCard(
                            name = entry.name.substringBeforeLast("."),
                            fileName = entry.name,
                            path = entry.path,
                            payloadType = type,
                            rarity = LootClassifier.classifyRarity(type, metadata),
                            metadata = metadata,
                            size = entry.size,
                            capturedAt = entry.modifiedTimestamp ?: System.currentTimeMillis(),
                            tags = tags,
                            previewData = preview
                        )
                    )
                }
            }

            _lootCards.value = allLoot.sortedByDescending { it.capturedAt }
            _hasLoadedVault.value = true
            _isLoadingVault.value = false
        }
    }

    fun deleteLoot(loot: LootCard) {
        viewModelScope.launch {
            val result = runCatching { fileSystem.deleteFile(loot.path) }
            val deleted = result.getOrNull()
            if (deleted != null && deleted.isSuccess) {
                _lootCards.value = _lootCards.value.filter { it.id != loot.id }
                _message.value = "Deleted " + loot.name
                if ((_editTarget.value as? EditTarget.Vault)?.loot?.id == loot.id) closeWorkbench()
            } else {
                _message.value = "Delete failed: " + (deleted?.exceptionOrNull()?.message ?: "unknown error")
            }
        }
    }

    fun duplicateLoot(loot: LootCard) {
        viewModelScope.launch {
            val content = runCatching { fileSystem.readFile(loot.path) }.getOrNull()?.getOrNull()
            if (content == null) {
                _message.value = "Could not read " + loot.fileName
                return@launch
            }
            val extension = loot.fileName.substringAfterLast(".", "")
            val directory = loot.path.substringBeforeLast("/")
            val copyName = loot.name + "_copy" + if (extension.isBlank()) "" else "." + extension
            val result = runCatching { fileSystem.writeFile(directory + "/" + copyName, content) }
                .getOrNull()
            if (result != null && result.isSuccess) {
                _message.value = "Duplicated as " + copyName
                loadVault()
            } else {
                _message.value = "Duplicate failed"
            }
        }
    }

    // ── Code sheet ───────────────────────────────────────────────────────

    fun openInWorkbench(loot: LootCard) {
        _editTarget.value = EditTarget.Vault(loot)
        _editContent.value = ""
        _editValidation.value = null
        _sheetError.value = null
        viewModelScope.launch {
            val content = runCatching { fileSystem.readFile(loot.path) }.getOrNull()
            if (content != null && content.isSuccess) {
                val text = content.getOrDefault("")
                _editContent.value = text
                _editValidation.value = ForgeValidator.validate(text, loot.payloadType)
            } else {
                _sheetError.value = "This file could not be read. Check the connection."
            }
        }
    }

    fun openBlueprintEditor() {
        val blueprint = _currentBlueprint.value ?: return
        _editTarget.value = EditTarget.Blueprint
        _editContent.value = blueprint.generatedCode
        _editValidation.value = ForgeValidator.validate(blueprint.generatedCode, blueprint.payloadType)
        _sheetError.value = null
    }

    fun updateEditContent(content: String) {
        _editContent.value = content
        val type = editPayloadType()
        _editValidation.value = if (type == null) null else ForgeValidator.validate(content, type)
        _sheetError.value = null
    }

    fun saveWorkbench() {
        val target = _editTarget.value ?: return
        val content = _editContent.value
        if (_isSaving.value) return

        val validation = _editValidation.value
        if (validation != null && !validation.valid) {
            _sheetError.value = "Fix the validation errors first."
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            _sheetError.value = null

            when (target) {
                is EditTarget.Vault -> {
                    val result = runCatching { fileSystem.writeFile(target.loot.path, content) }
                        .getOrNull()
                    if (result != null && result.isSuccess) {
                        _message.value = "Saved " + target.loot.fileName
                        closeWorkbench()
                    } else {
                        _sheetError.value = "Save failed: " +
                            (result?.exceptionOrNull()?.message ?: "the write did not complete")
                    }
                }

                EditTarget.Blueprint -> {
                    val blueprint = _currentBlueprint.value
                    if (blueprint != null) {
                        val updated = blueprint.copy(
                            generatedCode = content,
                            status = ForgeStatus.BLUEPRINT
                        )
                        _currentBlueprint.value = updated
                        _forgeValidation.value = ForgeValidator.validate(content, blueprint.payloadType)
                        _deployError.value = null
                        _message.value = "Blueprint updated. Deploy when it validates."
                    }
                    closeWorkbench()
                }
            }

            _isSaving.value = false
        }
    }

    fun closeWorkbench() {
        _editTarget.value = null
        _editContent.value = ""
        _editValidation.value = null
        _sheetError.value = null
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Payload type used to validate the open sheet. */
    fun editPayloadType(): PayloadType? = when (val target = _editTarget.value) {
        is EditTarget.Vault -> target.loot.payloadType
        EditTarget.Blueprint -> _currentBlueprint.value?.payloadType
        null -> null
    }
}
