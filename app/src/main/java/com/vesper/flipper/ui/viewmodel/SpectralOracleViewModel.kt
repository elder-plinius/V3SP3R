package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.BleServiceManager
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.AnalysisContext
import com.vesper.flipper.domain.model.AnalysisType
import com.vesper.flipper.domain.model.CapturedSignalData
import com.vesper.flipper.domain.model.OraclePrompts
import com.vesper.flipper.domain.model.OracleRiskLevel
import com.vesper.flipper.domain.model.SecurityLevel
import com.vesper.flipper.domain.model.SignalCapture
import com.vesper.flipper.domain.model.SignalIntelRequest
import com.vesper.flipper.domain.model.SignalParser
import com.vesper.flipper.domain.model.SignalType
import com.vesper.flipper.domain.model.VulnerabilitySeverity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Steps of the Oracle flow. The screen renders one step at a time. */
enum class OracleStep {
    PICK_CAPTURE,
    PICK_TYPE,
    RUNNING,
    RESULT
}

/** Coarse milestones in the analysis, used for the determinate progress bar. */
enum class OracleStage(val label: String, val progress: Float) {
    PREPARING("Preparing the capture", 0.2f),
    ANALYZING("Analyzing the capture", 0.65f),
    FINISHING("Building the report", 0.9f)
}

data class SpectralOracleUiState(
    val step: OracleStep = OracleStep.PICK_CAPTURE,
    val captures: List<SignalCapture> = emptyList(),
    val selected: SignalCapture? = null,
    val analysisType: AnalysisType = AnalysisType.FULL_ANALYSIS,
    val isLoadingCaptures: Boolean = true,
    val isDeviceConnected: Boolean = false,
    val loadError: String? = null,
    val isAnalyzing: Boolean = false,
    val stage: OracleStage? = null,
    val progress: Float = 0f,
    val result: OracleAnalysisResult? = null,
    val errorMessage: String? = null,
    val hasApiKey: Boolean? = null,
    val notice: String? = null
)

@HiltViewModel
class SpectralOracleViewModel @Inject constructor(
    private val fileSystem: FlipperFileSystem,
    private val settingsStore: SettingsStore,
    private val bleServiceManager: BleServiceManager
) : ViewModel() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(SpectralOracleUiState())
    val uiState: StateFlow<SpectralOracleUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null

    @Volatile
    private var activeCall: Call? = null

    init {
        observeConnection()
        loadCaptures()
        refreshApiKeyStatus()
    }

    fun refreshApiKeyStatus() {
        viewModelScope.launch {
            val hasKey = settingsStore.hasApiKey()
            _uiState.update { it.copy(hasApiKey = hasKey) }
        }
    }

    fun loadCaptures() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCaptures = true, loadError = null) }

            val entries = withContext(Dispatchers.IO) {
                fileSystem.listDirectory(SignalType.SUBGHZ.directory)
            }
            if (entries.isFailure) {
                val connected = _uiState.value.isDeviceConnected
                _uiState.update {
                    it.copy(
                        isLoadingCaptures = false,
                        captures = emptyList(),
                        loadError = if (connected) {
                            "Could not read Sub-GHz captures. " +
                                "Reconnect the Flipper in Device, then retry."
                        } else {
                            null
                        }
                    )
                }
                return@launch
            }

            val captures = withContext(Dispatchers.IO) {
                entries.getOrThrow()
                    .filter {
                        !it.isDirectory &&
                            it.name.endsWith(SignalType.SUBGHZ.extension, ignoreCase = true)
                    }
                    .mapNotNull { entry ->
                        val content = fileSystem.readFile(entry.path).getOrNull()
                            ?: return@mapNotNull null
                        SignalParser.parseSubGhz(
                            content = content,
                            path = entry.path,
                            name = entry.name.removeSuffix(SignalType.SUBGHZ.extension)
                        )
                    }
                    .sortedByDescending { it.timestamp }
            }

            _uiState.update { state ->
                val currentPath = state.selected?.path
                val refreshedSelection = captures.firstOrNull { it.path == currentPath }
                state.copy(
                    isLoadingCaptures = false,
                    captures = captures,
                    selected = refreshedSelection,
                    step = if (currentPath != null && refreshedSelection == null) {
                        OracleStep.PICK_CAPTURE
                    } else {
                        state.step
                    },
                    loadError = null
                )
            }
        }
    }

    fun selectCapture(signal: SignalCapture) {
        _uiState.update {
            it.copy(
                selected = signal,
                step = OracleStep.PICK_TYPE,
                result = null,
                errorMessage = null
            )
        }
    }

    fun setAnalysisType(type: AnalysisType) {
        _uiState.update { it.copy(analysisType = type) }
    }

    fun analyze() {
        if (_uiState.value.isAnalyzing) return
        val state = _uiState.value
        val signal = state.selected
        if (signal == null) {
            _uiState.update {
                it.copy(errorMessage = "Pick a capture before running an analysis.")
            }
            return
        }
        val analysisType = state.analysisType

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    step = OracleStep.RUNNING,
                    isAnalyzing = true,
                    stage = OracleStage.PREPARING,
                    progress = OracleStage.PREPARING.progress,
                    result = null,
                    errorMessage = null
                )
            }

            try {
                val hasKey = settingsStore.hasApiKey()
                _uiState.update { it.copy(hasApiKey = hasKey) }
                if (!hasKey) throw MissingApiKeyException()

                val request = SignalIntelRequest(
                    signalData = signalToCapturedData(signal),
                    analysisType = analysisType,
                    context = AnalysisContext(securityLevel = SecurityLevel.DEEP)
                )

                _uiState.update {
                    it.copy(stage = OracleStage.ANALYZING, progress = OracleStage.ANALYZING.progress)
                }
                val result = performAIAnalysis(request)

                _uiState.update {
                    it.copy(stage = OracleStage.FINISHING, progress = OracleStage.FINISHING.progress)
                }
                _uiState.update {
                    it.copy(
                        step = OracleStep.RESULT,
                        isAnalyzing = false,
                        stage = null,
                        progress = 1f,
                        result = result,
                        errorMessage = null
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                _uiState.update {
                    it.copy(
                        step = OracleStep.PICK_TYPE,
                        isAnalyzing = false,
                        stage = null,
                        progress = 0f,
                        hasApiKey = if (error is MissingApiKeyException) false else it.hasApiKey,
                        errorMessage = actionableMessage(error)
                    )
                }
            }
        }
    }

    fun cancelAnalysis() {
        val wasRunning = _uiState.value.isAnalyzing
        analysisJob?.cancel()
        analysisJob = null
        activeCall?.cancel()
        activeCall = null
        _uiState.update {
            it.copy(
                step = if (wasRunning) OracleStep.PICK_TYPE else it.step,
                isAnalyzing = false,
                stage = null,
                progress = 0f,
                errorMessage = null,
                notice = if (wasRunning) "Analysis cancelled." else it.notice
            )
        }
    }

    fun previousStep() {
        _uiState.update { state ->
            when (state.step) {
                OracleStep.PICK_CAPTURE -> state
                OracleStep.PICK_TYPE -> state.copy(step = OracleStep.PICK_CAPTURE)
                OracleStep.RUNNING -> state
                OracleStep.RESULT -> state.copy(step = OracleStep.PICK_TYPE)
            }
        }
    }

    fun startOver() {
        _uiState.update {
            it.copy(
                step = OracleStep.PICK_CAPTURE,
                selected = null,
                result = null,
                errorMessage = null,
                stage = null,
                progress = 0f
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, loadError = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            bleServiceManager.connectionState.collect { connection ->
                val connected = connection is ConnectionState.Connected
                val wasConnected = _uiState.value.isDeviceConnected
                _uiState.update { it.copy(isDeviceConnected = connected) }
                if (connected && !wasConnected &&
                    _uiState.value.captures.isEmpty() &&
                    !_uiState.value.isLoadingCaptures
                ) {
                    loadCaptures()
                }
            }
        }
    }

    private fun signalToCapturedData(signal: SignalCapture): CapturedSignalData {
        return when (signal) {
            is SignalCapture.SubGhz -> CapturedSignalData(
                frequency = signal.frequency,
                rawTimings = signal.rawData ?: emptyList(),
                modulationType = signal.preset.takeIf { it.isNotEmpty() },
                captureTimestamp = signal.timestamp,
                metadata = mapOf(
                    "protocol" to signal.protocol,
                    "bitLength" to (signal.bitLength?.toString() ?: "unknown"),
                    "keyData" to (signal.keyData ?: "none")
                )
            )

            else -> CapturedSignalData(
                frequency = 0L,
                rawTimings = emptyList(),
                modulationType = null,
                captureTimestamp = signal.timestamp
            )
        }
    }

    private suspend fun performAIAnalysis(request: SignalIntelRequest): OracleAnalysisResult =
        withContext(Dispatchers.IO) {
            val apiKey = settingsStore.apiKey.first()
            if (apiKey.isNullOrBlank()) throw MissingApiKeyException()

            val model = settingsStore.selectedModel.first()
            val prompt = OraclePrompts.buildAnalysisPrompt(request)

            val requestBody = """
            {
                "model": "$model",
                "messages": [
                    {
                        "role": "system",
                        "content": ${json.encodeToString(kotlinx.serialization.serializer(), ORACLE_SYSTEM_PROMPT)}
                    },
                    {
                        "role": "user",
                        "content": ${json.encodeToString(kotlinx.serialization.serializer(), prompt)}
                    }
                ],
                "max_tokens": 4000,
                "temperature": 0.3
            }
            """.trimIndent()

            val httpRequest = Request.Builder()
                .url(ORACLE_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://vesper.flipper.app")
                .addHeader("X-Title", "Vesper Spectral Oracle")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(httpRequest)
            activeCall = call
            try {
                val response = call.execute()
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw OracleHttpException(response.code)
                if (responseBody.isBlank()) throw EmptyOracleResponseException()
                parseAIResponse(responseBody, request)
            } finally {
                activeCall = null
            }
        }

    private fun actionableMessage(error: Throwable): String = when (error) {
        is MissingApiKeyException ->
            "Add an OpenRouter API key in Settings, then run the analysis again."

        is OracleHttpException -> when (error.statusCode) {
            401, 403 -> "The analysis service rejected the API key. Update it in Settings, then retry."
            429 -> "The analysis service is rate limiting requests. Wait a minute, then retry."
            in 500..599 -> "The analysis service is unavailable right now. Retry in a moment."
            else -> "The analysis service rejected the request. Check the model and API key in Settings."
        }

        is UnknownHostException ->
            "Cannot reach the analysis service. Check the network connection, then retry."

        is SocketTimeoutException ->
            "The analysis service took too long to answer. Retry, or pick a faster model in Settings."

        is EmptyOracleResponseException ->
            "The analysis service returned an empty answer. Retry, or try a different model in Settings."

        is IOException ->
            "The connection dropped before the analysis finished. Retry when the network is stable."

        else -> "The analysis could not be completed. Check the connection, then retry."
    }

    private fun parseAIResponse(responseBody: String, request: SignalIntelRequest): OracleAnalysisResult {
        val contentRegex = """"content"\s*:\s*"(.*?)(?<!\\)"""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = contentRegex.find(responseBody)
        val aiResponse = match?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: responseBody

        val sections = parseResponseSections(aiResponse)

        return OracleAnalysisResult(
            signalName = request.signalData.metadata["protocol"] ?: "Unknown signal",
            frequency = request.signalData.frequency,
            analysisType = request.analysisType,
            summary = extractSummary(aiResponse),

            protocolInfo = ProtocolInfo(
                name = extractProtocolName(sections["PROTOCOL IDENTIFICATION"] ?: aiResponse),
                family = extractValue(aiResponse, "family", "protocol family"),
                encoding = extractValue(aiResponse, "encoding", "modulation"),
                isEncrypted = aiResponse.lowercase().contains("encrypt") &&
                    !aiResponse.lowercase().contains("no encrypt") &&
                    !aiResponse.lowercase().contains("not encrypt"),
                isRollingCode = aiResponse.lowercase().contains("rolling code") &&
                    !aiResponse.lowercase().contains("no rolling") &&
                    !aiResponse.lowercase().contains("not rolling"),
                confidence = extractConfidence(aiResponse)
            ),

            vulnerabilities = parseVulnerabilities(sections["VULNERABILITIES"] ?: aiResponse),
            exploits = parseExploits(sections["EXPLOITS"] ?: sections["EXPLOIT"] ?: aiResponse),

            threatLevel = determineThreatLevel(aiResponse),
            threatAnalysis = sections["THREAT ASSESSMENT"] ?: extractSection(aiResponse, "threat", "risk"),

            recommendations = parseRecommendations(sections["RECOMMENDATIONS"] ?: aiResponse),

            rawResponse = aiResponse,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun parseResponseSections(response: String): Map<String, String> {
        val sections = mutableMapOf<String, String>()
        val sectionHeaders = listOf(
            "PROTOCOL IDENTIFICATION", "PROTOCOL ID", "PROTOCOL ANALYSIS",
            "VULNERABILITIES", "VULNERABILITY", "SECURITY ISSUES",
            "EXPLOITS", "EXPLOIT", "ATTACK VECTORS",
            "THREAT ASSESSMENT", "THREAT ANALYSIS", "RISK ASSESSMENT",
            "RECOMMENDATIONS", "MITIGATIONS", "COUNTERMEASURES"
        )

        var currentSection = ""
        var currentContent = StringBuilder()

        response.lines().forEach { line ->
            val headerMatch = sectionHeaders.find {
                line.uppercase().contains(it) && (line.startsWith("#") || line.startsWith("*"))
            }

            if (headerMatch != null) {
                if (currentSection.isNotEmpty()) {
                    sections[currentSection] = currentContent.toString().trim()
                }
                currentSection = headerMatch
                currentContent = StringBuilder()
            } else if (currentSection.isNotEmpty()) {
                currentContent.appendLine(line)
            }
        }

        if (currentSection.isNotEmpty()) {
            sections[currentSection] = currentContent.toString().trim()
        }

        return sections
    }

    private fun extractSummary(response: String): String {
        val firstPara = response.split("\n\n").firstOrNull()?.take(300) ?: response.take(300)
        return if (firstPara.length >= 300) "$firstPara..." else firstPara
    }

    private fun extractProtocolName(section: String): String {
        val patterns = listOf(
            """protocol[:\s]+(\w+)""".toRegex(RegexOption.IGNORE_CASE),
            """identified as[:\s]+(\w+)""".toRegex(RegexOption.IGNORE_CASE),
            """appears to be[:\s]+(\w+)""".toRegex(RegexOption.IGNORE_CASE),
            """(Princeton|KeeLoq|CAME|Nice|Chamberlain|Linear|GE|Somfy|Faac)""".toRegex(RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.find(section)?.groupValues?.get(1)?.let { return it }
        }
        return "Unknown"
    }

    private fun extractValue(text: String, vararg keywords: String): String {
        keywords.forEach { keyword ->
            val pattern = """$keyword[:\s]+([^\n,]+)""".toRegex(RegexOption.IGNORE_CASE)
            pattern.find(text)?.groupValues?.get(1)?.trim()?.let { return it }
        }
        return "Unknown"
    }

    private fun extractConfidence(response: String): Float {
        val pattern = """(\d+)%?\s*confidence""".toRegex(RegexOption.IGNORE_CASE)
        pattern.find(response)?.groupValues?.get(1)?.toFloatOrNull()?.let {
            return (it / 100f).coerceIn(0f, 1f)
        }

        return when {
            response.lowercase().contains("high confidence") -> 0.85f
            response.lowercase().contains("moderate confidence") -> 0.65f
            response.lowercase().contains("low confidence") -> 0.35f
            else -> 0.5f
        }
    }

    private fun parseVulnerabilities(section: String): List<VulnItem> {
        val vulns = mutableListOf<VulnItem>()

        val severityPatterns = mapOf(
            VulnerabilitySeverity.CRITICAL to listOf("critical", "severe", "dangerous"),
            VulnerabilitySeverity.HIGH to listOf("high", "significant", "major"),
            VulnerabilitySeverity.MEDIUM to listOf("medium", "moderate", "notable"),
            VulnerabilitySeverity.LOW to listOf("low", "minor", "minimal"),
            VulnerabilitySeverity.INFO to listOf("info", "informational", "note")
        )

        val itemPattern = """[-*•]\s*(.+?)(?=\n[-*•]|\n\n|$)""".toRegex(RegexOption.DOT_MATCHES_ALL)

        itemPattern.findAll(section).forEach { match ->
            val item = match.groupValues[1].trim()
            if (item.length > 10) {
                var severity = VulnerabilitySeverity.MEDIUM
                val lowerItem = item.lowercase()

                for ((sev, keywords) in severityPatterns) {
                    if (keywords.any { lowerItem.contains(it) }) {
                        severity = sev
                        break
                    }
                }

                vulns.add(
                    VulnItem(
                        name = item.take(60).let { if (it.length >= 60) "$it..." else it },
                        description = item,
                        severity = severity
                    )
                )
            }
        }

        if (vulns.isEmpty()) {
            severityPatterns.forEach { (severity, keywords) ->
                keywords.forEach { keyword ->
                    val pattern = """$keyword[:\s]+([^.\n]+)""".toRegex(RegexOption.IGNORE_CASE)
                    pattern.findAll(section).forEach { match ->
                        val desc = match.groupValues[1].trim()
                        if (desc.length > 5) {
                            vulns.add(
                                VulnItem(
                                    name = desc.take(50),
                                    description = desc,
                                    severity = severity
                                )
                            )
                        }
                    }
                }
            }
        }

        return vulns.distinctBy { it.name }.take(10)
    }

    private fun parseExploits(section: String): List<ExploitItem> {
        val exploits = mutableListOf<ExploitItem>()

        val exploitPatterns = listOf(
            """replay attack""".toRegex(RegexOption.IGNORE_CASE),
            """brute force""".toRegex(RegexOption.IGNORE_CASE),
            """jamming""".toRegex(RegexOption.IGNORE_CASE),
            """code grab""".toRegex(RegexOption.IGNORE_CASE),
            """timing attack""".toRegex(RegexOption.IGNORE_CASE),
            """rolljam""".toRegex(RegexOption.IGNORE_CASE)
        )

        exploitPatterns.forEach { pattern ->
            if (pattern.containsMatchIn(section)) {
                val name = pattern.pattern.replace("""\\""", "").replace("(?i)", "")
                    .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

                val contextPattern = """.{0,100}${pattern.pattern}.{0,200}""".toRegex(RegexOption.IGNORE_CASE)
                val context = contextPattern.find(section)?.value ?: ""

                exploits.add(
                    ExploitItem(
                        name = name,
                        description = context.trim(),
                        difficulty = if (context.lowercase().contains("trivial") ||
                            context.lowercase().contains("easy")
                        ) {
                            "Easy"
                        } else if (context.lowercase().contains("complex") ||
                            context.lowercase().contains("difficult")
                        ) {
                            "Hard"
                        } else {
                            "Moderate"
                        }
                    )
                )
            }
        }

        return exploits.distinctBy { it.name }.take(5)
    }

    private fun determineThreatLevel(response: String): OracleRiskLevel {
        val lower = response.lowercase()
        return when {
            lower.contains("critical") && (lower.contains("vuln") || lower.contains("risk")) ->
                OracleRiskLevel.CRITICAL
            lower.contains("high risk") || lower.contains("highly vulnerable") ->
                OracleRiskLevel.HIGH
            lower.contains("elevated") || lower.contains("significant risk") ->
                OracleRiskLevel.ELEVATED
            lower.contains("moderate") && lower.contains("risk") ->
                OracleRiskLevel.MODERATE
            lower.contains("low risk") || lower.contains("minimal") ->
                OracleRiskLevel.LOW
            lower.contains("secure") || lower.contains("protected") ->
                OracleRiskLevel.MINIMAL
            else -> OracleRiskLevel.MODERATE
        }
    }

    private fun extractSection(text: String, vararg keywords: String): String {
        keywords.forEach { keyword ->
            val pattern = """$keyword[:\s]*([^#*]+?)(?=\n#|\n\*\*|$)""".toRegex(RegexOption.IGNORE_CASE)
            pattern.find(text)?.groupValues?.get(1)?.trim()?.let {
                if (it.length > 20) return it
            }
        }
        return "No analysis section was returned."
    }

    private fun parseRecommendations(section: String): List<String> {
        val recs = mutableListOf<String>()

        val itemPattern = """[-*•\d.]\s*(.+)""".toRegex()
        section.lines().forEach { line ->
            itemPattern.find(line)?.groupValues?.get(1)?.trim()?.let {
                if (it.length > 10 && !it.startsWith("#")) {
                    recs.add(it)
                }
            }
        }

        return recs.take(8)
    }

    private companion object {
        const val ORACLE_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val ORACLE_SYSTEM_PROMPT =
            "You are Spectral Oracle, a radio frequency signals analyst. " +
                "Analyze captured signals for authorized security research. " +
                "Structure the response with the sections PROTOCOL IDENTIFICATION, " +
                "VULNERABILITIES, EXPLOITS, THREAT ASSESSMENT, and RECOMMENDATIONS."
    }
}

private class MissingApiKeyException : Exception("Missing API key")
private class OracleHttpException(val statusCode: Int) : Exception("HTTP $statusCode")
private class EmptyOracleResponseException : Exception("Empty response")

// Result data classes
data class OracleAnalysisResult(
    val signalName: String,
    val frequency: Long,
    val analysisType: AnalysisType,
    val summary: String,
    val protocolInfo: ProtocolInfo,
    val vulnerabilities: List<VulnItem>,
    val exploits: List<ExploitItem>,
    val threatLevel: OracleRiskLevel,
    val threatAnalysis: String,
    val recommendations: List<String>,
    val rawResponse: String,
    val timestamp: Long
)

data class ProtocolInfo(
    val name: String,
    val family: String,
    val encoding: String,
    val isEncrypted: Boolean,
    val isRollingCode: Boolean,
    val confidence: Float
)

data class VulnItem(
    val name: String,
    val description: String,
    val severity: VulnerabilitySeverity
)

data class ExploitItem(
    val name: String,
    val description: String,
    val difficulty: String
)
