package com.vesper.flipper.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ai.*
import com.vesper.flipper.ai.ToolResult as AiToolResult
import com.vesper.flipper.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * PayloadLabViewModel - 3-Step AI Payload Generation
 *
 * Orchestrates the optimal workflow:
 * 1. GENERATE - Create initial payload with detailed prompting
 * 2. VALIDATE - Syntax check, security review, optimization
 * 3. EXECUTE  - Save to Flipper, search web, execute commands
 */
@HiltViewModel
class PayloadLabViewModel @Inject constructor(
    private val payloadEngine: PayloadEngine,
    private val toolExecutor: FlipperToolExecutor,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ============================================================
    // BADUSB STATE
    // ============================================================

    private val _badUsbPrompt = MutableStateFlow("")
    val badUsbPrompt: StateFlow<String> = _badUsbPrompt.asStateFlow()

    private val _badUsbPlatform = MutableStateFlow(BadUsbPlatform.WINDOWS)
    val badUsbPlatform: StateFlow<BadUsbPlatform> = _badUsbPlatform.asStateFlow()

    private val _badUsbExecutionMode = MutableStateFlow(ExecutionMode.NORMAL)
    val badUsbExecutionMode: StateFlow<ExecutionMode> = _badUsbExecutionMode.asStateFlow()

    private val _generatedScript = MutableStateFlow<String?>(null)
    val generatedScript: StateFlow<String?> = _generatedScript.asStateFlow()

    private val _scriptName = MutableStateFlow("payload")
    val scriptName: StateFlow<String> = _scriptName.asStateFlow()

    private val _badUsbMetadata = MutableStateFlow<Map<String, Any>>(emptyMap())
    val badUsbMetadata: StateFlow<Map<String, Any>> = _badUsbMetadata.asStateFlow()

    // ============================================================
    // EVIL PORTAL STATE
    // ============================================================

    private val _portalScreenshot = MutableStateFlow<Uri?>(null)
    val portalScreenshot: StateFlow<Uri?> = _portalScreenshot.asStateFlow()

    private val _portalPrompt = MutableStateFlow("")
    val portalPrompt: StateFlow<String> = _portalPrompt.asStateFlow()

    private val _portalType = MutableStateFlow(PortalType.GENERIC)
    val portalType: StateFlow<PortalType> = _portalType.asStateFlow()

    private val _generatedHtml = MutableStateFlow<String?>(null)
    val generatedHtml: StateFlow<String?> = _generatedHtml.asStateFlow()

    private val _portalName = MutableStateFlow("portal")
    val portalName: StateFlow<String> = _portalName.asStateFlow()

    private val _portalMetadata = MutableStateFlow<Map<String, Any>>(emptyMap())
    val portalMetadata: StateFlow<Map<String, Any>> = _portalMetadata.asStateFlow()

    // ============================================================
    // GENERATION PROGRESS STATE
    // ============================================================

    private val _generationPhase = MutableStateFlow(GenerationPhase.IDLE)
    val generationPhase: StateFlow<GenerationPhase> = _generationPhase.asStateFlow()

    private val _phaseMessage = MutableStateFlow("")
    val phaseMessage: StateFlow<String> = _phaseMessage.asStateFlow()

    private val _rawOutput = MutableStateFlow<String?>(null)
    val rawOutput: StateFlow<String?> = _rawOutput.asStateFlow()

    // ============================================================
    // SUGGESTED ACTIONS STATE
    // ============================================================

    private val _suggestedActions = MutableStateFlow<List<SuggestedAction>>(emptyList())
    val suggestedActions: StateFlow<List<SuggestedAction>> = _suggestedActions.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<SearchSuggestion>>(emptyList())
    val searchSuggestions: StateFlow<List<SearchSuggestion>> = _searchSuggestions.asStateFlow()

    // ============================================================
    // COMMON STATE
    // ============================================================

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    private val _saveSuccess = MutableStateFlow<String?>(null)
    val saveSuccess: StateFlow<String?> = _saveSuccess.asStateFlow()

    // Templates
    val badUsbTemplates = BadUsbTemplates.allTemplates
    val evilPortalTemplates = EvilPortalTemplates.allTemplates

    // ============================================================
    // BADUSB FUNCTIONS
    // ============================================================

    fun updateBadUsbPrompt(prompt: String) {
        _badUsbPrompt.value = prompt
    }

    fun updateBadUsbPlatform(platform: BadUsbPlatform) {
        _badUsbPlatform.value = platform
    }

    fun updateBadUsbExecutionMode(mode: ExecutionMode) {
        _badUsbExecutionMode.value = mode
    }

    fun updateScriptName(name: String) {
        _scriptName.value = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }

    fun loadBadUsbTemplate(template: BadUsbPayload) {
        _generatedScript.value = template.script
        _scriptName.value = template.name.lowercase().replace(" ", "_")
        _badUsbPlatform.value = template.platform
        _generationPhase.value = GenerationPhase.COMPLETE
        _suggestedActions.value = listOf(
            SuggestedAction("save", "Save to Flipper", "/ext/badusb/${_scriptName.value}.txt", "Deploy template")
        )
    }

    /**
     * MAIN ENTRY: Generate BadUSB script with 3-step pipeline
     */
    fun generateBadUsbScript() {
        val prompt = _badUsbPrompt.value.trim()
        if (prompt.isEmpty()) {
            _error.value = "Please describe what you want the script to do"
            return
        }

        viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _warnings.value = emptyList()
            _generationPhase.value = GenerationPhase.GENERATING

            val request = BadUsbRequest(
                description = prompt,
                platform = _badUsbPlatform.value,
                executionMode = _badUsbExecutionMode.value
            )

            val result = payloadEngine.generateBadUsb(request) { progress ->
                handleProgress(progress)
            }

            when (result) {
                is PayloadResult.Success -> {
                    _generatedScript.value = result.payload
                    _badUsbMetadata.value = result.metadata
                    _warnings.value = result.warnings
                    _suggestedActions.value = result.suggestedActions.ifEmpty {
                        getDefaultBadUsbActions()
                    }
                    _searchSuggestions.value = toolExecutor.getSearchSuggestions(
                        PayloadType.BAD_USB,
                        prompt
                    )
                    _generationPhase.value = GenerationPhase.COMPLETE
                }
                is PayloadResult.Error -> {
                    _error.value = result.message
                    _generationPhase.value = GenerationPhase.ERROR
                }
            }

            _isGenerating.value = false
        }
    }

    /**
     * Save BadUSB script to Flipper
     */
    fun saveBadUsbScript() {
        val script = _generatedScript.value ?: return
        val name = _scriptName.value.ifEmpty { "payload" }

        viewModelScope.launch {
            _isSaving.value = true
            _error.value = null

            val result = toolExecutor.saveBadUsbScript(name, script)
            when (result) {
                is AiToolResult.Success -> {
                    _saveSuccess.value = result.message
                }
                is AiToolResult.Error -> {
                    _error.value = result.message
                }
            }

            _isSaving.value = false
        }
    }

    /**
     * Execute a suggested action for BadUSB
     */
    fun executeBadUsbAction(action: SuggestedAction) {
        viewModelScope.launch {
            _isExecuting.value = true

            when (action.type) {
                "save" -> {
                    val script = _generatedScript.value ?: return@launch
                    val result = toolExecutor.savePayload(action.value, script)
                    handleToolResult(result)
                }
                "search" -> {
                    openWebSearch(action.value)
                }
                "execute", "run" -> {
                    val result = toolExecutor.executeFlipperCommand(action.value)
                    handleToolResult(result)
                }
            }

            _isExecuting.value = false
        }
    }

    private fun getDefaultBadUsbActions(): List<SuggestedAction> {
        val name = _scriptName.value.ifEmpty { "payload" }
        return listOf(
            SuggestedAction("save", "Save to Flipper", "/ext/badusb/$name.txt", "Save script to Flipper"),
            SuggestedAction("run", "Save & Run", "badusb /ext/badusb/$name.txt", "Deploy and execute immediately")
        )
    }

    // ============================================================
    // EVIL PORTAL FUNCTIONS
    // ============================================================

    fun setPortalScreenshot(uri: Uri?) {
        _portalScreenshot.value = uri
    }

    fun updatePortalPrompt(prompt: String) {
        _portalPrompt.value = prompt
    }

    fun updatePortalType(type: PortalType) {
        _portalType.value = type
    }

    fun updatePortalName(name: String) {
        _portalName.value = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }

    fun loadEvilPortalTemplate(template: EvilPortalPayload) {
        _generatedHtml.value = template.html
        _portalName.value = template.name.lowercase().replace(" ", "_")
        _generationPhase.value = GenerationPhase.COMPLETE
        _suggestedActions.value = listOf(
            SuggestedAction("save", "Deploy Portal", "/ext/apps_data/evil_portal/portals/${_portalName.value}/index.html", "Deploy template")
        )
    }

    /**
     * MAIN ENTRY: Generate Evil Portal with 3-step pipeline
     */
    fun generateEvilPortal() {
        viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _warnings.value = emptyList()
            _generationPhase.value = GenerationPhase.GENERATING

            try {
                val screenshotUri = _portalScreenshot.value
                val additionalPrompt = _portalPrompt.value.trim()

                if (screenshotUri == null && additionalPrompt.isEmpty()) {
                    _error.value = "Please provide a screenshot or description"
                    _isGenerating.value = false
                    return@launch
                }

                // Encode screenshot if provided
                val screenshotBase64 = screenshotUri?.let { encodeScreenshot(it) }

                val request = EvilPortalRequest(
                    description = additionalPrompt,
                    portalType = _portalType.value,
                    screenshotBase64 = screenshotBase64
                )

                val result = payloadEngine.generateEvilPortal(request) { progress ->
                    handleProgress(progress)
                }

                when (result) {
                    is PayloadResult.Success -> {
                        _generatedHtml.value = result.payload
                        _portalMetadata.value = result.metadata
                        _warnings.value = result.warnings
                        _suggestedActions.value = result.suggestedActions.ifEmpty {
                            getDefaultPortalActions()
                        }
                        _searchSuggestions.value = toolExecutor.getSearchSuggestions(
                            PayloadType.UNKNOWN,
                            _portalType.value.name
                        )
                        _generationPhase.value = GenerationPhase.COMPLETE
                    }
                    is PayloadResult.Error -> {
                        _error.value = result.message
                        _generationPhase.value = GenerationPhase.ERROR
                    }
                }
            } catch (e: Exception) {
                _error.value = "Generation failed: ${e.message}"
                _generationPhase.value = GenerationPhase.ERROR
            }

            _isGenerating.value = false
        }
    }

    /**
     * Save Evil Portal to Flipper
     */
    fun saveEvilPortal() {
        val html = _generatedHtml.value ?: return
        val name = _portalName.value.ifEmpty { "portal" }

        viewModelScope.launch {
            _isSaving.value = true
            _error.value = null

            val result = toolExecutor.saveEvilPortal(name, html)
            when (result) {
                is AiToolResult.Success -> {
                    _saveSuccess.value = result.message
                }
                is AiToolResult.Error -> {
                    _error.value = result.message
                }
            }

            _isSaving.value = false
        }
    }

    /**
     * Execute a suggested action for Evil Portal
     */
    fun executePortalAction(action: SuggestedAction) {
        viewModelScope.launch {
            _isExecuting.value = true

            when (action.type) {
                "save" -> {
                    val html = _generatedHtml.value ?: return@launch
                    val name = _portalName.value.ifEmpty { "portal" }
                    val result = toolExecutor.saveEvilPortal(name, html)
                    handleToolResult(result)
                }
                "search" -> {
                    openWebSearch(action.value)
                }
                "execute" -> {
                    val result = toolExecutor.executeFlipperCommand(action.value)
                    handleToolResult(result)
                }
            }

            _isExecuting.value = false
        }
    }

    private fun getDefaultPortalActions(): List<SuggestedAction> {
        val name = _portalName.value.ifEmpty { "portal" }
        return listOf(
            SuggestedAction("save", "Deploy Portal", "/ext/apps_data/evil_portal/portals/$name/index.html", "Save to Flipper")
        )
    }

    private suspend fun encodeScreenshot(uri: Uri): String = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot read image")
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        // Resize if needed
        val maxSize = 1024
        val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val ratio = bitmap.width.toFloat() / bitmap.height
            val (newWidth, newHeight) = if (bitmap.width > bitmap.height) {
                maxSize to (maxSize / ratio).toInt()
            } else {
                (maxSize * ratio).toInt() to maxSize
            }
            bitmap.scale(newWidth, newHeight, filter = true)
        } else {
            bitmap
        }

        // Encode to base64
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    // ============================================================
    // FLIPPER COMMAND EXECUTION
    // ============================================================

    /**
     * Execute IR transmission
     */
    fun transmitIr(irPath: String, signalName: String? = null) {
        viewModelScope.launch {
            _isExecuting.value = true
            val result = toolExecutor.transmitIr(irPath, signalName)
            handleToolResult(result)
            _isExecuting.value = false
        }
    }

    /**
     * Execute Sub-GHz transmission
     */
    fun transmitSubGhz(subPath: String) {
        viewModelScope.launch {
            _isExecuting.value = true
            val result = toolExecutor.transmitSubGhz(subPath)
            handleToolResult(result)
            _isExecuting.value = false
        }
    }

    /**
     * Start BLE spam attack
     */
    fun startBleSpam(type: BleSpamType) {
        viewModelScope.launch {
            _isExecuting.value = true
            val result = toolExecutor.startBleSpam(type)
            handleToolResult(result)
            _isExecuting.value = false
        }
    }

    /**
     * Stop BLE spam
     */
    fun stopBleSpam() {
        startBleSpam(BleSpamType.STOP)
    }

    /**
     * Execute raw Flipper command
     */
    fun executeFlipperCommand(command: String) {
        viewModelScope.launch {
            _isExecuting.value = true
            val result = toolExecutor.executeFlipperCommand(command)
            handleToolResult(result)
            _isExecuting.value = false
        }
    }

    // ============================================================
    // WEB SEARCH
    // ============================================================

    fun openWebSearch(query: String) {
        val intent = toolExecutor.createWebSearchIntent(query)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun executeSearchSuggestion(suggestion: SearchSuggestion) {
        openWebSearch(suggestion.query)
    }

    // ============================================================
    // HELPER FUNCTIONS
    // ============================================================

    private fun handleProgress(progress: PayloadProgress) {
        when (progress) {
            is PayloadProgress.Generating -> {
                _generationPhase.value = GenerationPhase.GENERATING
                _phaseMessage.value = progress.message
            }
            is PayloadProgress.Generated -> {
                _rawOutput.value = progress.rawPayload
                _phaseMessage.value = "Initial generation complete"
            }
            is PayloadProgress.Validating -> {
                _generationPhase.value = GenerationPhase.VALIDATING
                _phaseMessage.value = progress.message
            }
            is PayloadProgress.Validated -> {
                _phaseMessage.value = "Validation complete"
            }
            is PayloadProgress.Ready -> {
                _generationPhase.value = GenerationPhase.READY
                _phaseMessage.value = "Ready for deployment"
            }
        }
    }

    private fun handleToolResult(result: AiToolResult) {
        when (result) {
            is AiToolResult.Success -> {
                _saveSuccess.value = result.message
            }
            is AiToolResult.Error -> {
                _error.value = result.message
            }
        }
    }

    // ============================================================
    // CLEAR FUNCTIONS
    // ============================================================

    fun clearError() {
        _error.value = null
    }

    fun clearSuccess() {
        _saveSuccess.value = null
    }

    fun clearWarnings() {
        _warnings.value = emptyList()
    }

    fun clearBadUsbGenerated() {
        _generatedScript.value = null
        _rawOutput.value = null
        _badUsbMetadata.value = emptyMap()
        _suggestedActions.value = emptyList()
        _generationPhase.value = GenerationPhase.IDLE
    }

    fun clearPortalGenerated() {
        _generatedHtml.value = null
        _portalScreenshot.value = null
        _rawOutput.value = null
        _portalMetadata.value = emptyMap()
        _suggestedActions.value = emptyList()
        _generationPhase.value = GenerationPhase.IDLE
    }
}

/**
 * Generation phase for progress tracking
 */
enum class GenerationPhase {
    IDLE,           // Not started
    GENERATING,     // Step 1: Initial generation
    VALIDATING,     // Step 2: Validation & cleanup
    READY,          // Step 3: Ready for execution
    COMPLETE,       // Fully complete
    ERROR           // Error occurred
}
