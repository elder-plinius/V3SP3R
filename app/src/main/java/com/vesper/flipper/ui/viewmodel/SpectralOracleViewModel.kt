package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.data.awaitProviderResponse
import com.vesper.flipper.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class SpectralOracleViewModel @Inject constructor(
    private val fileSystem: FlipperFileSystem,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // Long timeout for analysis
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Available signals for analysis
    private val _availableSignals = MutableStateFlow<List<SignalCapture>>(emptyList())
    val availableSignals: StateFlow<List<SignalCapture>> = _availableSignals.asStateFlow()

    // Selected signal
    private val _selectedSignal = MutableStateFlow<SignalCapture?>(null)
    val selectedSignal: StateFlow<SignalCapture?> = _selectedSignal.asStateFlow()

    // Analysis type
    private val _analysisType = MutableStateFlow(AnalysisType.FULL_ANALYSIS)
    val analysisType: StateFlow<AnalysisType> = _analysisType.asStateFlow()

    // Analysis state
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analysisProgress = MutableStateFlow("")
    val analysisProgress: StateFlow<String> = _analysisProgress.asStateFlow()

    // Results
    private val _analysisResult = MutableStateFlow<OracleAnalysisResult?>(null)
    val analysisResult: StateFlow<OracleAnalysisResult?> = _analysisResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // History
    private val _analysisHistory = MutableStateFlow<List<AnalysisHistoryItem>>(emptyList())
    val analysisHistory: StateFlow<List<AnalysisHistoryItem>> = _analysisHistory.asStateFlow()

    init {
        loadAvailableSignals()
    }

    fun loadAvailableSignals() {
        viewModelScope.launch {
            val signals = mutableListOf<SignalCapture>()

            // Load SubGHz signals
            try {
                val subghzResult = fileSystem.listDirectory("/ext/subghz")
                if (subghzResult.isSuccess) {
                    subghzResult.getOrThrow()
                        .filter { it.name.endsWith(".sub") }
                        .forEach { file ->
                            loadSubGhzSignal(file.path, file.name)?.let { signals.add(it) }
                        }
                }
            } catch (e: Exception) { /* ignore */ }

            _availableSignals.value = signals.sortedByDescending { it.timestamp }
        }
    }

    private suspend fun loadSubGhzSignal(path: String, name: String): SignalCapture.SubGhz? {
        val contentResult = fileSystem.readFile(path)
        if (contentResult.isFailure) return null
        return SignalParser.parseSubGhz(contentResult.getOrThrow(), path, name.removeSuffix(".sub"))
    }

    fun selectSignal(signal: SignalCapture?) {
        _selectedSignal.value = signal
        _analysisResult.value = null
    }

    fun setAnalysisType(type: AnalysisType) {
        _analysisType.value = type
    }

    fun analyzeSignal() {
        val signal = _selectedSignal.value ?: return

        viewModelScope.launch {
            _isAnalyzing.value = true
            _error.value = null
            _analysisResult.value = null

            try {
                _analysisProgress.value = "Preparing signal data..."

                val capturedData = signalToCapturedData(signal)

                _analysisProgress.value = "Building analysis request..."

                val request = SignalIntelRequest(
                    signalData = capturedData,
                    analysisType = _analysisType.value,
                    context = AnalysisContext(securityLevel = SecurityLevel.DEEP)
                )

                _analysisProgress.value = "Consulting the Oracle..."

                val result = performAIAnalysis(request)

                _analysisResult.value = result

                // Add to history
                _analysisHistory.value = listOf(
                    AnalysisHistoryItem(
                        signalName = signal.name,
                        analysisType = _analysisType.value,
                        timestamp = System.currentTimeMillis(),
                        riskLevel = result.threatLevel,
                        summary = result.summary
                    )
                ) + _analysisHistory.value.take(19)

                _analysisProgress.value = "Analysis complete"

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Analysis failed: ${e.message}"
            } finally {
                _isAnalyzing.value = false
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
            val config = settingsStore.aiConnectionConfig.first()
            config.validationError()?.let { throw IllegalArgumentException(it) }
            val apiKey = config.apiKey!!
            val model = config.model
            val prompt = OraclePrompts.buildAnalysisPrompt(request)

            val requestBody = """
            {
                "model": ${json.encodeToString(kotlinx.serialization.serializer(), model)},
                "messages": [
                    {
                        "role": "system",
                        "content": "You are Spectral Oracle, an elite RF signals intelligence AI. Provide detailed, technical analysis of captured RF signals. Always structure your response clearly with sections for: PROTOCOL IDENTIFICATION, VULNERABILITIES, EXPLOITS, THREAT ASSESSMENT, and RECOMMENDATIONS."
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
                .url("${config.provider.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://vesper.flipper.app")
                .addHeader("X-Title", "Vesper Spectral Oracle")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                val response = client.newCall(httpRequest).awaitProviderResponse()
                val responseBody = response.body
                    ?: throw Exception("Empty response from AI")

                if (!response.isSuccessful) {
                    throw Exception("API error: ${response.code}")
                }

                parseAIResponse(responseBody, request)
            } catch (_: UnknownHostException) {
                throw Exception(
                    "Cannot resolve ${config.provider.displayName} (DNS/network issue). Verify internet access, disable broken Private DNS/VPN, then retry."
                )
            }
        }

    private fun parseAIResponse(responseBody: String, request: SignalIntelRequest): OracleAnalysisResult {
        // Extract the AI message content
        val contentRegex = """"content"\s*:\s*"(.*?)(?<!\\)"""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = contentRegex.find(responseBody)
        val aiResponse = match?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: responseBody

        // Parse structured sections from AI response
        val sections = parseResponseSections(aiResponse)

        return OracleAnalysisResult(
            signalName = request.signalData.metadata["protocol"] ?: "Unknown Signal",
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
        // Get first paragraph or first 200 chars
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

        // Look for severity indicators
        val severityPatterns = mapOf(
            VulnerabilitySeverity.CRITICAL to listOf("critical", "severe", "dangerous"),
            VulnerabilitySeverity.HIGH to listOf("high", "significant", "major"),
            VulnerabilitySeverity.MEDIUM to listOf("medium", "moderate", "notable"),
            VulnerabilitySeverity.LOW to listOf("low", "minor", "minimal"),
            VulnerabilitySeverity.INFO to listOf("info", "informational", "note")
        )

        // Parse bullet points or numbered items
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

                vulns.add(VulnItem(
                    name = item.take(60).let { if (it.length >= 60) "$it..." else it },
                    description = item,
                    severity = severity
                ))
            }
        }

        // If no bullet points found, look for severity labels in text
        if (vulns.isEmpty()) {
            severityPatterns.forEach { (severity, keywords) ->
                keywords.forEach { keyword ->
                    val pattern = """$keyword[:\s]+([^.\n]+)""".toRegex(RegexOption.IGNORE_CASE)
                    pattern.findAll(section).forEach { match ->
                        val desc = match.groupValues[1].trim()
                        if (desc.length > 5) {
                            vulns.add(VulnItem(
                                name = desc.take(50),
                                description = desc,
                                severity = severity
                            ))
                        }
                    }
                }
            }
        }

        return vulns.distinctBy { it.name }.take(10)
    }

    private fun parseExploits(section: String): List<ExploitItem> {
        val exploits = mutableListOf<ExploitItem>()

        // Look for exploit descriptions
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

                // Extract surrounding context
                val contextPattern = """.{0,100}${pattern.pattern}.{0,200}""".toRegex(RegexOption.IGNORE_CASE)
                val context = contextPattern.find(section)?.value ?: ""

                exploits.add(ExploitItem(
                    name = name,
                    description = context.trim(),
                    difficulty = if (context.lowercase().contains("trivial") || context.lowercase().contains("easy"))
                        "Easy" else if (context.lowercase().contains("complex") || context.lowercase().contains("difficult"))
                        "Hard" else "Moderate"
                ))
            }
        }

        return exploits.distinctBy { it.name }.take(5)
    }

    private fun determineThreatLevel(response: String): OracleRiskLevel {
        val lower = response.lowercase()
        return when {
            lower.contains("critical") && (lower.contains("vuln") || lower.contains("risk")) -> OracleRiskLevel.CRITICAL
            lower.contains("high risk") || lower.contains("highly vulnerable") -> OracleRiskLevel.HIGH
            lower.contains("elevated") || lower.contains("significant risk") -> OracleRiskLevel.ELEVATED
            lower.contains("moderate") && lower.contains("risk") -> OracleRiskLevel.MODERATE
            lower.contains("low risk") || lower.contains("minimal") -> OracleRiskLevel.LOW
            lower.contains("secure") || lower.contains("protected") -> OracleRiskLevel.MINIMAL
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
        return "Analysis not available"
    }

    private fun parseRecommendations(section: String): List<String> {
        val recs = mutableListOf<String>()

        // Parse bullet points
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

    fun clearError() {
        _error.value = null
    }

    fun clearResult() {
        _analysisResult.value = null
    }
}

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

data class AnalysisHistoryItem(
    val signalName: String,
    val analysisType: AnalysisType,
    val timestamp: Long,
    val riskLevel: OracleRiskLevel,
    val summary: String
)
