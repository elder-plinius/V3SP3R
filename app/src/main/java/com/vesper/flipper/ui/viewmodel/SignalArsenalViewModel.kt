package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.BleServiceManager
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.mode.OperationModeManager
import com.vesper.flipper.domain.model.SignalCapture
import com.vesper.flipper.domain.model.SignalParser
import com.vesper.flipper.domain.model.SignalType
import com.vesper.flipper.domain.model.WaveformData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** A capture plus the file metadata the list and detail panes render. */
data class ArsenalCapture(
    val signal: SignalCapture,
    val modifiedAt: Long? = null,
    val durationMs: Float? = null,
    val sampleCount: Int? = null
)

/** Which action the error banner should retry. */
enum class ArsenalErrorSource {
    LOAD,
    REPLAY,
    DELETE
}

sealed interface ReplayState {
    data object Idle : ReplayState
    data object Sending : ReplayState
    data class Sent(val message: String) : ReplayState
}

data class SignalArsenalUiState(
    val selectedType: SignalType = SignalType.SUBGHZ,
    val query: String = "",
    val allCaptures: List<ArsenalCapture> = emptyList(),
    val captures: List<ArsenalCapture> = emptyList(),
    val counts: Map<SignalType, Int> = emptyMap(),
    val selected: ArsenalCapture? = null,
    val waveform: WaveformData? = null,
    val waveformLoading: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isDeviceConnected: Boolean = false,
    val errorMessage: String? = null,
    val errorSource: ArsenalErrorSource = ArsenalErrorSource.LOAD,
    val replay: ReplayState = ReplayState.Idle,
    val notice: String? = null
)

/**
 * Master-detail state for the signal library.
 *
 * Reads one capture folder at a time over BLE, keeps the current selection
 * stable across refreshes, and guards replay with the connection state and
 * the active operation mode.
 */
@HiltViewModel
class SignalArsenalViewModel @Inject constructor(
    private val fileSystem: FlipperFileSystem,
    private val bleServiceManager: BleServiceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignalArsenalUiState())
    val uiState: StateFlow<SignalArsenalUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var waveformJob: Job? = null

    init {
        observeConnection()
        refresh()
    }

    fun selectType(type: SignalType) {
        val state = _uiState.value
        if (type == state.selectedType && state.allCaptures.isNotEmpty()) return
        _uiState.update {
            it.copy(
                selected = null,
                waveform = null,
                waveformLoading = false,
                replay = ReplayState.Idle
            )
        }
        loadCaptures(type, showSkeleton = true)
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query, captures = filterCaptures(it.allCaptures, query)) }
    }

    fun refresh() {
        loadCounts()
        loadCaptures(_uiState.value.selectedType, showSkeleton = _uiState.value.allCaptures.isEmpty())
    }

    fun selectCapture(capture: ArsenalCapture) {
        _uiState.update {
            it.copy(
                selected = capture,
                waveform = null,
                waveformLoading = capture.sampleCount != null,
                replay = ReplayState.Idle,
                errorMessage = null
            )
        }
        waveformJob?.cancel()
        waveformJob = viewModelScope.launch {
            val waveform = withContext(Dispatchers.Default) {
                when (val signal = capture.signal) {
                    is SignalCapture.SubGhz -> signal.rawData?.let { SignalParser.rawToWaveform(it) }
                    is SignalCapture.Infrared -> signal.rawData?.let { SignalParser.rawToWaveform(it) }
                    else -> null
                }
            }
            _uiState.update { state ->
                if (state.selected?.signal?.path == capture.signal.path) {
                    state.copy(waveform = waveform, waveformLoading = false)
                } else {
                    state
                }
            }
        }
    }

    fun clearSelection() {
        waveformJob?.cancel()
        _uiState.update {
            it.copy(selected = null, waveform = null, waveformLoading = false, replay = ReplayState.Idle)
        }
    }

    fun replaySelected() {
        val state = _uiState.value
        val capture = state.selected ?: return
        if (state.replay is ReplayState.Sending) return
        val signal = capture.signal

        val action = replayAction(signal)
        if (!OperationModeManager.isActionAllowed(action)) {
            val mode = OperationModeManager.currentMode.value.displayName
            _uiState.update {
                it.copy(
                    errorSource = ArsenalErrorSource.REPLAY,
                    errorMessage = "Replay is blocked while $mode is the active mode. " +
                        "Switch to Standard in Settings, then retry."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(replay = ReplayState.Sending, errorMessage = null) }

            if (bleServiceManager.awaitConnectedService() == null) {
                _uiState.update {
                    it.copy(
                        replay = ReplayState.Idle,
                        errorSource = ArsenalErrorSource.REPLAY,
                        errorMessage = "Connect to your Flipper in Device before replaying this capture."
                    )
                }
                return@launch
            }

            val response = withTimeoutOrNull(REPLAY_TIMEOUT_MS) {
                fileSystem.executeCli(replayCommand(signal))
            }
            val failure = when {
                response == null ->
                    "The Flipper did not answer in time. Stop the transmission on the device, then retry."
                response.isFailure ->
                    "Replay failed. Wake the Flipper and keep it in range, then retry."
                else -> null
            }
            _uiState.update { current ->
                if (failure == null) {
                    current.copy(replay = ReplayState.Sent("Replay sent"))
                } else {
                    current.copy(
                        replay = ReplayState.Idle,
                        errorSource = ArsenalErrorSource.REPLAY,
                        errorMessage = failure
                    )
                }
            }
        }
    }

    fun deleteSelected() {
        val capture = _uiState.value.selected ?: return
        if (_uiState.value.replay is ReplayState.Sending) return

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { fileSystem.delete(capture.signal.path) }
            if (result.isSuccess) {
                _uiState.update { state ->
                    val remaining = state.allCaptures.filterNot { it.signal.path == capture.signal.path }
                    state.copy(
                        allCaptures = remaining,
                        captures = filterCaptures(remaining, state.query),
                        selected = null,
                        waveform = null,
                        waveformLoading = false,
                        replay = ReplayState.Idle,
                        errorMessage = null,
                        notice = "Capture deleted from the Flipper."
                    )
                }
                loadCounts()
            } else {
                _uiState.update {
                    it.copy(
                        errorSource = ArsenalErrorSource.DELETE,
                        errorMessage = "Could not delete this capture. " +
                            "Check the Flipper is connected, then retry."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            bleServiceManager.connectionState.collect { state ->
                val connected = state is ConnectionState.Connected
                val wasConnected = _uiState.value.isDeviceConnected
                _uiState.update { it.copy(isDeviceConnected = connected) }
                if (connected && !wasConnected &&
                    _uiState.value.allCaptures.isEmpty() &&
                    !_uiState.value.isLoading
                ) {
                    refresh()
                }
            }
        }
    }

    private fun loadCounts() {
        viewModelScope.launch {
            val counts = SignalType.entries.associateWith { type ->
                fileSystem.listDirectory(type.directory).getOrNull()
                    ?.count { !it.isDirectory && it.name.endsWith(type.extension, ignoreCase = true) }
                    ?: 0
            }
            _uiState.update { it.copy(counts = counts) }
        }
    }

    private fun loadCaptures(type: SignalType, showSkeleton: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedType = type,
                    isLoading = showSkeleton,
                    isRefreshing = !showSkeleton,
                    errorMessage = null
                )
            }

            try {
                val entries = withContext(Dispatchers.IO) { fileSystem.listDirectory(type.directory) }
                if (entries.isFailure) {
                    val connected = _uiState.value.isDeviceConnected
                    _uiState.update { state ->
                        val keepContent = state.allCaptures.isNotEmpty()
                        state.copy(
                            isLoading = false,
                            isRefreshing = false,
                            allCaptures = if (keepContent) state.allCaptures else emptyList(),
                            captures = if (keepContent) state.captures else emptyList(),
                            selected = if (keepContent) state.selected else null,
                            waveform = if (keepContent) state.waveform else null,
                            waveformLoading = false,
                            replay = if (keepContent) state.replay else ReplayState.Idle,
                            errorSource = ArsenalErrorSource.LOAD,
                            errorMessage = if (connected) {
                                "Could not read the captures folder. " +
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
                        .filter { !it.isDirectory && it.name.endsWith(type.extension, ignoreCase = true) }
                        .mapNotNull { entry ->
                            val content = fileSystem.readFile(entry.path).getOrNull()
                                ?: return@mapNotNull null
                            val signal = parseSignal(
                                type = type,
                                content = content,
                                path = entry.path,
                                name = entry.name.removeSuffix(type.extension)
                            ) ?: return@mapNotNull null
                            ArsenalCapture(
                                signal = signal,
                                modifiedAt = entry.modifiedTimestamp,
                                durationMs = signalDurationMs(signal),
                                sampleCount = signalSampleCount(signal)
                            )
                        }
                        .sortedByDescending { it.modifiedAt ?: it.signal.timestamp }
                }

                _uiState.update { state ->
                    val selectedPath = state.selected?.signal?.path
                    val refreshedSelection = if (state.selected == null) {
                        null
                    } else {
                        captures.firstOrNull { it.signal.path == selectedPath }
                    }
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        allCaptures = captures,
                        captures = filterCaptures(captures, state.query),
                        selected = refreshedSelection,
                        waveform = if (refreshedSelection == null) null else state.waveform,
                        waveformLoading = if (refreshedSelection == null) false else state.waveformLoading,
                        replay = if (refreshedSelection == null) ReplayState.Idle else state.replay,
                        errorMessage = null
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorSource = ArsenalErrorSource.LOAD,
                        errorMessage = "Could not read captures from the Flipper. " +
                            "Connect the device, then retry."
                    )
                }
            }
        }
    }

    private fun parseSignal(
        type: SignalType,
        content: String,
        path: String,
        name: String
    ): SignalCapture? = when (type) {
        SignalType.SUBGHZ -> SignalParser.parseSubGhz(content, path, name)
        SignalType.INFRARED -> SignalParser.parseInfrared(content, path, name).firstOrNull()
        SignalType.NFC -> SignalParser.parseNfc(content, path, name)
        SignalType.RFID -> SignalParser.parseRfid(content, path, name)
        SignalType.IBUTTON -> SignalParser.parseIButton(content, path, name)
    }

    private fun signalDurationMs(signal: SignalCapture): Float? {
        val raw = when (signal) {
            is SignalCapture.SubGhz -> signal.rawData
            is SignalCapture.Infrared -> signal.rawData
            else -> null
        } ?: return null
        if (raw.isEmpty()) return null
        return raw.sumOf { kotlin.math.abs(it).toLong() } / 1000f
    }

    private fun signalSampleCount(signal: SignalCapture): Int? = when (signal) {
        is SignalCapture.SubGhz -> signal.rawData?.size
        is SignalCapture.Infrared -> signal.rawData?.size
        else -> null
    }

    private fun filterCaptures(
        captures: List<ArsenalCapture>,
        query: String
    ): List<ArsenalCapture> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return captures
        return captures.filter { capture ->
            val haystack = buildString {
                append(capture.signal.name)
                append(' ')
                append(capture.signal.path)
                append(' ')
                append(searchTerms(capture.signal))
            }
            haystack.contains(trimmed, ignoreCase = true)
        }
    }

    private fun searchTerms(signal: SignalCapture): String = when (signal) {
        is SignalCapture.SubGhz ->
            "${signal.protocol} ${signal.preset} ${signal.keyData.orEmpty()}"
        is SignalCapture.Infrared ->
            "${signal.protocol} ${signal.address} ${signal.command}"
        is SignalCapture.Nfc ->
            "${signal.deviceType} ${signal.uid}"
        is SignalCapture.Rfid ->
            "${signal.protocol} ${signal.keyData}"
        is SignalCapture.IButton ->
            "${signal.protocol} ${signal.keyData}"
    }

    private fun replayAction(signal: SignalCapture): String = when (signal) {
        is SignalCapture.SubGhz -> "subghz_transmit"
        is SignalCapture.Infrared -> "infrared_send"
        is SignalCapture.Nfc -> "nfc_emulate"
        is SignalCapture.Rfid -> "rfid_emulate"
        is SignalCapture.IButton -> "ibutton_emulate"
    }

    private fun replayCommand(signal: SignalCapture): String = when (signal) {
        is SignalCapture.SubGhz -> "subghz tx ${signal.path}"
        is SignalCapture.Infrared ->
            if (signal.name.isNotBlank()) "ir tx ${signal.path} ${signal.name}" else "ir tx ${signal.path}"
        is SignalCapture.Nfc -> "nfc emulate ${signal.path}"
        is SignalCapture.Rfid -> "rfid emulate ${signal.path}"
        is SignalCapture.IButton -> "ibutton emulate ${signal.path}"
    }

    private companion object {
        const val REPLAY_TIMEOUT_MS = 30_000L
    }
}
