package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.model.AuditEntry
import com.vesper.flipper.domain.model.AuditSummary
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.CommandResultData
import com.vesper.flipper.domain.model.ExecuteCommand
import com.vesper.flipper.domain.model.PendingApproval
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.domain.service.AuditService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuditState(
    val entries: List<AuditEntry> = emptyList(),
    val timeline: List<CommandTimelineItem> = emptyList(),
    val summary: AuditSummary? = null,
    val isLoading: Boolean = true,
    val selectedEntry: AuditEntry? = null,
    val filterType: AuditFilterType = AuditFilterType.ALL,
    val searchQuery: String = "",
    val replayingTraceId: String? = null,
    val pendingReplayApproval: PendingApproval? = null,
    val replayMessage: String? = null
)

enum class AuditFilterType {
    ALL,
    WRITES,
    HIGH_RISK,
    DENIED
}

enum class CommandTraceStatus {
    RECEIVED,
    WAITING_APPROVAL,
    EXECUTED,
    FAILED,
    BLOCKED,
    DENIED,
    TIMED_OUT
}

data class CommandTimelineItem(
    val traceId: String,
    val sessionId: String,
    val command: ExecuteCommand,
    val status: CommandTraceStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val durationMs: Long,
    val summary: String,
    val error: String? = null
)

@HiltViewModel
class AuditViewModel @Inject constructor(
    private val auditService: AuditService,
    private val commandExecutor: CommandExecutor
) : ViewModel() {

    private val _state = MutableStateFlow(AuditState())
    val state: StateFlow<AuditState> = _state.asStateFlow()

    private var replayInFlight = false
    private var allEntries: List<AuditEntry> = emptyList()

    init {
        observeAuditLog()
    }

    private fun observeAuditLog() {
        viewModelScope.launch {
            auditService.recentEntries.collect { entries ->
                allEntries = entries
                refreshState(entries = entries, preserveReplay = true)
            }
        }
    }

    private fun refreshState(entries: List<AuditEntry>, preserveReplay: Boolean) {
        val current = _state.value
        val filteredEntries = filterEntries(entries, current.filterType, current.searchQuery)
        val timeline = buildTimeline(entries)
        _state.update {
            it.copy(
                entries = filteredEntries,
                timeline = timeline,
                isLoading = false,
                replayingTraceId = if (preserveReplay) it.replayingTraceId else null,
                pendingReplayApproval = if (preserveReplay) it.pendingReplayApproval else null,
                replayMessage = if (preserveReplay) it.replayMessage else null
            )
        }
    }

    fun setFilter(filterType: AuditFilterType) {
        _state.update { it.copy(filterType = filterType) }
        refreshState(entries = allEntries, preserveReplay = true)
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        refreshState(entries = allEntries, preserveReplay = true)
    }

    fun clearFilters() {
        _state.update { it.copy(filterType = AuditFilterType.ALL, searchQuery = "") }
        refreshState(entries = allEntries, preserveReplay = true)
    }

    private fun filterEntries(
        entries: List<AuditEntry>,
        filterType: AuditFilterType,
        query: String
    ): List<AuditEntry> {
        val byType = when (filterType) {
            AuditFilterType.ALL -> entries
            AuditFilterType.WRITES -> entries.filter { entry ->
                entry.command?.action?.let { action -> action in WRITE_ACTIONS } == true
            }
            AuditFilterType.HIGH_RISK -> entries.filter {
                it.riskLevel == RiskLevel.HIGH || it.riskLevel == RiskLevel.BLOCKED
            }
            AuditFilterType.DENIED -> entries.filter {
                it.actionType.name == "APPROVAL_DENIED" || it.actionType.name == "COMMAND_BLOCKED"
            }
        }
        val trimmed = query.trim()
        val byQuery = if (trimmed.isEmpty()) {
            byType
        } else {
            byType.filter { entry ->
                pathFor(entry)?.contains(trimmed, ignoreCase = true) == true
            }
        }
        return byQuery.sortedByDescending { it.timestamp }
    }

    private fun pathFor(entry: AuditEntry): String? =
        entry.command?.args?.path
            ?: entry.command?.args?.destinationPath
            ?: entry.metadata["path"]
            ?: entry.metadata["destination_path"]

    private fun buildTimeline(entries: List<AuditEntry>): List<CommandTimelineItem> {
        val commandLikeEntries = entries
            .filter {
                it.command != null ||
                        it.actionType.name.contains("COMMAND") ||
                        it.actionType.name.contains("APPROVAL")
            }
            .sortedBy { it.timestamp }

        val grouped = LinkedHashMap<String, MutableList<AuditEntry>>()
        commandLikeEntries.forEach { entry ->
            val key = entry.metadata["trace_id"]
                ?: entry.metadata["approval_id"]?.let { "approval:$it" }
                ?: entry.command?.let { "legacy:" + entry.id }
                ?: return@forEach
            grouped.getOrPut(key) { mutableListOf() }.add(entry)
        }

        return grouped.mapNotNull { (traceId, traceEntries) ->
            toTimelineItem(traceId, traceEntries)
        }.sortedByDescending { it.startedAt }
    }

    private fun toTimelineItem(
        traceId: String,
        traceEntries: List<AuditEntry>
    ): CommandTimelineItem? {
        val command = traceEntries.firstOrNull { it.command != null }?.command ?: return null
        val sorted = traceEntries.sortedBy { it.timestamp }
        val first = sorted.first()
        val last = sorted.last()

        val status = when {
            sorted.any { it.actionType.name == "COMMAND_EXECUTED" } -> CommandTraceStatus.EXECUTED
            sorted.any { it.actionType.name == "COMMAND_FAILED" } -> CommandTraceStatus.FAILED
            sorted.any { it.actionType.name == "COMMAND_BLOCKED" } -> CommandTraceStatus.BLOCKED
            sorted.any { it.actionType.name == "APPROVAL_DENIED" } -> CommandTraceStatus.DENIED
            sorted.any { it.actionType.name == "APPROVAL_TIMEOUT" } -> CommandTraceStatus.TIMED_OUT
            sorted.any {
                it.actionType.name == "APPROVAL_REQUESTED" || it.actionType.name == "APPROVAL_GRANTED"
            } -> CommandTraceStatus.WAITING_APPROVAL
            else -> CommandTraceStatus.RECEIVED
        }

        val finalResult = sorted.lastOrNull { it.result != null }?.result
        val durationMs = finalResult?.executionTimeMs
            ?: (last.timestamp - first.timestamp).coerceAtLeast(0L)
        val summary = finalResult?.let { summarizeResult(it.data, it.error) }
            ?: status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        val error = finalResult?.error
            ?: sorted.lastOrNull { it.actionType.name == "COMMAND_FAILED" }?.metadata?.get("reason")

        return CommandTimelineItem(
            traceId = traceId,
            sessionId = first.sessionId,
            command = command,
            status = status,
            startedAt = first.timestamp,
            finishedAt = last.timestamp,
            durationMs = durationMs,
            summary = summary,
            error = error
        )
    }

    private fun summarizeResult(data: CommandResultData?, error: String?): String {
        if (!error.isNullOrBlank()) return error.take(180)
        return when {
            !data?.message.isNullOrBlank() -> data?.message.orEmpty().take(180)
            !data?.content.isNullOrBlank() -> data?.content.orEmpty().lineSequence().firstOrNull().orEmpty().take(180)
            data?.bytesWritten != null -> "Wrote ${data?.bytesWritten} bytes"
            data?.entries != null -> "Listed ${data?.entries?.size ?: 0} entries"
            data?.deviceInfo != null -> "Fetched device info"
            data?.storageInfo != null -> "Fetched storage info"
            else -> "Command completed"
        }
    }

    fun replayTrace(traceId: String) {
        if (replayInFlight) return
        val item = _state.value.timeline.firstOrNull { it.traceId == traceId } ?: return
        replayInFlight = true
        _state.update {
            it.copy(
                replayingTraceId = traceId,
                replayMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val sessionId = auditService.getCurrentSessionId() ?: item.sessionId
                val result = commandExecutor.execute(item.command, sessionId)
                val approvalId = result.pendingApprovalId
                if (result.requiresConfirmation && !approvalId.isNullOrBlank()) {
                    val pending = commandExecutor.getPendingApproval(approvalId)
                    _state.update {
                        it.copy(
                            pendingReplayApproval = pending,
                            replayMessage = if (pending != null) {
                                "Replay requires approval before execution."
                            } else {
                                "Replay approval request expired."
                            }
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            replayMessage = if (result.success) {
                                "Replay succeeded: ${result.data?.message ?: item.command.action.name.lowercase()}"
                            } else {
                                "Replay failed: ${result.error ?: "Unknown error"}"
                            }
                        )
                    }
                }
            } finally {
                replayInFlight = false
                _state.update { it.copy(replayingTraceId = null) }
            }
        }
    }

    fun approveReplay() {
        val pending = _state.value.pendingReplayApproval ?: return
        runReplayApproval(pending.id, approved = true)
    }

    fun rejectReplay() {
        val pending = _state.value.pendingReplayApproval ?: return
        runReplayApproval(pending.id, approved = false)
    }

    private fun runReplayApproval(approvalId: String, approved: Boolean) {
        if (replayInFlight) return
        replayInFlight = true
        _state.update { it.copy(replayingTraceId = approvalId) }
        viewModelScope.launch {
            try {
                val sessionId = auditService.getCurrentSessionId() ?: "audit-replay"
                val result = if (approved) {
                    commandExecutor.approve(approvalId, sessionId)
                } else {
                    commandExecutor.reject(approvalId, sessionId)
                }
                _state.update {
                    it.copy(
                        pendingReplayApproval = null,
                        replayMessage = if (result.success) {
                            "Replay command executed."
                        } else {
                            result.error ?: "Replay approval command failed."
                        }
                    )
                }
            } finally {
                replayInFlight = false
                _state.update { it.copy(replayingTraceId = null) }
            }
        }
    }

    fun clearReplayMessage() {
        _state.update { it.copy(replayMessage = null) }
    }

    fun selectEntry(entry: AuditEntry) {
        _state.update { it.copy(selectedEntry = entry) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedEntry = null) }
    }

    fun loadSummary(sessionId: String) {
        viewModelScope.launch {
            val summary = auditService.getSessionSummary(sessionId)
            _state.update { it.copy(summary = summary) }
        }
    }

    fun clearAuditLog() {
        viewModelScope.launch {
            auditService.clearAll()
            allEntries = emptyList()
            _state.update {
                it.copy(
                    entries = emptyList(),
                    timeline = emptyList(),
                    pendingReplayApproval = null,
                    replayMessage = null,
                    isLoading = false
                )
            }
        }
    }

    fun clearOldEntries(days: Int) {
        viewModelScope.launch {
            auditService.clearOlderThan(days)
        }
    }

    private companion object {
        val WRITE_ACTIONS = setOf(
            CommandAction.WRITE_FILE,
            CommandAction.CREATE_DIRECTORY,
            CommandAction.DELETE,
            CommandAction.MOVE,
            CommandAction.RENAME,
            CommandAction.COPY,
            CommandAction.PUSH_ARTIFACT
        )
    }
}
