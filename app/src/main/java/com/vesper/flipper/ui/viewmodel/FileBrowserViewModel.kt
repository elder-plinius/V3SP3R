package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.BleServiceManager
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.FileEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the preview sheet can render. Reads are capped so a large capture
 * never turns into a single unbounded Text node.
 */
sealed interface FilePreview {
    val truncated: Boolean
    val bytesRead: Int
    val totalBytes: Long

    data class Text(
        val content: String,
        override val truncated: Boolean,
        override val bytesRead: Int,
        override val totalBytes: Long
    ) : FilePreview

    data class Binary(
        val hex: String,
        val detectedType: String,
        override val truncated: Boolean,
        override val bytesRead: Int,
        override val totalBytes: Long
    ) : FilePreview

    /** Larger files are never read over BLE; the sheet explains why. */
    data class TooLarge(
        override val totalBytes: Long,
        val limitBytes: Long
    ) : FilePreview {
        override val truncated: Boolean = true
        override val bytesRead: Int = 0
    }
}

data class FileBrowserState(
    val currentPath: String = FileBrowserViewModel.ROOT_PATH,
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pathHistory: List<String> = listOf(FileBrowserViewModel.ROOT_PATH),
    val selectedFile: FileEntry? = null,
    val isConnected: Boolean = false,
    val hasLoaded: Boolean = false,
    val preview: FilePreview? = null,
    val isPreviewLoading: Boolean = false,
    val previewError: String? = null,
    val isActionInProgress: Boolean = false,
    val actionError: String? = null,
    val actionSucceeded: Boolean = false
)

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileSystem: FlipperFileSystem,
    private val bleServiceManager: BleServiceManager
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var wasConnected = false
            bleServiceManager.connectionState.collect { connection ->
                val connected = connection is ConnectionState.Connected
                val justConnected = connected && !wasConnected
                wasConnected = connected
                _state.update { current ->
                    if (!connected) {
                        current.copy(
                            isConnected = false,
                            selectedFile = null,
                            preview = null,
                            previewError = null,
                            isPreviewLoading = false
                        )
                    } else {
                        current.copy(isConnected = true)
                    }
                }
                if (justConnected) {
                    loadDirectory(_state.value.currentPath)
                }
            }
        }
        loadDirectory(FileBrowserViewModel.ROOT_PATH)
    }

    fun loadDirectory(path: String) {
        if (_state.value.isConnected.not()) {
            _state.update {
                it.copy(
                    currentPath = path,
                    isLoading = false,
                    hasLoaded = true
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    selectedFile = null,
                    preview = null,
                    previewError = null
                )
            }

            fileSystem.listDirectory(path)
                .onSuccess { entries ->
                    _state.update { current ->
                        val newHistory = if (path != current.currentPath) {
                            (current.pathHistory + path).distinct()
                        } else {
                            current.pathHistory
                        }
                        current.copy(
                            currentPath = path,
                            entries = sortEntries(entries),
                            isLoading = false,
                            hasLoaded = true,
                            error = null,
                            pathHistory = newHistory
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            hasLoaded = true,
                            error = failure.message ?: "Could not list this folder."
                        )
                    }
                }
        }
    }

    fun refresh() {
        loadDirectory(_state.value.currentPath)
    }

    fun navigateToPath(path: String) {
        if (path != _state.value.currentPath) {
            loadDirectory(path)
        }
    }

    fun navigateUp(): Boolean {
        val currentPath = _state.value.currentPath
        if (currentPath == ROOT_PATH || currentPath == INTERNAL_ROOT_PATH) return false
        val parentPath = currentPath.substringBeforeLast("/").ifEmpty { ROOT_PATH }
        loadDirectory(parentPath)
        return true
    }

    fun onEntryClick(entry: FileEntry) {
        if (entry.isDirectory) {
            loadDirectory(entry.path)
        } else {
            selectFile(entry)
        }
    }

    fun clearSelection() {
        _state.update {
            it.copy(
                selectedFile = null,
                preview = null,
                previewError = null,
                isPreviewLoading = false
            )
        }
    }

    fun retryPreview() {
        _state.value.selectedFile?.let { selectFile(it) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearActionState() {
        _state.update { it.copy(actionError = null, actionSucceeded = false) }
    }

    fun createDirectory(rawName: String) {
        val name = rawName.trim()
        validateName(name)?.let { message ->
            _state.update { it.copy(actionError = message) }
            return
        }
        if (_state.value.isActionInProgress) return

        viewModelScope.launch {
            _state.update { it.copy(isActionInProgress = true, actionError = null, actionSucceeded = false) }
            val path = childPath(_state.value.currentPath, name)
            fileSystem.createDirectory(path)
                .onSuccess {
                    _state.update { it.copy(isActionInProgress = false, actionSucceeded = true) }
                    refreshAfterAction()
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isActionInProgress = false,
                            actionError = "Could not create folder: ${failure.message ?: "unknown error"}"
                        )
                    }
                }
        }
    }

    fun writeFile(rawName: String, content: String) {
        val name = rawName.trim()
        validateName(name)?.let { message ->
            _state.update { it.copy(actionError = message) }
            return
        }
        if (_state.value.isActionInProgress) return

        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            _state.update {
                it.copy(actionError = "Keep new files under 64 KB. This file is ${bytes.size / 1024} KB.")
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isActionInProgress = true, actionError = null, actionSucceeded = false) }
            val path = childPath(_state.value.currentPath, name)
            fileSystem.writeFile(path, content)
                .onSuccess {
                    _state.update { it.copy(isActionInProgress = false, actionSucceeded = true) }
                    refreshAfterAction()
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isActionInProgress = false,
                            actionError = "Could not write file: ${failure.message ?: "unknown error"}"
                        )
                    }
                }
        }
    }

    fun deleteEntry(entry: FileEntry) {
        if (_state.value.isActionInProgress) return
        viewModelScope.launch {
            _state.update { it.copy(isActionInProgress = true, actionError = null, actionSucceeded = false) }
            fileSystem.delete(entry.path, recursive = entry.isDirectory)
                .onSuccess {
                    _state.update {
                        it.copy(
                            isActionInProgress = false,
                            actionSucceeded = true,
                            selectedFile = if (it.selectedFile?.path == entry.path) null else it.selectedFile,
                            preview = if (it.selectedFile?.path == entry.path) null else it.preview
                        )
                    }
                    refreshAfterAction()
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isActionInProgress = false,
                            actionError = "Could not delete ${entry.name}: ${failure.message ?: "unknown error"}"
                        )
                    }
                }
        }
    }

    private fun refreshAfterAction() {
        val path = _state.value.currentPath
        loadDirectory(path)
    }

    private fun selectFile(entry: FileEntry) {
        if (entry.size > MAX_READ_BYTES) {
            _state.update {
                it.copy(
                    selectedFile = entry,
                    preview = FilePreview.TooLarge(
                        totalBytes = entry.size,
                        limitBytes = MAX_READ_BYTES.toLong()
                    ),
                    previewError = null,
                    isPreviewLoading = false
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    selectedFile = entry,
                    preview = null,
                    previewError = null,
                    isPreviewLoading = true
                )
            }
            fileSystem.readFileBytes(entry.path)
                .onSuccess { bytes ->
                    val preview = buildPreview(entry, bytes)
                    _state.update { it.copy(preview = preview, isPreviewLoading = false) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewError = "Could not read this file: ${failure.message ?: "unknown error"}"
                        )
                    }
                }
        }
    }

    private fun sortEntries(entries: List<FileEntry>): List<FileEntry> =
        entries.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )

    private fun childPath(base: String, name: String): String {
        val normalized = base.trimEnd('/')
        return if (normalized.isEmpty()) "/$name" else "$normalized/$name"
    }

    private fun validateName(name: String): String? = when {
        name.isBlank() -> "Enter a name."
        name == "." || name == ".." -> "Choose a different name."
        name.length > MAX_NAME_LENGTH -> "Keep the name under $MAX_NAME_LENGTH characters."
        name.any { it == '/' || it == '\\' } -> "Names cannot contain slashes."
        name.any { it.code < 32 } -> "Remove control characters from the name."
        else -> null
    }

    private fun buildPreview(entry: FileEntry, bytes: ByteArray): FilePreview {
        val total = bytes.size.toLong()
        if (looksBinary(bytes)) {
            val shownBytes = minOf(bytes.size, MAX_BINARY_PREVIEW_BYTES)
            val shown = bytes.copyOfRange(0, shownBytes)
            return FilePreview.Binary(
                hex = hexDump(shown),
                detectedType = describeBinary(entry.name),
                truncated = bytes.size > shownBytes,
                bytesRead = shownBytes,
                totalBytes = total
            )
        }

        val shownBytes = minOf(bytes.size, MAX_TEXT_PREVIEW_BYTES)
        val content = bytes.copyOfRange(0, shownBytes).toString(Charsets.UTF_8)
        return FilePreview.Text(
            content = content,
            truncated = bytes.size > shownBytes,
            bytesRead = shownBytes,
            totalBytes = total
        )
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sampleSize = minOf(bytes.size, BINARY_SAMPLE_BYTES)
        var suspicious = 0
        for (index in 0 until sampleSize) {
            val value = bytes[index].toInt() and 0xFF
            when {
                value == 0 -> return true
                value == 9 || value == 10 || value == 13 -> Unit
                value < 32 || value == 127 -> suspicious++
            }
        }
        return suspicious.toDouble() / sampleSize.toDouble() > BINARY_RATIO_THRESHOLD
    }

    private fun describeBinary(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "sub" -> "Sub-GHz capture"
            "ir" -> "Infrared capture"
            "nfc" -> "NFC dump"
            "rfid" -> "RFID dump"
            "ibtn" -> "iButton dump"
            "fap" -> "Flipper app package"
            "png", "jpg", "jpeg", "bmp", "gif" -> "Image"
            "wav", "mp3" -> "Audio"
            "" -> "Binary file"
            else -> "Binary .$extension file"
        }
    }

    private fun hexDump(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val builder = StringBuilder()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + HEX_BYTES_PER_LINE, bytes.size)
            builder.append(offset.toString(16).padStart(8, '0')).append("  ")
            for (index in offset until end) {
                val value = bytes[index].toInt() and 0xFF
                builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F]).append(' ')
            }
            for (index in end until offset + HEX_BYTES_PER_LINE) {
                builder.append("   ")
            }
            builder.append(' ')
            for (index in offset until end) {
                val value = bytes[index].toInt() and 0xFF
                builder.append(if (value in 32..126) value.toChar() else '.')
            }
            if (end < bytes.size) builder.append('\n')
            offset = end
        }
        return builder.toString()
    }

    companion object {
        const val ROOT_PATH = "/ext"
        const val INTERNAL_ROOT_PATH = "/int"

        private const val MAX_TEXT_PREVIEW_BYTES = 64 * 1024
        private const val MAX_BINARY_PREVIEW_BYTES = 4 * 1024
        private const val BINARY_SAMPLE_BYTES = 4 * 1024
        private const val MAX_WRITE_BYTES = 64 * 1024
        private const val MAX_READ_BYTES = 256 * 1024
        private const val MAX_NAME_LENGTH = 64
        private const val HEX_BYTES_PER_LINE = 16
        private const val BINARY_RATIO_THRESHOLD = 0.05
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
