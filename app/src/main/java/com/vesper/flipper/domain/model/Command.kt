package com.vesper.flipper.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The single command interface for AI agent interaction.
 * All Flipper operations go through this unified structure.
 */
@Serializable
data class ExecuteCommand(
    val action: CommandAction,
    val args: CommandArgs,
    val justification: String,
    @SerialName("expected_effect")
    val expectedEffect: String
)

@Serializable
enum class CommandAction {
    @SerialName("list_directory")
    LIST_DIRECTORY,

    @SerialName("read_file")
    READ_FILE,

    @SerialName("write_file")
    WRITE_FILE,

    @SerialName("create_directory")
    CREATE_DIRECTORY,

    @SerialName("delete")
    DELETE,

    @SerialName("move")
    MOVE,

    @SerialName("rename")
    RENAME,

    @SerialName("copy")
    COPY,

    @SerialName("get_device_info")
    GET_DEVICE_INFO,

    @SerialName("get_storage_info")
    GET_STORAGE_INFO,

    @SerialName("search_faphub")
    SEARCH_FAPHUB,

    @SerialName("install_faphub_app")
    INSTALL_FAPHUB_APP,

    @SerialName("push_artifact")
    PUSH_ARTIFACT,

    @SerialName("execute_cli")
    EXECUTE_CLI,

    @SerialName("forge_payload")
    FORGE_PAYLOAD,

    @SerialName("search_resources")
    SEARCH_RESOURCES,

    @SerialName("list_vault")
    LIST_VAULT,

    @SerialName("run_runbook")
    RUN_RUNBOOK
}

@Serializable
data class CommandArgs(
    val command: String? = null,
    val path: String? = null,
    @SerialName("destination_path")
    val destinationPath: String? = null,
    val content: String? = null,
    @SerialName("new_name")
    val newName: String? = null,
    val recursive: Boolean = false,
    @SerialName("artifact_type")
    val artifactType: String? = null,
    @SerialName("artifact_data")
    val artifactData: String? = null,
    val prompt: String? = null,
    @SerialName("resource_type")
    val resourceType: String? = null,
    @SerialName("runbook_id")
    val runbookId: String? = null,
    @SerialName("payload_type")
    val payloadType: String? = null,
    val filter: String? = null
)

/**
 * Result returned after command execution
 */
@Serializable
data class CommandResult(
    val success: Boolean,
    val action: CommandAction,
    val data: CommandResultData? = null,
    val error: String? = null,
    @SerialName("execution_time_ms")
    val executionTimeMs: Long = 0,
    @SerialName("requires_confirmation")
    val requiresConfirmation: Boolean = false,
    @SerialName("pending_approval_id")
    val pendingApprovalId: String? = null
)

@Serializable
data class CommandResultData(
    val entries: List<FileEntry>? = null,
    val content: String? = null,
    @SerialName("bytes_written")
    val bytesWritten: Long? = null,
    @SerialName("device_info")
    val deviceInfo: DeviceInfo? = null,
    @SerialName("storage_info")
    val storageInfo: StorageInfo? = null,
    val diff: FileDiff? = null,
    val message: String? = null
)

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    @SerialName("is_directory")
    val isDirectory: Boolean,
    val size: Long = 0,
    @SerialName("modified_timestamp")
    val modifiedTimestamp: Long? = null
)

@Serializable
data class DeviceInfo(
    val name: String,
    @SerialName("firmware_version")
    val firmwareVersion: String,
    @SerialName("hardware_version")
    val hardwareVersion: String,
    @SerialName("battery_level")
    val batteryLevel: Int,
    @SerialName("is_charging")
    val isCharging: Boolean
)

@Serializable
data class StorageInfo(
    @SerialName("internal_total")
    val internalTotal: Long,
    @SerialName("internal_free")
    val internalFree: Long,
    @SerialName("external_total")
    val externalTotal: Long? = null,
    @SerialName("external_free")
    val externalFree: Long? = null,
    @SerialName("has_sd_card")
    val hasSdCard: Boolean
)

@Serializable
data class FileDiff(
    @SerialName("original_content")
    val originalContent: String?,
    @SerialName("new_content")
    val newContent: String,
    @SerialName("lines_added")
    val linesAdded: Int,
    @SerialName("lines_removed")
    val linesRemoved: Int,
    @SerialName("unified_diff")
    val unifiedDiff: String
)
