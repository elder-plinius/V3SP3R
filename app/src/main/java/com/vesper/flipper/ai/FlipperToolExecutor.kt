package com.vesper.flipper.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.vesper.flipper.ble.BleServiceManager
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.PayloadType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FlipperToolExecutor - Function Calling for Payload Execution
 *
 * Provides the execution layer for AI-generated payloads:
 * - Save files to Flipper
 * - Execute Flipper CLI commands
 * - Trigger web searches
 * - Initiate Flipper functions (IR, BLE, Sub-GHz)
 */
@Singleton
class FlipperToolExecutor @Inject constructor(
    private val flipperFileSystem: FlipperFileSystem,
    private val bleServiceManager: BleServiceManager
) {

    // ============================================================
    // FILE OPERATIONS
    // ============================================================

    /**
     * Save a payload file to Flipper
     */
    suspend fun savePayload(
        path: String,
        content: String,
        createDirectories: Boolean = true
    ): ToolResult = withContext(Dispatchers.IO) {
        try {
            // Extract directory path and create if needed
            if (createDirectories) {
                val dirPath = path.substringBeforeLast("/")
                if (dirPath.isNotEmpty() && dirPath != path) {
                    flipperFileSystem.createDirectory(dirPath)
                }
            }

            // Write the file
            val result = flipperFileSystem.writeFile(path, content)
            if (result.isSuccess) {
                ToolResult.Success(
                    message = "Saved to $path",
                    data = mapOf(
                        "path" to path,
                        "size" to content.toByteArray().size
                    )
                )
            } else {
                ToolResult.Error("Failed to save: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            ToolResult.Error("Save error: ${e.message}")
        }
    }

    /**
     * Save BadUSB script to appropriate location
     */
    suspend fun saveBadUsbScript(
        filename: String,
        script: String
    ): ToolResult {
        val sanitizedName = filename
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(50)
        val path = "/ext/badusb/$sanitizedName.txt"
        return savePayload(path, script)
    }

    /**
     * Save Evil Portal to appropriate location
     */
    suspend fun saveEvilPortal(
        portalName: String,
        html: String
    ): ToolResult = withContext(Dispatchers.IO) {
        val sanitizedName = portalName
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(50)
        val dir = "/ext/apps_data/evil_portal/portals/$sanitizedName"
        val indexPath = "$dir/index.html"

        try {
            // Create portal directory
            flipperFileSystem.createDirectory(dir)

            // Write index.html
            val result = flipperFileSystem.writeFile(indexPath, html)
            if (result.isSuccess) {
                ToolResult.Success(
                    message = "Portal deployed to $dir",
                    data = mapOf(
                        "directory" to dir,
                        "indexPath" to indexPath,
                        "size" to html.toByteArray().size
                    )
                )
            } else {
                ToolResult.Error("Failed to save portal: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            ToolResult.Error("Portal save error: ${e.message}")
        }
    }

    // ============================================================
    // FLIPPER COMMAND EXECUTION
    // ============================================================

    /**
     * Execute a CLI command on the Flipper
     */
    suspend fun executeFlipperCommand(command: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            bleServiceManager.awaitConnectedService()
                ?: return@withContext ToolResult.Error(
                    "Flipper not connected. Open Device, connect to a Flipper, then retry."
                )
            val result = flipperFileSystem.executeCli(command)

            if (result.isSuccess) {
                val outputPreview = result.getOrNull()
                    ?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.take(160)
                    .orEmpty()
                ToolResult.Success(
                    message = if (outputPreview.isBlank()) {
                        "Command dispatched: $command"
                    } else {
                        "Command output: $outputPreview"
                    },
                    data = mapOf(
                        "command" to command,
                        "output" to (result.getOrNull().orEmpty())
                    )
                )
            } else {
                ToolResult.Error(
                    result.exceptionOrNull()?.message
                        ?: "Command failed: $command"
                )
            }
        } catch (e: Exception) {
            ToolResult.Error("Command error: ${e.message}")
        }
    }

    /**
     * Run a BadUSB script on the Flipper
     */
    suspend fun runBadUsb(scriptPath: String): ToolResult {
        return executeFlipperCommand("badusb $scriptPath")
    }

    /**
     * Trigger IR transmission
     */
    suspend fun transmitIr(irFilePath: String, signalName: String? = null): ToolResult {
        val command = if (signalName != null) {
            "ir tx $irFilePath $signalName"
        } else {
            "ir tx $irFilePath"
        }
        return executeFlipperCommand(command)
    }

    /**
     * Trigger Sub-GHz transmission
     */
    suspend fun transmitSubGhz(subFilePath: String): ToolResult {
        return executeFlipperCommand("subghz tx $subFilePath")
    }

    /**
     * Start BLE beacon spam
     */
    suspend fun startBleSpam(type: BleSpamType): ToolResult {
        val command = when (type) {
            BleSpamType.APPLE_AIRPODS -> "ble_spam apple_airpods"
            BleSpamType.APPLE_AIRTAG -> "ble_spam apple_airtag"
            BleSpamType.SAMSUNG_BUDS -> "ble_spam samsung_buds"
            BleSpamType.GOOGLE_FAST_PAIR -> "ble_spam google_fastpair"
            BleSpamType.DEVICE_FLOOD -> "ble_spam flood"
            BleSpamType.STOP -> "ble_spam stop"
        }
        return executeFlipperCommand(command)
    }

    /**
     * Get Flipper device info
     */
    suspend fun getDeviceInfo(): ToolResult = withContext(Dispatchers.IO) {
        try {
            val info = flipperFileSystem.getDeviceInfo()
            if (info.isSuccess) {
                val deviceInfo = info.getOrNull()
                ToolResult.Success(
                    message = "Device: ${deviceInfo?.name ?: "Unknown"}",
                    data = mapOf(
                        "name" to (deviceInfo?.name ?: ""),
                        "firmware" to (deviceInfo?.firmwareVersion ?: ""),
                        "hardware" to (deviceInfo?.hardwareVersion ?: ""),
                        "battery" to (deviceInfo?.batteryLevel ?: 0)
                    )
                )
            } else {
                ToolResult.Error("Failed to get device info")
            }
        } catch (e: Exception) {
            ToolResult.Error("Device info error: ${e.message}")
        }
    }

    /**
     * List directory contents
     */
    suspend fun listDirectory(path: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val result = flipperFileSystem.listDirectory(path)
            if (result.isSuccess) {
                val entries = result.getOrNull() ?: emptyList()
                ToolResult.Success(
                    message = "Listed ${entries.size} items in $path",
                    data = mapOf(
                        "path" to path,
                        "entries" to entries
                    )
                )
            } else {
                ToolResult.Error("Failed to list: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            ToolResult.Error("List error: ${e.message}")
        }
    }

    // ============================================================
    // WEB SEARCH (Returns intent for UI to handle)
    // ============================================================

    /**
     * Generate web search intent
     */
    fun createWebSearchIntent(query: String): Intent {
        val encodedQuery = Uri.encode(query)
        return Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$encodedQuery".toUri())
    }

    /**
     * Generate search suggestions based on payload type
     */
    fun getSearchSuggestions(payloadType: PayloadType, context: String): List<SearchSuggestion> {
        return when (payloadType) {
            PayloadType.BAD_USB -> listOf(
                SearchSuggestion(
                    label = "DuckyScript commands reference",
                    query = "duckyscript commands reference flipper zero"
                ),
                SearchSuggestion(
                    label = "PowerShell one-liners",
                    query = "powershell one liner commands pentest"
                ),
                SearchSuggestion(
                    label = "BadUSB payloads GitHub",
                    query = "badusb payloads github flipper zero"
                ),
                SearchSuggestion(
                    label = "UAC bypass techniques",
                    query = "windows uac bypass techniques 2024"
                )
            )
            PayloadType.SUB_GHZ -> listOf(
                SearchSuggestion(
                    label = "Sub-GHz protocols",
                    query = "flipper zero sub-ghz protocols supported"
                ),
                SearchSuggestion(
                    label = "Sub-GHz frequency database",
                    query = "sub-ghz frequency database by country"
                ),
                SearchSuggestion(
                    label = "Sub-GHz file format",
                    query = "flipper zero .sub file format specification"
                ),
                SearchSuggestion(
                    label = "Sub-GHz captures GitHub",
                    query = "flipper zero sub-ghz captures github"
                )
            )
            PayloadType.INFRARED -> listOf(
                SearchSuggestion(
                    label = "IR remote database",
                    query = "flipper zero infrared remote database IRDB"
                ),
                SearchSuggestion(
                    label = "IR protocol reference",
                    query = "infrared NEC RC5 RC6 protocol reference"
                ),
                SearchSuggestion(
                    label = "Universal remotes",
                    query = "flipper zero universal remote $context"
                ),
                SearchSuggestion(
                    label = "IR file format",
                    query = "flipper zero .ir file format specification"
                )
            )
            PayloadType.NFC -> listOf(
                SearchSuggestion(
                    label = "NFC tag types",
                    query = "flipper zero NFC tag types NTAG MIFARE"
                ),
                SearchSuggestion(
                    label = "Amiibo files",
                    query = "flipper zero amiibo NFC files github"
                ),
                SearchSuggestion(
                    label = "NFC file format",
                    query = "flipper zero .nfc file format specification"
                ),
                SearchSuggestion(
                    label = "NFC tools and apps",
                    query = "flipper zero NFC tools apps fap"
                )
            )
            PayloadType.RFID -> listOf(
                SearchSuggestion(
                    label = "RFID protocols",
                    query = "flipper zero LFRFID protocols EM4100 HID"
                ),
                SearchSuggestion(
                    label = "RFID file format",
                    query = "flipper zero .rfid file format"
                ),
                SearchSuggestion(
                    label = "RFID cloning guide",
                    query = "flipper zero RFID read clone guide"
                ),
                SearchSuggestion(
                    label = "125kHz RFID cards",
                    query = "125khz RFID card types and protocols"
                )
            )
            PayloadType.IBUTTON -> listOf(
                SearchSuggestion(
                    label = "iButton protocols",
                    query = "flipper zero iButton DS1990 Cyfral Metakom"
                ),
                SearchSuggestion(
                    label = "iButton file format",
                    query = "flipper zero .ibtn file format"
                ),
                SearchSuggestion(
                    label = "iButton guide",
                    query = "flipper zero iButton read emulate guide"
                ),
                SearchSuggestion(
                    label = "1-Wire protocol",
                    query = "Dallas 1-Wire protocol iButton reference"
                )
            )
            PayloadType.UNKNOWN -> listOf(
                SearchSuggestion(
                    label = "Flipper Zero file formats",
                    query = "flipper zero supported file formats"
                ),
                SearchSuggestion(
                    label = "Flipper Zero payloads",
                    query = "flipper zero payloads github $context"
                ),
                SearchSuggestion(
                    label = "Flipper Zero guides",
                    query = "flipper zero beginner guide tutorials"
                ),
                SearchSuggestion(
                    label = "Flipper community",
                    query = "flipper zero community resources tools"
                )
            )
        }
    }

    // ============================================================
    // BATCH OPERATIONS
    // ============================================================

    /**
     * Execute multiple tools in sequence
     */
    suspend fun executeBatch(actions: List<ToolAction>): List<ToolResult> {
        return actions.map { action ->
            when (action) {
                is ToolAction.SaveFile -> savePayload(action.path, action.content)
                is ToolAction.ExecuteCommand -> executeFlipperCommand(action.command)
                is ToolAction.SaveBadUsb -> saveBadUsbScript(action.filename, action.script)
                is ToolAction.SavePortal -> saveEvilPortal(action.portalName, action.html)
                is ToolAction.RunBadUsb -> runBadUsb(action.scriptPath)
                is ToolAction.TransmitIr -> transmitIr(action.irPath, action.signalName)
                is ToolAction.TransmitSubGhz -> transmitSubGhz(action.subPath)
                is ToolAction.StartBleSpam -> startBleSpam(action.type)
            }
        }
    }
}

// ============================================================
// DATA CLASSES
// ============================================================

sealed class ToolResult {
    data class Success(
        val message: String,
        val data: Map<String, Any> = emptyMap()
    ) : ToolResult()

    data class Error(val message: String) : ToolResult()

    val isSuccess: Boolean get() = this is Success
}

sealed class ToolAction {
    data class SaveFile(val path: String, val content: String) : ToolAction()
    data class ExecuteCommand(val command: String) : ToolAction()
    data class SaveBadUsb(val filename: String, val script: String) : ToolAction()
    data class SavePortal(val portalName: String, val html: String) : ToolAction()
    data class RunBadUsb(val scriptPath: String) : ToolAction()
    data class TransmitIr(val irPath: String, val signalName: String? = null) : ToolAction()
    data class TransmitSubGhz(val subPath: String) : ToolAction()
    data class StartBleSpam(val type: BleSpamType) : ToolAction()
}

enum class BleSpamType {
    APPLE_AIRPODS,
    APPLE_AIRTAG,
    SAMSUNG_BUDS,
    GOOGLE_FAST_PAIR,
    DEVICE_FLOOD,
    STOP
}

// PayloadType is imported from com.vesper.flipper.domain.model

data class SearchSuggestion(
    val label: String,
    val query: String
)

@Serializable
data class ToolCallRequest(
    val tool: String,
    val parameters: Map<String, String>
)

@Serializable
data class ToolCallResponse(
    val success: Boolean,
    val result: String? = null,
    val error: String? = null,
    val data: Map<String, String> = emptyMap()
)
