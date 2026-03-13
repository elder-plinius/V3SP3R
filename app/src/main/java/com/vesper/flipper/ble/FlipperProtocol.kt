package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.app.Application
import com.flipperdevices.protobuf.desktop.Desktop
import com.flipperdevices.protobuf.screen.Gui
import com.flipperdevices.protobuf.storage.Storage as PBStorage
import com.flipperdevices.protobuf.system.System as PBSystem
import com.google.protobuf.ByteString
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Protocol handler for Flipper Zero serial communication.
 * Implements the Protobuf-based RPC protocol used by Flipper.
 */
@Singleton
class FlipperProtocol @Inject constructor() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bleService: FlipperBleService? = null

    private val responseBuffer = ByteArrayOutputStream()
    @Volatile
    private var pendingRequest: PendingRequest? = null
    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val isWriting = AtomicBoolean(false)
    private val commandMutex = Mutex()
    @Volatile
    private var rawCliCollector: RawCliCollector? = null
    @Volatile
    private var rawBinaryCollector: RawBinaryCollector? = null
    @Volatile
    private var firmwareProfile: FirmwareProfile = FirmwareProfile.UNKNOWN
    @Volatile
    private var lastCliProbeAtMs: Long = 0L
    @Volatile
    private var desktopLockProbeSupported: Boolean? = null
    @Volatile
    private var lastDesktopLockProbeAtMs: Long = 0L
    private val rpcStartCandidateCache = ConcurrentHashMap<String, String>()
    private val rpcButtonArgCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var lastRpcExecutionSnapshot: RpcExecutionSnapshot? = null
    private val remoteHealthRefreshInFlight = AtomicBoolean(false)
    @Volatile
    private var lastRpcActivityAtMs: Long = 0L

    private var currentRequestId: UInt = 0u
    private val immediateRpcCommandId = AtomicInteger(10_000)

    private val _responseFlow = MutableSharedFlow<ProtocolResponse>()
    val responseFlow: SharedFlow<ProtocolResponse> = _responseFlow.asSharedFlow()
    private val _cliStatus = MutableStateFlow(CliCapabilityStatus())
    val cliStatus: StateFlow<CliCapabilityStatus> = _cliStatus.asStateFlow()
    private val _firmwareCompatibility = MutableStateFlow(FirmwareCompatibilityProfile())
    val firmwareCompatibility: StateFlow<FirmwareCompatibilityProfile> = _firmwareCompatibility.asStateFlow()

    init {
        scope.launch {
            processWriteQueue()
        }
    }

    fun setBleService(service: FlipperBleService) {
        this.bleService = service
    }

    fun onConnectionReset() {
        responseBuffer.reset()
        rawCliCollector = null
        firmwareProfile = FirmwareProfile.UNKNOWN
        lastCliProbeAtMs = 0L
        desktopLockProbeSupported = null
        lastDesktopLockProbeAtMs = 0L
        rpcStartCandidateCache.clear()
        rpcButtonArgCache.clear()
        lastRpcExecutionSnapshot = null
        _cliStatus.value = CliCapabilityStatus(
            level = CliCapabilityLevel.UNKNOWN,
            checkedAtMs = System.currentTimeMillis(),
            details = "Connection reset. Capability will be rechecked."
        )
        // Set transport to PROBING instead of UNAVAILABLE — probes will update
        // once they complete. This prevents the UI from showing "transport
        // unavailable" during the brief window between connect and probe.
        _firmwareCompatibility.value = _firmwareCompatibility.value.copy(
            transportMode = FirmwareTransportMode.PROBING,
            notes = "Probing transport capabilities..."
        )

        pendingRequest?.let { pending ->
            pending.continuation.complete(ProtocolResponse.Error("Connection reset"))
        }
        pendingRequest = null
    }

    private suspend fun processWriteQueue() {
        for (data in writeQueue) {
            val service = bleService
            if (service == null) {
                failPendingRequest("Device service unavailable")
                continue
            }

            val sent = service.sendData(data)
            if (!sent) {
                val reason = service.consumeLastWriteFailureReason()
                val detail = if (reason.isNullOrBlank()) "" else ": $reason"
                failPendingRequest("Failed to write command over active transport$detail")
                continue
            }
            delay(10) // Small delay between writes
        }
    }

    fun processIncomingData(data: ByteArray) {
        scope.launch {
            try {
                val collector = rawCliCollector
                if (collector != null) {
                    collector.append(data)
                    return@launch
                }
                val binaryCollector = rawBinaryCollector
                if (binaryCollector != null) {
                    binaryCollector.append(data)
                    return@launch
                }
                responseBuffer.write(data)
                parseResponses()
            } catch (e: Exception) {
                _responseFlow.emit(ProtocolResponse.Error("Parse error: ${e.message}"))
            }
        }
    }

    private suspend fun parseResponses() {
        while (true) {
            val buffer = responseBuffer.toByteArray()
            if (buffer.isEmpty()) return

            when (val rpcConsume = consumeLeadingRpcFrames(buffer)) {
                RpcFrameConsumeResult.None -> Unit
                RpcFrameConsumeResult.Partial -> return
                is RpcFrameConsumeResult.Consumed -> {
                    discardBufferedPrefix(buffer = buffer, prefixSize = rpcConsume.bytes)
                    continue
                }
            }

            if (buffer.size < 4) return

            // Simple frame format: [length:4][data:length]
            val length = ByteBuffer.wrap(buffer, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            if (length <= 0 || length > MAX_FRAME_SIZE) {
                handleNonProtocolResponse(buffer)
                return
            }

            if (buffer.size < 4 + length) return

            val frameData = buffer.sliceArray(4 until 4 + length)
            responseBuffer.reset()
            if (buffer.size > 4 + length) {
                responseBuffer.write(buffer.sliceArray(4 + length until buffer.size))
            }

            val response = parseFrame(frameData)
            _responseFlow.emit(response)

            // Handle pending request
            completePendingRequest(response)
        }
    }

    /**
     * Some paths send protobuf RPC frames without raw collectors.
     * Consume those frames here so they don't poison legacy frame parsing.
     */
    private fun consumeLeadingRpcFrames(buffer: ByteArray): RpcFrameConsumeResult {
        var offset = 0
        var consumedAny = false

        while (offset < buffer.size) {
            val lengthRead = readVarint(buffer, offset) ?: break
            val frameLength = lengthRead.value.toInt()
            if (frameLength <= 0 || frameLength > MAX_FRAME_SIZE) {
                break
            }
            val frameStart = lengthRead.nextOffset
            val frameEnd = frameStart + frameLength
            if (frameEnd > buffer.size) {
                return if (consumedAny) {
                    RpcFrameConsumeResult.Consumed(offset)
                } else {
                    RpcFrameConsumeResult.Partial
                }
            }

            val frame = buffer.copyOfRange(frameStart, frameEnd)
            val main = runCatching { Flipper.Main.parseFrom(frame) }.getOrNull() ?: break
            if (main.commandId <= 0) {
                break
            }

            consumedAny = true
            offset = frameEnd
        }

        return if (consumedAny) {
            RpcFrameConsumeResult.Consumed(offset)
        } else {
            RpcFrameConsumeResult.None
        }
    }

    private fun discardBufferedPrefix(buffer: ByteArray, prefixSize: Int) {
        responseBuffer.reset()
        if (prefixSize < buffer.size) {
            responseBuffer.write(buffer, prefixSize, buffer.size - prefixSize)
        }
    }

    private suspend fun handleNonProtocolResponse(buffer: ByteArray) {
        responseBuffer.reset()
        val snippet = String(buffer, Charsets.UTF_8)
            .replace("\u0000", "")
            .trim()
            .take(200)
        val message = if (snippet.isNotEmpty()) {
            "Device returned non-protocol response: $snippet"
        } else {
            "Device returned invalid protocol frame"
        }
        val response = ProtocolResponse.Error(message)
        _responseFlow.emit(response)

        // Avoid failing in-flight RPC requests on benign textual noise.
        val shouldFailPending = snippet.isBlank() || !isLikelyCliText(snippet)
        if (shouldFailPending) {
            completePendingRequest(response)
        }
    }

    private fun parseFrame(data: ByteArray): ProtocolResponse {
        if (data.isEmpty()) return ProtocolResponse.Error("Empty frame")

        return when (data[0].toInt()) {
            RESP_OK -> parseOkResponse(data)
            RESP_ERROR -> parseErrorResponse(data)
            RESP_LIST -> parseListResponse(data)
            RESP_DATA -> parseDataResponse(data)
            RESP_INFO -> parseInfoResponse(data)
            RESP_CLI -> parseDataResponse(data)
            else -> ProtocolResponse.Error("Unknown response type: ${data[0]}")
        }
    }

    private fun parseOkResponse(data: ByteArray): ProtocolResponse {
        val message = if (data.size > 1) String(data, 1, data.size - 1, Charsets.UTF_8) else "OK"
        return ProtocolResponse.Success(message)
    }

    private fun parseErrorResponse(data: ByteArray): ProtocolResponse {
        val errorCode = if (data.size > 1) data[1].toInt() else -1
        val message = if (data.size > 2) String(data, 2, data.size - 2, Charsets.UTF_8) else "Error"
        return ProtocolResponse.Error(message, errorCode)
    }

    private fun parseListResponse(data: ByteArray): ProtocolResponse {
        val entries = mutableListOf<FileEntry>()
        var offset = 1

        while (offset < data.size) {
            if (offset + 9 > data.size) break

            val isDir = data[offset] == 1.toByte()
            val size = ByteBuffer.wrap(data, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            val nameLen = ByteBuffer.wrap(data, offset + 5, 4).order(ByteOrder.LITTLE_ENDIAN).int

            offset += 9
            if (offset + nameLen > data.size) break

            val name = String(data, offset, nameLen, Charsets.UTF_8)
            offset += nameLen

            entries.add(FileEntry(
                name = name,
                path = name,
                isDirectory = isDir,
                size = size
            ))
        }

        return ProtocolResponse.DirectoryList(entries)
    }

    private fun parseDataResponse(data: ByteArray): ProtocolResponse {
        val content = if (data.size > 1) String(data, 1, data.size - 1, Charsets.UTF_8) else ""
        return ProtocolResponse.FileContent(content)
    }

    private fun parseInfoResponse(data: ByteArray): ProtocolResponse {
        if (data.size < 20) return ProtocolResponse.Error("Invalid info response")

        val buffer = ByteBuffer.wrap(data, 1, data.size - 1).order(ByteOrder.LITTLE_ENDIAN)

        val batteryLevel = buffer.get().toInt() and 0xFF
        val isCharging = buffer.get() == 1.toByte()

        val internalTotal = buffer.long
        val internalFree = buffer.long

        return ProtocolResponse.DeviceInformation(
            deviceInfo = DeviceInfo(
                name = "Flipper Zero",
                firmwareVersion = "0.0.0",
                hardwareVersion = "1.0",
                batteryLevel = batteryLevel,
                isCharging = isCharging
            ),
            storageInfo = StorageInfo(
                internalTotal = internalTotal,
                internalFree = internalFree,
                hasSdCard = false
            )
        )
    }

    // Command building and sending

    suspend fun listDirectory(path: String): ProtocolResponse = withCommandLock(
        operation = "list directory",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while listing directory: $path") }
    ) {
        sendRpcListDirectoryLocked(path) ?: sendLegacyListDirectoryLocked(path)
    }

    suspend fun readFile(path: String): ProtocolResponse = withCommandLock(
        operation = "read file",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while reading file: $path") }
    ) {
        when (val response = sendRpcReadFileLocked(path) ?: sendLegacyReadFileLocked(path)) {
            is ProtocolResponse.BinaryContent -> {
                ProtocolResponse.FileContent(response.data.toString(Charsets.UTF_8))
            }
            else -> response
        }
    }

    suspend fun readFileBinary(path: String): ProtocolResponse = withCommandLock(
        operation = "read file binary",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while reading binary file: $path") }
    ) {
        sendRpcReadFileLocked(path) ?: sendLegacyReadFileLocked(path)
    }

    suspend fun writeFile(path: String, content: ByteArray): ProtocolResponse = withCommandLock(
        operation = "write file",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while writing file: $path") }
    ) {
        sendRpcWriteFileLocked(path, content) ?: sendLegacyWriteFileLocked(path, content)
    }

    suspend fun createDirectory(path: String): ProtocolResponse = withCommandLock(
        operation = "create directory",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while creating directory: $path") }
    ) {
        sendRpcCreateDirectoryLocked(path) ?: sendLegacyCreateDirectoryLocked(path)
    }

    suspend fun delete(path: String, recursive: Boolean = false): ProtocolResponse = withCommandLock(
        operation = "delete path",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while deleting: $path") }
    ) {
        sendRpcDeleteLocked(path, recursive) ?: sendLegacyDeleteLocked(path, recursive)
    }

    suspend fun move(sourcePath: String, destPath: String): ProtocolResponse = withCommandLock(
        operation = "move path",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while moving: $sourcePath -> $destPath") }
    ) {
        sendRpcMoveLocked(sourcePath, destPath) ?: sendLegacyMoveLocked(sourcePath, destPath)
    }

    suspend fun getDeviceInfo(): ProtocolResponse = withCommandLock(
        operation = "get device info",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while requesting device info") }
    ) {
        sendRpcDeviceInfoLocked() ?: sendLegacyDeviceInfoLocked()
    }

    suspend fun getStorageInfo(): ProtocolResponse = withCommandLock(
        operation = "get storage info",
        onTimeout = { ProtocolResponse.Error("Command pipeline busy while requesting storage info") }
    ) {
        sendRpcStorageInfoLocked() ?: sendLegacyStorageInfoLocked()
    }

    private suspend fun sendLegacyListDirectoryLocked(path: String): ProtocolResponse {
        val command = buildCommand(CMD_LIST, path.toByteArray(Charsets.UTF_8))
        return sendCommandLocked(command)
    }

    private suspend fun sendLegacyReadFileLocked(path: String): ProtocolResponse {
        val command = buildCommand(CMD_READ, path.toByteArray(Charsets.UTF_8))
        return sendCommandLocked(command)
    }

    private suspend fun sendLegacyWriteFileLocked(path: String, content: ByteArray): ProtocolResponse {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(4 + pathBytes.size + content.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(pathBytes.size)
            .put(pathBytes)
            .put(content)
            .array()
        val command = buildCommand(CMD_WRITE, payload)
        return sendCommandLocked(
            command = command,
            timeoutMs = resolveLegacyWriteTimeoutMs(content.size)
        )
    }

    private suspend fun sendLegacyCreateDirectoryLocked(path: String): ProtocolResponse {
        val command = buildCommand(CMD_MKDIR, path.toByteArray(Charsets.UTF_8))
        return sendCommandLocked(command)
    }

    private suspend fun sendLegacyDeleteLocked(path: String, recursive: Boolean): ProtocolResponse {
        val flags = if (recursive) 1.toByte() else 0.toByte()
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(flags) + pathBytes
        val command = buildCommand(CMD_DELETE, payload)
        return sendCommandLocked(command)
    }

    private suspend fun sendLegacyMoveLocked(sourcePath: String, destPath: String): ProtocolResponse {
        val sourceBytes = sourcePath.toByteArray(Charsets.UTF_8)
        val destBytes = destPath.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(8 + sourceBytes.size + destBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sourceBytes.size)
            .put(sourceBytes)
            .putInt(destBytes.size)
            .put(destBytes)
            .array()
        val command = buildCommand(CMD_MOVE, payload)
        return sendCommandLocked(command)
    }

    private suspend fun sendLegacyDeviceInfoLocked(): ProtocolResponse {
        val command = buildCommand(CMD_INFO, byteArrayOf())
        return sendCommandLocked(command)
    }

    private suspend fun sendLegacyStorageInfoLocked(): ProtocolResponse {
        val command = buildCommand(CMD_STORAGE_INFO, byteArrayOf())
        return sendCommandLocked(command)
    }

    private fun buildCommand(commandType: Byte, payload: ByteArray): ByteArray {
        val requestId = currentRequestId++
        val frame = ByteBuffer.allocate(5 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(requestId.toInt())
            .put(commandType)
            .put(payload)
            .array()

        // Wrap with length prefix
        return ByteBuffer.allocate(4 + frame.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(frame.size)
            .put(frame)
            .array()
    }

    private suspend fun sendCommand(command: ByteArray): ProtocolResponse = commandMutex.withLock {
        sendCommandLocked(command)
    }

    private suspend fun sendCommandLocked(
        command: ByteArray,
        timeoutMs: Long = COMMAND_TIMEOUT_MS
    ): ProtocolResponse {
        val service = bleService
            ?: return ProtocolResponse.Error("Flipper device service unavailable").also {
                markCliUnavailable("Device service unavailable")
            }
        if (!ensureTransportConnected(service)) {
            return ProtocolResponse.Error("Flipper command transport is not connected").also {
                markCliUnavailable("Flipper command transport is not connected")
            }
        }
        if (pendingRequest != null) {
            return ProtocolResponse.Error(
                "Command pipeline busy: pending legacy response not yet completed"
            )
        }

        val deferred = CompletableDeferred<ProtocolResponse>()
        val pendingRequest = PendingRequest(command, deferred)
        this.pendingRequest = pendingRequest
        writeQueue.send(command)

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            if (this.pendingRequest === pendingRequest) {
                this.pendingRequest = null
            }
            deferred.cancel()
            ProtocolResponse.Error("Command timed out")
        } catch (e: Exception) {
            if (this.pendingRequest === pendingRequest) {
                this.pendingRequest = null
            }
            deferred.cancel()
            ProtocolResponse.Error("Command failed: ${e.message}")
        }
    }

    private suspend fun ensureTransportConnected(service: FlipperBleService): Boolean {
        if (service.isCommandTransportConnected()) return true
        return service.awaitCommandTransportReady()
    }

    private suspend fun sendRpcListDirectoryLocked(path: String): ProtocolResponse? {
        if (!ensureRpcAvailableLocked("RPC ping responded for storage list request")) {
            return null
        }
        val responses = sendRpcMainAndCollectResponses(timeoutMs = RPC_STORAGE_COMMAND_TIMEOUT_MS) {
            setStorageListRequest(
                PBStorage.ListRequest.newBuilder()
                    .setPath(path)
                    .build()
            )
        }
        if (responses.isEmpty()) {
            return ProtocolResponse.Error("No RPC response for storage list request: $path")
        }
        val status = responses.firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }?.commandStatus
        if (status != null && status != Flipper.CommandStatus.OK) {
            if (shouldFallbackToLegacy(status)) {
                return null
            }
            return ProtocolResponse.Error("RPC storage list failed: ${status.name}", status.number)
        }

        val files = responses
            .filter { it.hasStorageListResponse() }
            .flatMap { it.storageListResponse.fileList }
        if (files.isEmpty() && responses.none { it.hasStorageListResponse() }) {
            return null
        }

        val entries = files.map { file ->
            FileEntry(
                name = file.name,
                path = normalizeStoragePath(path, file.name),
                isDirectory = file.type == PBStorage.File.FileType.DIR,
                size = unsignedIntToLong(file.size)
            )
        }
        return ProtocolResponse.DirectoryList(entries)
    }

    private suspend fun sendRpcReadFileLocked(path: String): ProtocolResponse? {
        if (!ensureRpcAvailableLocked("RPC ping responded for storage read request")) {
            return null
        }
        val responses = sendRpcMainAndCollectResponses(timeoutMs = RPC_STORAGE_COMMAND_TIMEOUT_MS) {
            setStorageReadRequest(
                PBStorage.ReadRequest.newBuilder()
                    .setPath(path)
                    .build()
            )
        }
        if (responses.isEmpty()) {
            return ProtocolResponse.Error("No RPC response for storage read request: $path")
        }
        val status = responses.firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }?.commandStatus
        if (status != null && status != Flipper.CommandStatus.OK) {
            if (shouldFallbackToLegacy(status)) {
                return null
            }
            return ProtocolResponse.Error("RPC storage read failed: ${status.name}", status.number)
        }

        val chunks = responses
            .filter { it.hasStorageReadResponse() }
            .mapNotNull { response ->
                val readResponse = response.storageReadResponse
                if (readResponse.hasFile()) readResponse.file.data.toByteArray() else null
            }
        if (chunks.isEmpty() && responses.none { it.hasStorageReadResponse() }) {
            return null
        }

        val combined = ByteArrayOutputStream()
        chunks.forEach { chunk ->
            if (chunk.isNotEmpty()) {
                combined.write(chunk)
            }
        }
        return ProtocolResponse.BinaryContent(combined.toByteArray())
    }

    private suspend fun sendRpcWriteFileLocked(path: String, content: ByteArray): ProtocolResponse? {
        if (!ensureRpcAvailableLocked("RPC ping responded for storage write request")) {
            return null
        }

        // Small files: send in a single message (original behavior).
        if (content.size <= WRITE_CHUNK_SIZE) {
            return sendRpcWriteSingleLocked(path, content)
        }

        // Large files: stream in chunks to avoid overwhelming Flipper's RPC buffer.
        return sendRpcWriteChunkedLocked(path, content)
    }

    private suspend fun sendRpcWriteSingleLocked(path: String, content: ByteArray): ProtocolResponse? {
        val fileMessage = PBStorage.File.newBuilder()
            .setType(PBStorage.File.FileType.FILE)
            .setName(path.substringAfterLast('/'))
            .setSize(content.size)
            .setData(ByteString.copyFrom(content))
            .build()
        val timeoutMs = resolveRpcStorageWriteTimeoutMs(content.size)
        var lastFailure: ProtocolResponse? = null

        repeat(RPC_STORAGE_WRITE_MAX_ATTEMPTS) { attempt ->
            val responses = sendRpcMainAndCollectResponses(timeoutMs = timeoutMs) {
                setStorageWriteRequest(
                    PBStorage.WriteRequest.newBuilder()
                        .setPath(path)
                        .setFile(fileMessage)
                        .build()
                )
            }
            if (responses.isEmpty()) {
                lastFailure = ProtocolResponse.Error("No RPC response for storage write request: $path")
            } else {
                val status = responses
                    .firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }
                    ?.commandStatus
                if (status == null || status == Flipper.CommandStatus.OK) {
                    return ProtocolResponse.Success("RPC storage write executed: $path")
                }
                if (shouldFallbackToLegacy(status)) {
                    return null
                }
                lastFailure = ProtocolResponse.Error("RPC storage write failed: ${status.name}", status.number)
                if (!isRetryableStorageWriteStatus(status)) {
                    return lastFailure
                }
            }

            if (attempt < RPC_STORAGE_WRITE_MAX_ATTEMPTS - 1) {
                delay(RPC_STORAGE_WRITE_RETRY_DELAY_MS * (attempt + 1))
            }
        }

        return lastFailure ?: ProtocolResponse.Error("RPC storage write failed: $path")
    }

    /**
     * Stream a large file to Flipper in WRITE_CHUNK_SIZE chunks using the
     * RPC streaming protocol (hasNext=true on all packets except the last).
     * This prevents the Flipper's RPC buffer from overflowing and hanging
     * the device with the hourglass animation.
     */
    private suspend fun sendRpcWriteChunkedLocked(path: String, content: ByteArray): ProtocolResponse? {
        val commandId = nextRpcProbeCommandId()
        val totalChunks = (content.size + WRITE_CHUNK_SIZE - 1) / WRITE_CHUNK_SIZE
        val service = bleService ?: return ProtocolResponse.Error("BLE not connected")
        val timeoutMs = resolveRpcStorageWriteTimeoutMs(content.size)
        var lastFailure: ProtocolResponse? = null

        repeat(RPC_STORAGE_WRITE_MAX_ATTEMPTS) { attempt ->
            var sendFailed = false

            for (chunkIndex in 0 until totalChunks) {
                val offset = chunkIndex * WRITE_CHUNK_SIZE
                val end = minOf(offset + WRITE_CHUNK_SIZE, content.size)
                val chunkData = content.copyOfRange(offset, end)
                val isLast = chunkIndex == totalChunks - 1

                val fileMessage = PBStorage.File.newBuilder()
                    .setType(PBStorage.File.FileType.FILE)
                    .setName(path.substringAfterLast('/'))
                    .setSize(chunkData.size)
                    .setData(ByteString.copyFrom(chunkData))
                    .build()

                val writeRequest = PBStorage.WriteRequest.newBuilder()
                    .setPath(path)
                    .setFile(fileMessage)
                    .build()

                if (isLast) {
                    // Final chunk: send and collect the response using the same commandId.
                    val finalPacket = buildRpcMainPacket(commandId) {
                        setStorageWriteRequest(writeRequest)
                    }
                    val responseBytes = collectRawBinaryResponseAttempt(finalPacket, timeoutMs)
                    val responses = if (responseBytes.isNotEmpty()) {
                        findRpcMainMessages(responseBytes, commandId)
                    } else {
                        emptyList()
                    }
                    if (responses.isEmpty()) {
                        lastFailure = ProtocolResponse.Error("No RPC response for storage write (chunked): $path")
                    } else {
                        val status = responses
                            .firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }
                            ?.commandStatus
                        if (status == null || status == Flipper.CommandStatus.OK) {
                            return ProtocolResponse.Success("RPC storage write executed (chunked, $totalChunks chunks): $path")
                        }
                        if (shouldFallbackToLegacy(status)) {
                            return null
                        }
                        lastFailure = ProtocolResponse.Error("RPC storage write failed: ${status.name}", status.number)
                        if (!isRetryableStorageWriteStatus(status)) {
                            return lastFailure
                        }
                    }
                } else {
                    // Intermediate chunk: fire-and-forget with hasNext=true.
                    val packet = buildRpcMainPacket(commandId) {
                        setHasNext(true)
                        setStorageWriteRequest(writeRequest)
                    }
                    val sent = service.sendData(packet)
                    if (!sent) {
                        lastFailure = ProtocolResponse.Error("BLE send failed during chunked write at chunk ${chunkIndex + 1}/$totalChunks: $path")
                        sendFailed = true
                        break
                    }
                    // Brief pause between chunks to let Flipper process and avoid buffer overflow.
                    delay(WRITE_CHUNK_DELAY_MS)
                }
            }

            if (sendFailed && attempt < RPC_STORAGE_WRITE_MAX_ATTEMPTS - 1) {
                delay(RPC_STORAGE_WRITE_RETRY_DELAY_MS * (attempt + 1))
                return@repeat // retry next attempt
            }
            if (!sendFailed) {
                // If we got here without sendFailed, the final chunk's response was handled above
                // and we either returned success or set lastFailure. Stop retrying.
                return@repeat
            }
        }

        return lastFailure ?: ProtocolResponse.Error("RPC storage write failed (chunked): $path")
    }

    private suspend fun sendRpcCreateDirectoryLocked(path: String): ProtocolResponse? {
        if (!ensureRpcAvailableLocked("RPC ping responded for storage mkdir request")) {
            return null
        }
        val responses = sendRpcMainAndCollectResponses(timeoutMs = RPC_STORAGE_COMMAND_TIMEOUT_MS) {
            setStorageMkdirRequest(
                PBStorage.MkdirRequest.newBuilder()
                    .setPath(path)
                    .build()
            )
        }
        if (responses.isEmpty()) {
            return ProtocolResponse.Error("No RPC response for storage mkdir request: $path")
        }
        val status = responses.firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }?.commandStatus
        if (status != null && status != Flipper.CommandStatus.OK) {
            if (shouldFallbackToLegacy(status)) {
                return null
            }
            return ProtocolResponse.Error("RPC storage mkdir failed: ${status.name}", status.number)
        }

        return ProtocolResponse.Success("RPC storage mkdir executed: $path")
    }

    private suspend fun sendRpcDeleteLocked(path: String, recursive: Boolean): ProtocolResponse? {
        if (!ensureRpcAvailableLocked("RPC ping responded for storage delete request")) {
            return null
        }
        val responses = sendRpcMainAndCollectResponses(timeoutMs = RPC_STORAGE_COMMAND_TIMEOUT_MS) {
            setStorageDeleteRequest(
                PBStorage.DeleteRequest.newBuilder()
                    .setPath(path)
                    .setRecursive(recursive)
                    .build()
            )
        }
        if (responses.isEmpty()) {
            return ProtocolResponse.Error("No RPC response for storage delete request: $path")
        }
        val status = responses.firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }?.commandStatus
        if (status != null && status != Flipper.CommandStatus.OK) {
            if (shouldFallbackToLegacy(status)) {
                return null
            }
            return ProtocolResponse.Error("RPC storage delete failed: ${status.name}", status.number)
        }

        return ProtocolResponse.Success("RPC storage delete executed: $path")
    }

    private suspend fun sendRpcMoveLocked(sourcePath: String, destPath: String): ProtocolResponse? {
        if (!ensureRpcAvailableLocked("RPC ping responded for storage rename request")) {
            return null
        }
        val responses = sendRpcMainAndCollectResponses(timeoutMs = RPC_STORAGE_COMMAND_TIMEOUT_MS) {
            setStorageRenameRequest(
                PBStorage.RenameRequest.newBuilder()
                    .setOldPath(sourcePath)
                    .setNewPath(destPath)
                    .build()
            )
        }
        if (responses.isEmpty()) {
            return ProtocolResponse.Error("No RPC response for storage rename request: $sourcePath -> $destPath")
        }
        val status = responses.firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }?.commandStatus
        if (status != null && status != Flipper.CommandStatus.OK) {
            if (shouldFallbackToLegacy(status)) {
                return null
            }
            return ProtocolResponse.Error("RPC storage rename failed: ${status.name}", status.number)
        }

        return ProtocolResponse.Success("RPC storage rename executed: $sourcePath -> $destPath")
    }

    private suspend fun sendRpcDeviceInfoLocked(): ProtocolResponse? {
        if (!ensureRpcAvailableLocked("RPC ping responded for system device-info request")) {
            return null
        }
        val deviceInfoPairs = requestDeviceInfoMapLocked()
        if (deviceInfoPairs.isEmpty()) {
            return null
        }
        val powerInfoPairs = requestPowerInfoMapLocked()

        val deviceName = firstNonBlank(
            deviceInfoPairs,
            "device_name",
            "name",
            "hardware_name",
            "product_name"
        ) ?: "Flipper Zero"

        val firmwareVersion = firstNonBlank(
            deviceInfoPairs,
            "firmware_version",
            "firmware",
            "version",
            "git_commit",
            "branch"
        ) ?: "unknown"

        val hardwareVersion = firstNonBlank(
            deviceInfoPairs,
            "hardware_version",
            "hardware",
            "hardware_name",
            "target"
        ) ?: "unknown"

        val batteryLevel = parsePercent(
            powerInfoPairs,
            "charge_percent",
            "charge_level",
            "battery_level",
            "capacity_percent"
        ) ?: parsePercent(
            deviceInfoPairs,
            "battery_level",
            "charge_percent",
            "charge_level"
        ) ?: parseBatteryLevelFromVoltage(powerInfoPairs)
            ?: parseBatteryLevelFromVoltage(deviceInfoPairs)
            ?: parseBatteryLevelFromCapacity(powerInfoPairs)
            ?: parseBatteryLevelFromCapacity(deviceInfoPairs)
            ?: parseBatteryPercentFromBatteryKey(powerInfoPairs["battery"])
            ?: parseBatteryPercentFromBatteryKey(deviceInfoPairs["battery"])
            ?: 0

        val isCharging = parseBoolean(
            powerInfoPairs,
            "is_charging",
            "charging",
            "charger_connected",
            "charge_state"
        ) ?: parseBoolean(
            deviceInfoPairs,
            "is_charging",
            "charging"
        ) ?: false

        val storageInfo = readStorageInfoFromRpcLocked() ?: StorageInfo(
            internalTotal = 0L,
            internalFree = 0L,
            hasSdCard = false
        )

        return ProtocolResponse.DeviceInformation(
            deviceInfo = DeviceInfo(
                name = deviceName,
                firmwareVersion = firmwareVersion,
                hardwareVersion = hardwareVersion,
                batteryLevel = batteryLevel.coerceIn(0, 100),
                isCharging = isCharging
            ),
            storageInfo = storageInfo
        )
    }

    private suspend fun sendRpcStorageInfoLocked(): ProtocolResponse? {
        if (!ensureRpcAvailableLocked("RPC ping responded for storage info request")) {
            return null
        }
        val storageInfo = readStorageInfoFromRpcLocked() ?: return null
        return ProtocolResponse.DeviceInformation(
            deviceInfo = DeviceInfo(
                name = "Flipper Zero",
                firmwareVersion = "unknown",
                hardwareVersion = "unknown",
                batteryLevel = 0,
                isCharging = false
            ),
            storageInfo = storageInfo
        )
    }

    private suspend fun readStorageInfoFromRpcLocked(): StorageInfo? {
        val internal = requestStorageInfoForPathLocked("/int")
            ?: requestStorageInfoForPathLocked("/")
        val external = requestStorageInfoForPathLocked("/ext")

        if (internal == null && external == null) {
            return null
        }

        return StorageInfo(
            internalTotal = internal?.totalSpace ?: 0L,
            internalFree = internal?.freeSpace ?: 0L,
            externalTotal = external?.totalSpace,
            externalFree = external?.freeSpace,
            hasSdCard = (external?.totalSpace ?: 0L) > 0L
        )
    }

    private suspend fun requestStorageInfoForPathLocked(path: String): PBStorage.InfoResponse? {
        val responses = sendRpcMainAndCollectResponses(timeoutMs = RPC_STORAGE_COMMAND_TIMEOUT_MS) {
            setStorageInfoRequest(
                PBStorage.InfoRequest.newBuilder()
                    .setPath(path)
                    .build()
            )
        }
        if (responses.isEmpty()) {
            return null
        }
        val status = responses.firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }?.commandStatus
        if (status != null && status != Flipper.CommandStatus.OK) {
            return null
        }
        return responses
            .asReversed()
            .firstOrNull { it.hasStorageInfoResponse() }
            ?.storageInfoResponse
    }

    private suspend fun requestDeviceInfoMapLocked(): Map<String, String> {
        val responses = sendRpcMainAndCollectResponses(timeoutMs = RPC_STORAGE_COMMAND_TIMEOUT_MS) {
            setSystemDeviceInfoRequest(PBSystem.DeviceInfoRequest.getDefaultInstance())
        }
        if (responses.isEmpty()) {
            return emptyMap()
        }
        val status = responses.firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }?.commandStatus
        if (status != null && status != Flipper.CommandStatus.OK) {
            return emptyMap()
        }

        return responses
            .filter { it.hasSystemDeviceInfoResponse() }
            .map { it.systemDeviceInfoResponse }
            .associate { response ->
                normalizeInfoKey(response.key) to response.value
            }
            .filterValues { it.isNotBlank() }
    }

    private suspend fun requestPowerInfoMapLocked(): Map<String, String> {
        val responses = sendRpcMainAndCollectResponses(timeoutMs = RPC_STORAGE_COMMAND_TIMEOUT_MS) {
            setSystemPowerInfoRequest(PBSystem.PowerInfoRequest.getDefaultInstance())
        }
        if (responses.isEmpty()) {
            return emptyMap()
        }
        val status = responses.firstOrNull { it.commandStatus != Flipper.CommandStatus.OK }?.commandStatus
        if (status != null && status != Flipper.CommandStatus.OK) {
            return emptyMap()
        }

        return responses
            .filter { it.hasSystemPowerInfoResponse() }
            .map { it.systemPowerInfoResponse }
            .associate { response ->
                normalizeInfoKey(response.key) to response.value
            }
            .filterValues { it.isNotBlank() }
    }

    private suspend fun ensureRpcAvailableLocked(detail: String): Boolean {
        val current = _cliStatus.value
        if (current.level == CliCapabilityLevel.READY && current.supportsRpc) {
            return true
        }
        val probed = probeRpcTransportAvailability(detail = detail)
        return probed?.supportsRpc == true
    }

    private fun shouldFallbackToLegacy(status: Flipper.CommandStatus): Boolean {
        return status == Flipper.CommandStatus.ERROR_NOT_IMPLEMENTED ||
                status == Flipper.CommandStatus.ERROR_DECODE ||
                status == Flipper.CommandStatus.ERROR_STORAGE_NOT_IMPLEMENTED
    }

    private fun isRetryableStorageWriteStatus(status: Flipper.CommandStatus): Boolean {
        return status == Flipper.CommandStatus.ERROR_BUSY ||
                status == Flipper.CommandStatus.ERROR_STORAGE_NOT_READY ||
                status == Flipper.CommandStatus.ERROR_CONTINUOUS_COMMAND_INTERRUPTED
    }

    private fun resolveRpcStorageWriteTimeoutMs(contentSize: Int): Long {
        if (contentSize <= 0) return RPC_STORAGE_COMMAND_TIMEOUT_MS
        val kib = (contentSize.toLong() + 1023L) / 1024L
        return (RPC_STORAGE_WRITE_BASE_TIMEOUT_MS + kib * RPC_STORAGE_WRITE_PER_KIB_TIMEOUT_MS)
            .coerceIn(RPC_STORAGE_COMMAND_TIMEOUT_MS, RPC_STORAGE_WRITE_TIMEOUT_MS)
    }

    private fun resolveLegacyWriteTimeoutMs(contentSize: Int): Long {
        if (contentSize <= 0) return COMMAND_TIMEOUT_MS
        val kib = (contentSize.toLong() + 1023L) / 1024L
        return (LEGACY_WRITE_BASE_TIMEOUT_MS + kib * LEGACY_WRITE_PER_KIB_TIMEOUT_MS)
            .coerceIn(COMMAND_TIMEOUT_MS, LEGACY_WRITE_TIMEOUT_MS)
    }

    private fun normalizeStoragePath(basePath: String, childName: String): String {
        val normalizedBase = basePath.trimEnd('/')
        return if (normalizedBase.isEmpty()) {
            "/$childName"
        } else {
            "$normalizedBase/$childName"
        }
    }

    private fun unsignedIntToLong(value: Int): Long {
        return value.toLong() and 0xFFFF_FFFFL
    }

    private fun normalizeInfoKey(key: String): String {
        return key.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun firstNonBlank(values: Map<String, String>, vararg keys: String): String? {
        keys.forEach { key ->
            values[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun parsePercent(values: Map<String, String>, vararg keys: String): Int? {
        keys.forEach { key ->
            val parsed = values[key]?.let { parseFirstInt(it) }
            if (parsed != null) {
                return parsed.coerceIn(0, 100)
            }
        }
        return null
    }

    private fun parseBatteryLevelFromCapacity(values: Map<String, String>): Int? {
        val raw = firstNonBlank(values, "capacity_percent", "capacity") ?: return null
        val normalized = raw.lowercase()
        if (normalized.contains("mah")) return null
        val parsed = parseFirstInt(raw) ?: return null
        return if (parsed in 0..100) parsed else null
    }

    private fun parseBatteryLevelFromVoltage(values: Map<String, String>): Int? {
        val voltageKeys = listOf(
            "battery_voltage",
            "voltage",
            "vbat",
            "vbatt",
            "vbat_mv",
            "power_voltage"
        )
        voltageKeys.forEach { key ->
            val raw = values[key] ?: return@forEach
            val mv = parseVoltageMillivolts(raw) ?: return@forEach
            return estimateBatteryPercentFromMillivolts(mv)
        }
        return null
    }

    private fun parseBatteryPercentFromBatteryKey(rawValue: String?): Int? {
        val raw = rawValue?.trim().orEmpty()
        if (raw.isBlank()) return null
        val normalized = raw.lowercase()
        if (normalized.contains("mah") ||
            normalized.contains("mwh") ||
            normalized.contains("mv") ||
            normalized.contains("volt") ||
            normalized.endsWith("v")
        ) {
            return null
        }
        val parsed = parseFirstInt(raw) ?: return null
        return if (parsed in 0..100) parsed else null
    }

    private fun parseVoltageMillivolts(raw: String): Int? {
        val normalized = raw.trim().lowercase()
        val numeric = Regex("-?\\d+(?:\\.\\d+)?")
            .find(normalized)
            ?.value
            ?.toDoubleOrNull()
            ?: return null
        if (numeric <= 0.0) return null

        return when {
            normalized.contains("mv") -> numeric.toInt()
            normalized.contains("volt") ||
                    normalized.contains(" v") ||
                    normalized.endsWith("v") -> (numeric * 1000.0).toInt()
            numeric in 2.5..5.5 -> (numeric * 1000.0).toInt()
            numeric in 1000.0..5000.0 -> numeric.toInt()
            else -> null
        }
    }

    private fun estimateBatteryPercentFromMillivolts(mv: Int): Int {
        val clampedMv = mv.coerceIn(BATTERY_MIN_MV, BATTERY_MAX_MV)
        val range = (BATTERY_MAX_MV - BATTERY_MIN_MV).toDouble()
        if (range <= 0.0) return 0
        val percent = ((clampedMv - BATTERY_MIN_MV).toDouble() / range) * 100.0
        return percent.toInt().coerceIn(0, 100)
    }

    private fun parseBoolean(values: Map<String, String>, vararg keys: String): Boolean? {
        keys.forEach { key ->
            val parsed = values[key]?.let { parseBooleanText(it) }
            if (parsed != null) {
                return parsed
            }
        }
        return null
    }

    private fun parseBooleanText(value: String): Boolean? {
        val normalized = value.trim().lowercase()
        return when {
            normalized == "1" ||
                    normalized == "true" ||
                    normalized == "yes" ||
                    normalized == "charging" ||
                    normalized == "connected" -> true
            normalized == "0" ||
                    normalized == "false" ||
                    normalized == "no" ||
                    normalized == "discharging" ||
                    normalized == "not_charging" ||
                    normalized == "disconnected" -> false
            else -> null
        }
    }

    private fun parseFirstInt(value: String): Int? {
        return Regex("-?\\d+").find(value)?.value?.toIntOrNull()
    }

    private suspend fun <T> withCommandLock(
        operation: String,
        timeoutMs: Long = COMMAND_MUTEX_LOCK_TIMEOUT_MS,
        onTimeout: () -> T,
        block: suspend () -> T
    ): T {
        val result = withTimeoutOrNull(timeoutMs) {
            commandMutex.withLock {
                block()
            }
        }
        if (result != null) {
            return result
        }
        if (operation.isNotBlank()) {
            // Keep operation name in scope for easier timeout diagnostics.
        }
        return onTimeout()
    }

    private fun isRawCollectorActive(): Boolean {
        return rawCliCollector != null || rawBinaryCollector != null
    }

    private fun isLikelyCliText(output: String): Boolean {
        val sanitized = output.replace("\u0000", "").trim()
        if (sanitized.isBlank()) return false
        val sample = sanitized.take(256)
        val printable = sample.count { ch ->
            ch == '\n' || ch == '\r' || ch == '\t' || ch.code in 32..126
        }
        val printableRatio = printable.toDouble() / sample.length.toDouble()
        val hasReadableToken = sample.any { it.isLetterOrDigit() }
        return printableRatio >= CLI_TEXT_PRINTABLE_RATIO_MIN && hasReadableToken
    }

    private fun completePendingRequest(response: ProtocolResponse) {
        val pending = pendingRequest ?: return
        pendingRequest = null
        if (!pending.continuation.isCompleted) {
            pending.continuation.complete(response)
        }
    }

    private fun failPendingRequest(message: String) {
        if (message.contains("failed to write", ignoreCase = true) ||
            message.contains("unavailable", ignoreCase = true)
        ) {
            markCliUnavailable(message)
        }
        val response = ProtocolResponse.Error(message)
        completePendingRequest(response)
    }

    fun onWriteComplete() {
        isWriting.set(false)
    }

    fun onWriteError(status: Int) {
        isWriting.set(false)
        markCliUnavailable("BLE write failed: $status")
        scope.launch {
            _responseFlow.emit(ProtocolResponse.Error("Write failed: $status"))
        }
    }

    companion object {
        const val COMMAND_TIMEOUT_MS = 5_000L
        private const val MAX_FRAME_SIZE = 256 * 1024
        private const val RAW_CLI_TIMEOUT_MS = 3_000L
        private const val RAW_CLI_PROBE_TIMEOUT_MS = 900L
        private const val RAW_CLI_QUIET_PERIOD_MS = 220L
        private const val RAW_BINARY_QUIET_PERIOD_MS = 120L
        private const val CLI_TEXT_PRINTABLE_RATIO_MIN = 0.70
        private const val CLI_PROBE_CACHE_MS = 10_000L
        private const val COMMAND_MUTEX_LOCK_TIMEOUT_MS = 8_000L
        private const val COMMAND_MUTEX_LOCK_RPC_APP_TIMEOUT_MS = 30_000L
        private const val RPC_RESET_RETRY_DELAY_MS = 250L
        private const val RPC_SESSION_START_DELAY_MS = 250L
        private const val RPC_SESSION_STOP_DELAY_MS = 250L
        private const val RPC_SESSION_STOP_GUARD_WINDOW_MS = 5_000L
        private const val RPC_RETRY_COMMAND_TIMEOUT_MS = 4_000L
        private const val RPC_COMMAND_TIMEOUT_MS = 1_500L
        private const val RPC_APP_COMMAND_TIMEOUT_MS = 1_800L
        private const val RPC_APP_START_TIMEOUT_MS = 1_300L
        private const val RPC_APP_LOAD_TIMEOUT_MS = 1_900L
        private const val RPC_APP_BUTTON_TIMEOUT_MS = 1_100L
        private const val RPC_APP_LOCK_TIMEOUT_MS = 650L
        private const val RPC_APP_ERROR_TIMEOUT_MS = 650L
        private const val RPC_APP_FALLBACK_GUI_TIMEOUT_MS = 900L
        private const val RPC_APP_START_SETTLE_DELAY_MS = 90L
        private const val RPC_APP_START_ALT_TIMEOUT_MS = 650L
        private const val RPC_APP_BUTTON_ALT_TIMEOUT_MS = 700L
        private const val RPC_APP_COMMAND_RETRY_DELAY_MS = 120L
        private const val RPC_APP_COMMAND_MAX_RETRIES = 2
        private const val RPC_APP_FAST_REPEAT_WINDOW_MS = 15_000L
        private const val RPC_STORAGE_COMMAND_TIMEOUT_MS = 4_000L
        private const val RPC_STORAGE_WRITE_BASE_TIMEOUT_MS = 8_000L
        private const val RPC_STORAGE_WRITE_PER_KIB_TIMEOUT_MS = 450L
        private const val RPC_STORAGE_WRITE_TIMEOUT_MS = 120_000L
        private const val RPC_STORAGE_WRITE_MAX_ATTEMPTS = 3
        private const val RPC_STORAGE_WRITE_RETRY_DELAY_MS = 300L
        private const val WRITE_CHUNK_SIZE = 512
        private const val WRITE_CHUNK_DELAY_MS = 8L
        private const val RPC_CONTINUATION_COLLECT_WINDOW_MS = 900L
        private const val RPC_REMOTE_INPUT_ACK_TIMEOUT_MS = 900L
        private const val RPC_REMOTE_RELEASE_TIMEOUT_MS = 1_100L
        private const val RPC_REMOTE_LOCK_TIMEOUT_MS = 450L
        private const val RPC_REMOTE_UNLOCK_TIMEOUT_MS = 700L
        private const val RPC_REMOTE_LOCK_CHECK_CACHE_MS = 3_000L
        private const val RPC_REMOTE_FAST_RPC_STATUS_MAX_AGE_MS = 7_500L
        private const val RPC_REMOTE_IMMEDIATE_WRITE_ATTEMPTS = 2
        private const val RPC_REMOTE_IMMEDIATE_RETRY_DELAY_MS = 12L
        private const val RPC_REMOTE_BOOTSTRAP_DELAY_MS = 90L
        private const val RPC_REMOTE_BOOTSTRAP_LOCK_TIMEOUT_MS = 450L
        /** Hard cap on the total time a single CLI/RPC probe sequence can run. */
        private const val CLI_PROBE_TOTAL_CAP_MS = 15_000L
        private const val DESKTOP_LOCK_PROBE_RETRY_MS = 30_000L
        private const val LEGACY_WRITE_BASE_TIMEOUT_MS = 7_500L
        private const val LEGACY_WRITE_PER_KIB_TIMEOUT_MS = 350L
        private const val LEGACY_WRITE_TIMEOUT_MS = 90_000L
        private const val BATTERY_MIN_MV = 3_300
        private const val BATTERY_MAX_MV = 4_200
        private const val RPC_APP_START_ARGUMENT = "RPC"

        private val CLI_PROBE_COMMANDS = listOf(
            "version\r\n",
            "help\r\n",
            "device_info\r\n",
            "info\r\n"
        )

        // Command types
        const val CMD_LIST: Byte = 0x01
        const val CMD_READ: Byte = 0x02
        const val CMD_WRITE: Byte = 0x03
        const val CMD_MKDIR: Byte = 0x04
        const val CMD_DELETE: Byte = 0x05
        const val CMD_MOVE: Byte = 0x06
        const val CMD_INFO: Byte = 0x07
        const val CMD_STORAGE_INFO: Byte = 0x08
        const val CMD_CLI: Byte = 0x10  // CLI passthrough command

        // Response types
        const val RESP_OK: Int = 0x00
        const val RESP_ERROR: Int = 0x01
        const val RESP_LIST: Int = 0x02
        const val RESP_DATA: Int = 0x03
        const val RESP_INFO: Int = 0x04
        const val RESP_CLI: Int = 0x10  // CLI response

        private val COMPATIBILITY_MISS_MARKERS = listOf(
            "unknown command",
            "command not found",
            "not recognized",
            "invalid command",
            "unsupported command",
            "unknown subcommand"
        )

        private val CLI_FAILURE_MARKERS = listOf(
            "unknown command",
            "command not found",
            "not recognized",
            "invalid command",
            "unsupported command",
            "no such file",
            "not found",
            "cannot open",
            "permission denied",
            "error:",
            "failed to"
        )

        private val FATAL_RPC_APP_ERROR_MARKERS = listOf(
            "error",
            "fail",
            "invalid",
            "not found",
            "cannot",
            "can't",
            "denied",
            "unsupported",
            "timeout",
            "locked"
        )
    }

    /**
     * Send a CLI command to the Flipper.
     * This sends commands in a format the Flipper CLI understands.
     * Note: Requires Flipper firmware that supports CLI on the active transport.
     */
    suspend fun sendCliCommand(command: String): ProtocolResponse {
        val normalizedCommand = normalizeCliCommand(command)
        val commandVariants = buildCliCommandVariants(normalizedCommand)

        var lastResponse: ProtocolResponse = ProtocolResponse.Error("No response")
        val attemptMessages = mutableListOf<String>()

        for ((index, variant) in commandVariants.withIndex()) {
            val response = sendSingleCliCommand(variant)
            val normalizedResponse = normalizeCliResponse(variant, response)
            learnFirmwareProfile(normalizedResponse)
            val responseText = extractResponseText(normalizedResponse)
            if (responseText.isNotBlank()) {
                markCliReady(responseText)
            }

            if (index < commandVariants.lastIndex && shouldRetryWithNextVariant(variant, normalizedResponse)) {
                attemptMessages.add("${variant}: ${summarizeResponse(normalizedResponse)}")
                lastResponse = normalizedResponse
                continue
            }

            return normalizedResponse
        }

        val attemptSummary = if (attemptMessages.isNotEmpty()) {
            attemptMessages.joinToString(" | ").take(600)
        } else {
            summarizeResponse(lastResponse)
        }
        if (hasRpcAppCommandMapping(normalizedCommand)) {
            val rpcBridgeResponse = executeRpcAppCommand(normalizedCommand)
            if (rpcBridgeResponse !is ProtocolResponse.Error) {
                return rpcBridgeResponse
            }
            val rpcSummary = summarizeResponse(rpcBridgeResponse)
            return ProtocolResponse.Error(
                "No compatible CLI variant succeeded for \"$normalizedCommand\". " +
                        "CLI: $attemptSummary | RPC bridge: $rpcSummary"
            )
        }
        return ProtocolResponse.Error(
            "No compatible CLI variant succeeded for \"$normalizedCommand\". $attemptSummary"
        )
    }

    fun hasRpcAppCommandMapping(command: String): Boolean {
        return parseRpcCommandPlan(command) != null
    }

    private suspend fun sendSingleCliCommand(command: String): ProtocolResponse {
        return sendRawCliCommand(command)
    }

    private fun normalizeCliCommand(command: String): String {
        return command.trim().replace(Regex("\\s+"), " ")
    }

    private fun buildCliCommandVariants(command: String): List<String> {
        val variants = linkedSetOf<String>()
        fun add(value: String) {
            val normalized = normalizeCliCommand(value)
            if (normalized.isNotEmpty()) variants.add(normalized)
        }

        add(command)
        val lower = command.lowercase()

        when {
            lower == "device_info" -> {
                add("device info")
                add("version")
                add("info")
            }
            lower == "device info" -> {
                add("device_info")
                add("version")
                add("info")
            }
            lower.startsWith("storage list ") -> {
                val tail = command.substringAfter("storage list ").trim()
                add("storage ls $tail")
            }
            lower.startsWith("storage ls ") -> {
                val tail = command.substringAfter("storage ls ").trim()
                add("storage list $tail")
            }
            lower.startsWith("storage read ") -> {
                val tail = command.substringAfter("storage read ").trim()
                add("storage cat $tail")
            }
            lower.startsWith("storage cat ") -> {
                val tail = command.substringAfter("storage cat ").trim()
                add("storage read $tail")
            }
            lower.startsWith("storage copy ") -> {
                val tail = command.substringAfter("storage copy ").trim()
                add("storage cp $tail")
            }
            lower.startsWith("storage cp ") -> {
                val tail = command.substringAfter("storage cp ").trim()
                add("storage copy $tail")
            }
            lower.startsWith("storage remove_recursive ") -> {
                val tail = command.substringAfter("storage remove_recursive ").trim()
                add("storage rm -r $tail")
                add("storage remove $tail -r")
            }
            lower.startsWith("storage rm -r ") -> {
                val tail = command.substringAfter("storage rm -r ").trim()
                add("storage remove_recursive $tail")
            }
            lower.startsWith("storage remove ") -> {
                val tail = command.substringAfter("storage remove ").trim()
                add("storage rm $tail")
            }
            lower.startsWith("storage rm ") -> {
                val tail = command.substringAfter("storage rm ").trim()
                add("storage remove $tail")
            }
            lower.startsWith("loader open ") -> {
                // loader open maps to RPC AppStart — no CLI variants needed,
                // but ensure both casing styles are tried
                val tail = command.substringAfter("loader open ").trim()
                add("loader open $tail")
            }
            lower.startsWith("subghz tx_from_file ") -> {
                val tail = command.substringAfter("subghz tx_from_file ").trim()
                add("subghz tx $tail")
            }
            lower.startsWith("subghz tx ") -> {
                val tail = command.substringAfter("subghz tx ").trim()
                add("subghz tx_from_file $tail")
            }
            lower.startsWith("badusb ") -> {
                val tail = command.substringAfter("badusb ").trim()
                if (tail.startsWith("/")) {
                    add("badusb run $tail")
                    add("badusb start $tail")
                }
            }
            lower.startsWith("badusb run ") -> {
                val tail = command.substringAfter("badusb run ").trim()
                add("badusb $tail")
                add("badusb start $tail")
            }
            lower.startsWith("badusb start ") -> {
                val tail = command.substringAfter("badusb start ").trim()
                add("badusb $tail")
                add("badusb run $tail")
            }
            lower.startsWith("ir tx ") -> {
                val tail = command.substringAfter("ir tx ").trim()
                add("infrared tx $tail")
            }
            lower.startsWith("infrared tx ") -> {
                val tail = command.substringAfter("infrared tx ").trim()
                add("ir tx $tail")
            }
            lower == "ble_spam" ||
                    lower == "blespam" ||
                    lower == "ble spam" ||
                    lower.startsWith("ble_spam ") ||
                    lower.startsWith("blespam ") ||
                    lower.startsWith("ble spam ") -> {
                val tail = when {
                    lower.startsWith("ble_spam ") -> command.substringAfter("ble_spam ").trim()
                    lower.startsWith("blespam ") -> command.substringAfter("blespam ").trim()
                    lower.startsWith("ble spam ") -> command.substringAfter("ble spam ").trim()
                    else -> ""
                }
                val suffix = if (tail.isBlank()) "" else " $tail"
                add("ble_spam$suffix")
                add("blespam$suffix")
                add("ble spam$suffix")
                if (tail == "--stop") {
                    add("ble_spam stop")
                    add("blespam stop")
                    add("ble spam stop")
                }
                if (tail == "stop") {
                    add("ble_spam --stop")
                    add("blespam --stop")
                    add("ble spam --stop")
                }
            }
            lower == "ble_scan" ||
                    lower == "blescan" ||
                    lower == "ble scan" ||
                    lower.startsWith("ble_scan ") ||
                    lower.startsWith("blescan ") ||
                    lower.startsWith("ble scan ") -> {
                val tail = when {
                    lower.startsWith("ble_scan ") -> command.substringAfter("ble_scan ").trim()
                    lower.startsWith("blescan ") -> command.substringAfter("blescan ").trim()
                    lower.startsWith("ble scan ") -> command.substringAfter("ble scan ").trim()
                    else -> ""
                }
                val suffix = if (tail.isBlank()) "" else " $tail"
                add("ble_scan$suffix")
                add("blescan$suffix")
                add("ble scan$suffix")
            }
        }

        // Momentum commonly accepts "badusb run <path>" even when other variants differ.
        if (firmwareProfile == FirmwareProfile.MOMENTUM) {
            val runVariant = variants.firstOrNull {
                it.lowercase().startsWith("badusb run ")
            }
            if (runVariant != null) {
                variants.remove(runVariant)
                variants.add(runVariant)
                return listOf(runVariant) + variants.toList().filter { it != runVariant }
            }
        }

        return variants.toList()
    }

    private fun shouldRetryWithNextVariant(command: String, response: ProtocolResponse): Boolean {
        val responseText = extractResponseText(response).lowercase()
        if (responseText.isBlank()) return false

        if (COMPATIBILITY_MISS_MARKERS.any { responseText.contains(it) }) {
            return true
        }

        if (responseText.contains("no cli response") ||
            responseText.contains("cannot be confirmed") ||
            responseText.contains("no response from flipper")
        ) {
            return true
        }

        // Some firmwares reply with usage text when the command exists but syntax differs.
        val commandLower = command.lowercase()
        if (responseText.contains("usage")) {
            return commandLower.startsWith("badusb ") ||
                    commandLower.startsWith("subghz tx") ||
                    commandLower.startsWith("storage ")
        }
        return false
    }

    /**
     * Send raw CLI text directly (for simple CLI interaction)
     * This bypasses the protocol framing for direct serial passthrough
     */
    suspend fun sendRawCli(text: String): Boolean {
        return withCommandLock(
            operation = "raw CLI text send",
            onTimeout = { false }
        ) {
            val service = bleService ?: run {
                markCliUnavailable("Device service unavailable")
                return@withCommandLock false
            }
            if (!ensureTransportConnected(service)) {
                markCliUnavailable("Flipper command transport is not connected")
                return@withCommandLock false
            }
            val data = "$text\r\n".toByteArray(Charsets.UTF_8)
            val sent = service.sendData(data)
            if (!sent) {
                markCliUnavailable("Failed to send CLI data over active transport")
            }
            sent
        }
    }

    suspend fun probeCliAvailability(force: Boolean = false): CliCapabilityStatus {
        // Hard cap: never let the entire probe sequence exceed CLI_PROBE_TOTAL_CAP_MS.
        return withTimeoutOrNull(CLI_PROBE_TOTAL_CAP_MS) {
            probeCliAvailabilityInner(force)
        } ?: markProbeDeferred(
            "CLI capability probe timed out after ${CLI_PROBE_TOTAL_CAP_MS / 1000}s. " +
                    "Run diagnostics manually if needed."
        )
    }

    private suspend fun probeCliAvailabilityInner(force: Boolean): CliCapabilityStatus = withCommandLock(
        operation = "CLI capability probe",
        onTimeout = {
            markProbeDeferred("CLI capability probe deferred; command pipeline is busy.")
        }
    ) {
        val service = bleService ?: return@withCommandLock markCliUnavailable("Device service unavailable")
        if (!ensureTransportConnected(service)) {
            return@withCommandLock markCliUnavailable("Flipper command transport is not connected")
        }

        val now = System.currentTimeMillis()
        val current = _cliStatus.value
        if (!force &&
            current.level != CliCapabilityLevel.UNKNOWN &&
            now - lastCliProbeAtMs < CLI_PROBE_CACHE_MS
        ) {
            return@withCommandLock current
        }

        val response = probeRawCliOutput()

        return@withCommandLock if (response.isNotBlank()) {
            markCliReady(response)
        } else {
            val rpcStatus = probeRpcTransportAvailability()
            if (rpcStatus != null) {
                recoverCliFromRpcSessionLocked()
            } else {
                val restarted = service.restartSerialRpc()
                if (restarted) {
                    delay(RPC_RESET_RETRY_DELAY_MS)
                    val retryCliResponse = probeRawCliOutput()
                    if (retryCliResponse.isNotBlank()) {
                        markCliReady(retryCliResponse)
                    } else {
                        val recovered = probeRpcTransportAvailability(
                            detail = "RPC ping responded after serial reset (CLI unavailable on this transport)"
                        )?.let {
                            recoverCliFromRpcSessionLocked()
                        }
                        recovered ?: markCliUnavailable(
                            "No response to CLI/RPC probe commands after serial reset. " +
                                    "Re-pair BLE and close any competing Flipper app sessions."
                        )
                    }
                } else {
                    markCliUnavailable(
                        "No response to CLI probe commands. " +
                                "This firmware/connection likely requires RPC (not raw CLI) on this transport."
                    )
                }
            }
        }
    }

    suspend fun probeRpcAvailability(
        detail: String = "RPC ping responded during diagnostics"
    ): CliCapabilityStatus = withCommandLock(
        operation = "RPC capability probe",
        onTimeout = {
            markProbeDeferred("RPC capability probe deferred; command pipeline is busy.")
        }
    ) {
        val service = bleService ?: return@withCommandLock markCliUnavailable("Device service unavailable")
        if (!ensureTransportConnected(service)) {
            return@withCommandLock markCliUnavailable("Flipper command transport is not connected")
        }

        val rpcStatus = probeRpcTransportAvailability(detail)
        if (rpcStatus != null) {
            return@withCommandLock rpcStatus
        }

        val current = _cliStatus.value
        val now = System.currentTimeMillis()
        val updated = current.copy(
            level = if (current.level == CliCapabilityLevel.UNKNOWN) {
                CliCapabilityLevel.UNAVAILABLE
            } else {
                current.level
            },
            checkedAtMs = now,
            supportsRpc = false,
            details = when {
                current.details.isBlank() -> "RPC ping did not respond."
                current.details.contains("RPC ping did not respond", ignoreCase = true) -> current.details
                else -> "${current.details} RPC ping did not respond."
            }
        )
        _cliStatus.value = updated
        lastCliProbeAtMs = updated.checkedAtMs
        return@withCommandLock updated
    }

    suspend fun recoverCliFromRpcSession(): CliCapabilityStatus = withCommandLock(
        operation = "CLI recovery from RPC session",
        onTimeout = {
            markRpcReady("Command pipeline busy; skipped stop_rpc_session to protect active RPC traffic.")
        }
    ) {
        recoverCliFromRpcSessionLocked()
    }

    private suspend fun recoverCliFromRpcSessionLocked(): CliCapabilityStatus {
        val service = bleService ?: return markCliUnavailable("Device service unavailable")
        if (!ensureTransportConnected(service)) {
            return markCliUnavailable("Flipper command transport is not connected")
        }

        val current = _cliStatus.value
        if (current.level == CliCapabilityLevel.READY && current.supportsCli) {
            return current
        }

        if (current.level != CliCapabilityLevel.READY || !current.supportsRpc) {
            probeRpcTransportAvailability() ?: return current
        }

        val nowMs = System.currentTimeMillis()
        if (current.supportsRpc &&
            !current.supportsCli &&
            (nowMs - lastRpcActivityAtMs) in 0..RPC_SESSION_STOP_GUARD_WINDOW_MS
        ) {
            return markRpcReady(
                "RPC is active; skipping stop_rpc_session to avoid interrupting in-flight automation."
            )
        }

        val stopped = tryStopRpcSession()
        if (!stopped) {
            return markRpcReady(
                "RPC responded, but stop_rpc_session could not be requested. CLI remains unavailable."
            )
        }

        delay(RPC_SESSION_STOP_DELAY_MS)
        val cliResponse = probeRawCliOutput()
        return if (cliResponse.isNotBlank()) {
            markCliReady(cliResponse)
        } else {
            markRpcReady(
                "RPC responded and stop_rpc_session was sent, but CLI is still unavailable. " +
                        "Reconnect Flipper and close competing BLE sessions."
            )
        }
    }

    private suspend fun probeRawCliOutput(): String {
        for (probe in CLI_PROBE_COMMANDS) {
            val output = collectRawCliResponse(probe, RAW_CLI_PROBE_TIMEOUT_MS).trim()
            if (output.isNotBlank() && isLikelyCliText(output)) {
                return output
            }
        }
        return ""
    }

    suspend fun executeRpcAppCommand(command: String): ProtocolResponse = withCommandLock(
        operation = "RPC app command",
        timeoutMs = COMMAND_MUTEX_LOCK_RPC_APP_TIMEOUT_MS,
        onTimeout = {
            ProtocolResponse.Error("Command pipeline busy; RPC app command timed out waiting for lock: $command")
        }
    ) {
        val service = bleService
            ?: return@withCommandLock ProtocolResponse.Error("Flipper device service unavailable").also {
                markCliUnavailable("Device service unavailable")
            }
        if (!ensureTransportConnected(service)) {
            return@withCommandLock ProtocolResponse.Error("Flipper command transport is not connected").also {
                markCliUnavailable("Flipper command transport is not connected")
            }
        }

        val rpcReady = if (_cliStatus.value.supportsRpc) {
            _cliStatus.value
        } else {
            probeRpcTransportAvailability(
                detail = "RPC ping responded for app command bridge"
            ) ?: return@withCommandLock ProtocolResponse.Error(
                "RPC transport is unavailable, so app command bridge cannot execute: $command"
            )
        }
        if (!rpcReady.supportsRpc) {
            return@withCommandLock ProtocolResponse.Error("RPC transport unavailable for app command bridge")
        }

        val plan = parseRpcCommandPlan(command)
            ?: return@withCommandLock ProtocolResponse.Error(
                "No RPC action mapping for command: $command"
            )
        val appStartCacheKey = buildRpcAppStartCacheKey(plan.appCandidates)
        val buttonCacheKey = buildRpcButtonCacheKey(plan)
        val executionKey = buildRpcExecutionCacheKey(plan, appStartCacheKey)
        val executionSnapshot = lastRpcExecutionSnapshot
        val now = System.currentTimeMillis()

        if (plan.triggerOkPress &&
            !buttonCacheKey.isNullOrBlank() &&
            !executionKey.isNullOrBlank() &&
            executionSnapshot?.executionKey == executionKey &&
            now - executionSnapshot.completedAtMs <= RPC_APP_FAST_REPEAT_WINDOW_MS
        ) {
            val fastStatus = sendAppButtonConfirmEventLocked(
                buttonArgsCandidates = plan.buttonArgsCandidates,
                cacheKey = buttonCacheKey,
                timeoutMs = RPC_APP_BUTTON_ALT_TIMEOUT_MS
            )
            if (fastStatus == Flipper.CommandStatus.OK) {
                val details = "RPC app bridge executed (fast repeat): $command"
                markRpcReady(details)
                lastRpcExecutionSnapshot = RpcExecutionSnapshot(
                    executionKey = executionKey,
                    completedAtMs = now
                )
                return@withCommandLock ProtocolResponse.Success(details)
            }
        }

        val startStatus = if (!plan.appCandidates.isNullOrEmpty()) {
            sendAppStartWithCandidates(
                candidates = plan.appCandidates,
                args = plan.appArgs,
                cacheKey = appStartCacheKey,
                timeoutMs = RPC_APP_START_TIMEOUT_MS
            )
        } else {
            Flipper.CommandStatus.OK
        }
        val appLocked = if (startStatus == Flipper.CommandStatus.ERROR_APP_SYSTEM_LOCKED) {
            requestAppLockStatusLocked(timeoutMs = RPC_APP_LOCK_TIMEOUT_MS)
        } else {
            null
        }
        val proceededWithLockedApp = startStatus == Flipper.CommandStatus.ERROR_APP_SYSTEM_LOCKED &&
            appLocked == true
        if (startStatus != Flipper.CommandStatus.OK &&
            startStatus != Flipper.CommandStatus.ERROR_APP_SYSTEM_LOCKED
        ) {
            val appErrorSuffix = formatAppErrorSuffix(
                requestAppErrorInfoLocked(timeoutMs = RPC_APP_ERROR_TIMEOUT_MS)
            )
            return@withCommandLock ProtocolResponse.Error(
                "RPC app start failed for \"$command\": ${startStatus.name}$appErrorSuffix"
            )
        }
        if (startStatus == Flipper.CommandStatus.ERROR_APP_SYSTEM_LOCKED &&
            plan.filePath.isNullOrBlank() &&
            !proceededWithLockedApp
        ) {
            val lockHint = if (appLocked == true) {
                "Another app is currently running on the Flipper."
            } else {
                "Unlock/open the target app on Flipper."
            }
            return@withCommandLock ProtocolResponse.Error(
                "RPC app start is system-locked for \"$command\". $lockHint"
            )
        }
        if (startStatus == Flipper.CommandStatus.OK) {
            delay(RPC_APP_START_SETTLE_DELAY_MS)
        }

        if (!plan.filePath.isNullOrBlank()) {
            val loadStatus = sendAppLoadFileWithRetry(
                path = plan.filePath,
                timeoutMs = RPC_APP_LOAD_TIMEOUT_MS
            )
            if (loadStatus != Flipper.CommandStatus.OK) {
                val appErrorSuffix = formatAppErrorSuffix(
                    requestAppErrorInfoLocked(timeoutMs = RPC_APP_ERROR_TIMEOUT_MS)
                )
                return@withCommandLock ProtocolResponse.Error(
                    "RPC app_load_file failed for ${plan.filePath}: ${loadStatus.name}$appErrorSuffix"
                )
            }
            delay(RPC_APP_START_SETTLE_DELAY_MS)
        }

        if (plan.triggerOkPress) {
            val buttonStatus = sendAppButtonConfirmEventLocked(
                buttonArgsCandidates = plan.buttonArgsCandidates,
                cacheKey = buttonCacheKey,
                timeoutMs = RPC_APP_BUTTON_TIMEOUT_MS
            )
            if (buttonStatus != Flipper.CommandStatus.OK) {
                val inputResponse = sendRpcMainAndAwaitResponse(timeoutMs = RPC_APP_FALLBACK_GUI_TIMEOUT_MS) {
                    setGuiSendInputEventRequest(
                        Gui.SendInputEventRequest.newBuilder()
                            .setKey(Gui.InputKey.OK)
                            .setType(Gui.InputType.SHORT)
                            .build()
                    )
                } ?: return@withCommandLock ProtocolResponse.Error(
                    "No RPC response for app button or GUI input event request"
                )

                if (inputResponse.commandStatus != Flipper.CommandStatus.OK) {
                    val appErrorSuffix = formatAppErrorSuffix(
                        requestAppErrorInfoLocked(timeoutMs = RPC_APP_ERROR_TIMEOUT_MS)
                    )
                    return@withCommandLock ProtocolResponse.Error(
                        "RPC input failed for \"$command\": app_button=${buttonStatus.name}, gui=${inputResponse.commandStatus.name}$appErrorSuffix"
                    )
                }
            }
        }
        val details = if (proceededWithLockedApp) {
            "RPC app bridge executed with locked-app fallback: $command"
        } else {
            "RPC app bridge executed: $command"
        }
        markRpcReady(details)
        if (!executionKey.isNullOrBlank()) {
            lastRpcExecutionSnapshot = RpcExecutionSnapshot(
                executionKey = executionKey,
                completedAtMs = System.currentTimeMillis()
            )
        }
        return@withCommandLock ProtocolResponse.Success(details)
    }

    /**
     * Send a direct GUI button/input event via RPC.
     * Mirrors official remote-control style input (UP/DOWN/LEFT/RIGHT/OK/BACK).
     */
    suspend fun sendGuiInputEvent(
        key: Gui.InputKey,
        inputType: Gui.InputType = Gui.InputType.SHORT
    ): ProtocolResponse = withCommandLock(
        operation = "direct GUI input",
        onTimeout = {
            ProtocolResponse.Error(
                "Command pipeline busy; GUI input timed out waiting for lock (${key.name}/${inputType.name})"
            )
        }
    ) {
        val service = bleService
            ?: return@withCommandLock ProtocolResponse.Error("Flipper device service unavailable").also {
                markCliUnavailable("Device service unavailable")
            }
        if (!ensureTransportConnected(service)) {
            return@withCommandLock ProtocolResponse.Error("Flipper command transport is not connected").also {
                markCliUnavailable("Flipper command transport is not connected")
            }
        }
        if (!ensureRpcAvailableLocked("RPC ping responded for direct GUI input request")) {
            val bootstrapFallback = trySendGuiInputViaRpcBootstrapLocked(
                service = service,
                key = key,
                inputType = inputType
            )
            if (bootstrapFallback != null) {
                return@withCommandLock bootstrapFallback
            }
            return@withCommandLock ProtocolResponse.Error(
                "RPC transport is unavailable for direct GUI input events."
            )
        }

        val unlockDetails = if (shouldProbeDesktopLockForRemoteInput(System.currentTimeMillis())) {
            maybeUnlockDesktopIfNeededLocked(
                lockProbeTimeoutMs = RPC_REMOTE_LOCK_TIMEOUT_MS,
                unlockTimeoutMs = RPC_REMOTE_UNLOCK_TIMEOUT_MS
            )
        } else {
            null
        }

        val canUsePressReleaseFlow = when (inputType) {
            Gui.InputType.SHORT,
            Gui.InputType.LONG,
            Gui.InputType.REPEAT -> true
            else -> false
        }
        if (canUsePressReleaseFlow) {
            val pressSent = sendGuiInputEventFireAndForgetLocked(key, Gui.InputType.PRESS)
            val actionSent = pressSent && sendGuiInputEventFireAndForgetLocked(key, inputType)
            if (actionSent) {
                val releaseSent = sendGuiInputEventFireAndForgetLocked(key, Gui.InputType.RELEASE)
                if (releaseSent) {
                    val details = buildString {
                        append("GUI input sent: ")
                        append(key.name)
                        append('/')
                        append(inputType.name)
                        append(" (fast press/release no-ack)")
                        if (!unlockDetails.isNullOrBlank()) {
                            append("; ")
                            append(unlockDetails)
                        }
                    }
                    markRpcReady(details)
                    return@withCommandLock ProtocolResponse.Success(details)
                }

                val releaseStatus = sendGuiInputEventStatusLocked(
                    key = key,
                    inputType = Gui.InputType.RELEASE,
                    timeoutMs = RPC_REMOTE_RELEASE_TIMEOUT_MS
                )
                if (releaseStatus == Flipper.CommandStatus.OK ||
                    releaseStatus == Flipper.CommandStatus.ERROR_NOT_IMPLEMENTED
                ) {
                    val details = buildString {
                        append("GUI input sent: ")
                        append(key.name)
                        append('/')
                        append(inputType.name)
                        append(" (fast press/release)")
                        if (!unlockDetails.isNullOrBlank()) {
                            append("; ")
                            append(unlockDetails)
                        }
                    }
                    markRpcReady(details)
                    return@withCommandLock ProtocolResponse.Success(details)
                }
            }
        }

        // Fallback path: try a single event with a small timeout for quick UI response.
        val response = sendRpcMainAndAwaitResponse(timeoutMs = RPC_REMOTE_INPUT_ACK_TIMEOUT_MS) {
            setGuiSendInputEventRequest(
                Gui.SendInputEventRequest.newBuilder()
                    .setKey(key)
                    .setType(inputType)
                    .build()
            )
        } ?: return@withCommandLock ProtocolResponse.Error(
            "No RPC response for GUI input event (${key.name}/${inputType.name}). Input not confirmed."
        )

        if (response.commandStatus != Flipper.CommandStatus.OK) {
            val appErrorSuffix = formatAppErrorSuffix(
                requestAppErrorInfoLocked(timeoutMs = RPC_APP_ERROR_TIMEOUT_MS)
            )
            return@withCommandLock ProtocolResponse.Error(
                "GUI input failed (${key.name}/${inputType.name}): ${response.commandStatus.name}$appErrorSuffix",
                response.commandStatus.number
            )
        }

        val details = buildString {
            append("GUI input sent: ")
            append(key.name)
            append('/')
            append(inputType.name)
            append(" (single-event acked)")
            if (!unlockDetails.isNullOrBlank()) {
                append("; ")
                append(unlockDetails)
            }
        }
        markRpcReady(details)
        return@withCommandLock ProtocolResponse.Success(details)
    }

    /**
     * Low-latency remote input path.
     * Sends GUI input immediately without waiting for protocol mutex/acks, then
     * falls back to the strict RPC path when immediate delivery fails.
     */
    suspend fun sendGuiInputEventImmediate(
        key: Gui.InputKey,
        inputType: Gui.InputType = Gui.InputType.SHORT
    ): ProtocolResponse {
        val service = bleService
            ?: return ProtocolResponse.Error("Flipper device service unavailable").also {
                markCliUnavailable("Device service unavailable")
            }
        if (!ensureTransportConnected(service)) {
            return ProtocolResponse.Error("Flipper command transport is not connected").also {
                markCliUnavailable("Flipper command transport is not connected")
            }
        }
        // Avoid interleaving immediate input packets with active raw collectors.
        if (isRawCollectorActive()) {
            return sendGuiInputEvent(key, inputType)
        }
        val nowMs = System.currentTimeMillis()
        val fastPathReady = isRemoteFastPathReady(nowMs)
        if (!fastPathReady) {
            val bootstrapResponse = withTimeoutOrNull(RPC_REMOTE_BOOTSTRAP_LOCK_TIMEOUT_MS) {
                commandMutex.withLock {
                    trySendGuiInputViaRpcBootstrapLocked(service, key, inputType)
                }
            }
            if (bootstrapResponse != null) {
                return bootstrapResponse
            }
            // Avoid false-positive success when RPC session is not ready yet.
            return sendGuiInputEvent(key, inputType)
        }

        val immediateSingleSent = sendGuiInputEventPacketImmediate(service, key, inputType)
        val immediateSent = if (immediateSingleSent) {
            true
        } else {
            when (inputType) {
                Gui.InputType.SHORT,
                Gui.InputType.LONG,
                Gui.InputType.REPEAT -> {
                    val pressSent = sendGuiInputEventPacketImmediate(service, key, Gui.InputType.PRESS)
                    val actionSent = pressSent && sendGuiInputEventPacketImmediate(service, key, inputType)
                    val releaseSent = actionSent && sendGuiInputEventPacketImmediate(service, key, Gui.InputType.RELEASE)
                    actionSent && releaseSent
                }
                else -> false
            }
        }
        val immediateFailureReason = if (immediateSent) {
            null
        } else {
            service.consumeLastWriteFailureReason()
        }

        if (immediateSent) {
            if (!fastPathReady) {
                scheduleRemoteInputHealthRefresh()
            }
            return ProtocolResponse.Success(
                if (immediateSingleSent) {
                    "GUI input sent: ${key.name}/${inputType.name} (immediate single-event)"
                } else {
                    "GUI input sent: ${key.name}/${inputType.name} (immediate press/release)"
                }
            )
        }

        // Preserve reliability: if immediate path misses, run strict RPC path.
        val strictResponse = sendGuiInputEvent(key, inputType)
        if (strictResponse is ProtocolResponse.Error && !immediateFailureReason.isNullOrBlank()) {
            return ProtocolResponse.Error(
                "${strictResponse.message}. Immediate write path failed: $immediateFailureReason",
                strictResponse.code
            )
        }
        return strictResponse
    }

    private fun scheduleRemoteInputHealthRefresh() {
        if (!remoteHealthRefreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                commandMutex.withLock {
                    val rpcReady = ensureRpcAvailableLocked(
                        "RPC ping refreshed after immediate remote input"
                    )
                    if (rpcReady && shouldProbeDesktopLockForRemoteInput(System.currentTimeMillis())) {
                        maybeUnlockDesktopIfNeededLocked(
                            lockProbeTimeoutMs = RPC_REMOTE_LOCK_TIMEOUT_MS,
                            unlockTimeoutMs = RPC_REMOTE_UNLOCK_TIMEOUT_MS
                        )
                    }
                }
            } finally {
                remoteHealthRefreshInFlight.set(false)
            }
        }
    }

    private suspend fun trySendGuiInputViaRpcBootstrapLocked(
        service: FlipperBleService,
        key: Gui.InputKey,
        inputType: Gui.InputType
    ): ProtocolResponse? {
        val started = tryStartRpcSession()
        if (!started) return null

        delay(RPC_REMOTE_BOOTSTRAP_DELAY_MS)

        val directSent = sendGuiInputEventPacketImmediate(service, key, inputType)
        val sent = if (directSent) {
            true
        } else {
            when (inputType) {
                Gui.InputType.SHORT,
                Gui.InputType.LONG,
                Gui.InputType.REPEAT -> {
                    val pressSent = sendGuiInputEventPacketImmediate(service, key, Gui.InputType.PRESS)
                    val actionSent = pressSent && sendGuiInputEventPacketImmediate(service, key, inputType)
                    val releaseSent = actionSent && sendGuiInputEventPacketImmediate(service, key, Gui.InputType.RELEASE)
                    actionSent && releaseSent
                }
                else -> false
            }
        }
        if (!sent) return null

        markRpcReady("RPC session primed via start_rpc_session for GUI input")
        return ProtocolResponse.Success(
            "GUI input sent: ${key.name}/${inputType.name} (RPC bootstrap fallback)"
        )
    }

    private suspend fun sendGuiInputEventStatusLocked(
        key: Gui.InputKey,
        inputType: Gui.InputType,
        timeoutMs: Long = RPC_APP_COMMAND_TIMEOUT_MS
    ): Flipper.CommandStatus? {
        val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
            setGuiSendInputEventRequest(
                Gui.SendInputEventRequest.newBuilder()
                    .setKey(key)
                    .setType(inputType)
                    .build()
            )
        } ?: return null
        return response.commandStatus
    }

    private suspend fun sendGuiInputEventFireAndForgetLocked(
        key: Gui.InputKey,
        inputType: Gui.InputType
    ): Boolean {
        val service = bleService ?: return false
        if (!ensureTransportConnected(service)) return false
        val packet = buildRpcMainPacket(nextRpcProbeCommandId()) {
            setGuiSendInputEventRequest(
                Gui.SendInputEventRequest.newBuilder()
                    .setKey(key)
                    .setType(inputType)
                    .build()
                )
        }
        val sent = service.sendData(
            packet,
            preferNoResponse = true,
            ignoreOverflowBudget = false
        )
        if (sent) {
            lastRpcActivityAtMs = System.currentTimeMillis()
        }
        return sent
    }

    private suspend fun sendGuiInputEventPacketImmediate(
        service: FlipperBleService,
        key: Gui.InputKey,
        inputType: Gui.InputType
    ): Boolean {
        val packet = buildRpcMainPacket(nextImmediateRpcCommandId()) {
            setGuiSendInputEventRequest(
                Gui.SendInputEventRequest.newBuilder()
                    .setKey(key)
                    .setType(inputType)
                    .build()
            )
        }
        repeat(RPC_REMOTE_IMMEDIATE_WRITE_ATTEMPTS) { attempt ->
            if (service.sendData(
                    packet,
                    preferNoResponse = true,
                    ignoreOverflowBudget = false
                )
            ) {
                lastRpcActivityAtMs = System.currentTimeMillis()
                return true
            }
            if (attempt < RPC_REMOTE_IMMEDIATE_WRITE_ATTEMPTS - 1) {
                delay(RPC_REMOTE_IMMEDIATE_RETRY_DELAY_MS)
            }
        }
        val sent = service.sendData(
            packet,
            preferNoResponse = false,
            ignoreOverflowBudget = false
        )
        if (sent) {
            lastRpcActivityAtMs = System.currentTimeMillis()
        }
        return sent
    }

    private fun isRemoteFastPathReady(nowMs: Long): Boolean {
        val status = _cliStatus.value
        if (status.level != CliCapabilityLevel.READY || !status.supportsRpc) {
            return false
        }
        val ageMs = nowMs - status.checkedAtMs
        return ageMs <= RPC_REMOTE_FAST_RPC_STATUS_MAX_AGE_MS
    }

    private fun shouldProbeDesktopLockForRemoteInput(nowMs: Long): Boolean {
        if (desktopLockProbeSupported == false) {
            return nowMs - lastDesktopLockProbeAtMs >= DESKTOP_LOCK_PROBE_RETRY_MS
        }
        return nowMs - lastDesktopLockProbeAtMs >= RPC_REMOTE_LOCK_CHECK_CACHE_MS
    }

    private suspend fun maybeUnlockDesktopIfNeededLocked(
        lockProbeTimeoutMs: Long = RPC_COMMAND_TIMEOUT_MS,
        unlockTimeoutMs: Long = RPC_COMMAND_TIMEOUT_MS
    ): String? {
        val now = System.currentTimeMillis()
        if (desktopLockProbeSupported == false &&
            now - lastDesktopLockProbeAtMs < DESKTOP_LOCK_PROBE_RETRY_MS
        ) {
            return null
        }

        val isLocked = requestDesktopLockStatusLocked(timeoutMs = lockProbeTimeoutMs) ?: run {
            desktopLockProbeSupported = false
            lastDesktopLockProbeAtMs = now
            return null
        }
        desktopLockProbeSupported = true
        lastDesktopLockProbeAtMs = now
        if (!isLocked) return null

        val unlockStatus = sendDesktopUnlockStatusLocked(timeoutMs = unlockTimeoutMs)
        return if (unlockStatus == Flipper.CommandStatus.OK) {
            "desktop unlocked"
        } else {
            "desktop unlock failed: ${unlockStatus.name}"
        }
    }

    private suspend fun requestDesktopLockStatusLocked(
        timeoutMs: Long = RPC_COMMAND_TIMEOUT_MS
    ): Boolean? {
        val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
            setDesktopIsLockedRequest(Desktop.IsLockedRequest.getDefaultInstance())
        } ?: return null
        if (response.commandStatus != Flipper.CommandStatus.OK) {
            return null
        }
        return if (response.hasDesktopStatus()) {
            response.desktopStatus.locked
        } else {
            null
        }
    }

    private suspend fun sendDesktopUnlockStatusLocked(
        timeoutMs: Long = RPC_COMMAND_TIMEOUT_MS
    ): Flipper.CommandStatus {
        val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
            setDesktopUnlockRequest(Desktop.UnlockRequest.getDefaultInstance())
        } ?: return Flipper.CommandStatus.ERROR
        return response.commandStatus
    }

    private suspend fun sendAppStartWithCandidates(
        candidates: List<String>,
        args: String,
        cacheKey: String? = null,
        timeoutMs: Long = RPC_APP_START_TIMEOUT_MS
    ): Flipper.CommandStatus {
        val orderedCandidates = reorderCandidatesWithCache(candidates, cacheKey, rpcStartCandidateCache)
        var lastStatus = Flipper.CommandStatus.ERROR_APP_CANT_START
        for ((index, candidate) in orderedCandidates.withIndex()) {
            val candidateTimeoutMs = if (index == 0) timeoutMs else RPC_APP_START_ALT_TIMEOUT_MS
            val response = sendRpcMainAndAwaitResponse(timeoutMs = candidateTimeoutMs) {
                setAppStartRequest(
                    Application.StartRequest.newBuilder()
                        .setName(candidate)
                        .setArgs(args)
                        .build()
                )
            }
            if (response == null) {
                lastStatus = Flipper.CommandStatus.ERROR
                continue
            }

            if (response.commandStatus == Flipper.CommandStatus.OK ||
                response.commandStatus == Flipper.CommandStatus.ERROR_APP_SYSTEM_LOCKED
            ) {
                if (!cacheKey.isNullOrBlank()) {
                    rpcStartCandidateCache[cacheKey] = candidate
                }
                return response.commandStatus
            }
            lastStatus = response.commandStatus
        }
        return lastStatus
    }

    private suspend fun sendAppLoadFileWithRetry(
        path: String,
        timeoutMs: Long = RPC_APP_LOAD_TIMEOUT_MS
    ): Flipper.CommandStatus {
        var lastStatus = Flipper.CommandStatus.ERROR
        repeat(RPC_APP_COMMAND_MAX_RETRIES) { attempt ->
            val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
                setAppLoadFileRequest(
                    Application.AppLoadFileRequest.newBuilder()
                        .setPath(path)
                        .build()
                )
            }
            val status = response?.commandStatus ?: Flipper.CommandStatus.ERROR
            if (status == Flipper.CommandStatus.OK) {
                return Flipper.CommandStatus.OK
            }
            lastStatus = status
            if (!isRetryableAppCommandStatus(status) || attempt == RPC_APP_COMMAND_MAX_RETRIES - 1) {
                return status
            }
            delay(RPC_APP_COMMAND_RETRY_DELAY_MS * (attempt + 1))
        }
        return lastStatus
    }

    private suspend fun sendAppButtonConfirmEventLocked(
        buttonArgsCandidates: List<String>,
        cacheKey: String? = null,
        timeoutMs: Long = RPC_APP_BUTTON_TIMEOUT_MS
    ): Flipper.CommandStatus {
        var lastStatus = Flipper.CommandStatus.ERROR_APP_CMD_ERROR
        repeat(RPC_APP_COMMAND_MAX_RETRIES) { attempt ->
            val status = sendAppButtonConfirmEventAttemptLocked(
                buttonArgsCandidates = buttonArgsCandidates,
                cacheKey = cacheKey,
                timeoutMs = timeoutMs
            )
            if (status == Flipper.CommandStatus.OK) {
                return Flipper.CommandStatus.OK
            }
            lastStatus = status
            if (!isRetryableAppCommandStatus(status) || attempt == RPC_APP_COMMAND_MAX_RETRIES - 1) {
                return status
            }
            delay(RPC_APP_COMMAND_RETRY_DELAY_MS * (attempt + 1))
        }
        return lastStatus
    }

    private suspend fun sendAppButtonConfirmEventAttemptLocked(
        buttonArgsCandidates: List<String>,
        cacheKey: String? = null,
        timeoutMs: Long = RPC_APP_BUTTON_TIMEOUT_MS
    ): Flipper.CommandStatus {
        val normalizedCandidates = buttonArgsCandidates
            .map { it.trim() }
            .distinct()
            .ifEmpty { listOf("OK", "ok", "") }
        val orderedCandidates = reorderCandidatesWithCache(normalizedCandidates, cacheKey, rpcButtonArgCache)

        for ((index, candidate) in orderedCandidates.withIndex()) {
            val candidateTimeoutMs = if (index == 0) timeoutMs else RPC_APP_BUTTON_ALT_TIMEOUT_MS
            val pressReleaseStatus = sendAppButtonPressReleaseStatusLocked(
                args = candidate,
                timeoutMs = candidateTimeoutMs
            )
            if (pressReleaseStatus == Flipper.CommandStatus.OK) {
                if (!cacheKey.isNullOrBlank()) {
                    rpcButtonArgCache[cacheKey] = candidate
                }
                return Flipper.CommandStatus.OK
            }

            // Some firmwares require explicit press + release instead of combined press_release.
            val pressStatus = sendAppButtonPressStatusLocked(
                args = candidate,
                timeoutMs = candidateTimeoutMs
            )
            if (pressStatus == Flipper.CommandStatus.OK) {
                val releaseStatus = sendAppButtonReleaseStatusLocked(timeoutMs = candidateTimeoutMs)
                if (releaseStatus == Flipper.CommandStatus.OK) {
                    if (!cacheKey.isNullOrBlank()) {
                        rpcButtonArgCache[cacheKey] = candidate
                    }
                    return Flipper.CommandStatus.OK
                }
            }
        }

        return Flipper.CommandStatus.ERROR_APP_CMD_ERROR
    }

    private fun isRetryableAppCommandStatus(status: Flipper.CommandStatus): Boolean {
        return status == Flipper.CommandStatus.ERROR_APP_NOT_RUNNING ||
                status == Flipper.CommandStatus.ERROR_BUSY
    }

    private suspend fun sendAppButtonPressReleaseStatusLocked(
        args: String,
        timeoutMs: Long = RPC_APP_BUTTON_TIMEOUT_MS
    ): Flipper.CommandStatus {
        val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
            setAppButtonPressReleaseRequest(
                Application.AppButtonPressReleaseRequest.newBuilder()
                    .setArgs(args)
                    .setIndex(0)
                    .build()
            )
        } ?: return Flipper.CommandStatus.ERROR
        return response.commandStatus
    }

    private suspend fun sendAppButtonPressStatusLocked(
        args: String,
        timeoutMs: Long = RPC_APP_BUTTON_TIMEOUT_MS
    ): Flipper.CommandStatus {
        val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
            setAppButtonPressRequest(
                Application.AppButtonPressRequest.newBuilder()
                    .setArgs(args)
                    .setIndex(0)
                    .build()
            )
        } ?: return Flipper.CommandStatus.ERROR
        return response.commandStatus
    }

    private suspend fun sendAppButtonReleaseStatusLocked(
        timeoutMs: Long = RPC_APP_BUTTON_TIMEOUT_MS
    ): Flipper.CommandStatus {
        val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
            setAppButtonReleaseRequest(Application.AppButtonReleaseRequest.getDefaultInstance())
        } ?: return Flipper.CommandStatus.ERROR
        return response.commandStatus
    }

    private suspend fun requestAppLockStatusLocked(
        timeoutMs: Long = RPC_APP_LOCK_TIMEOUT_MS
    ): Boolean? {
        val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
            setAppLockStatusRequest(Application.LockStatusRequest.getDefaultInstance())
        } ?: return null

        if (response.commandStatus != Flipper.CommandStatus.OK) {
            return null
        }

        return if (response.hasAppLockStatusResponse()) {
            response.appLockStatusResponse.locked
        } else {
            null
        }
    }

    private suspend fun requestAppErrorInfoLocked(
        timeoutMs: Long = RPC_APP_ERROR_TIMEOUT_MS
    ): AppErrorInfo? {
        val response = sendRpcMainAndAwaitResponse(timeoutMs = timeoutMs) {
            setAppGetErrorRequest(Application.GetErrorRequest.getDefaultInstance())
        } ?: return null

        if (response.commandStatus != Flipper.CommandStatus.OK || !response.hasAppGetErrorResponse()) {
            return null
        }

        val errorResponse = response.appGetErrorResponse
        val errorText = errorResponse.text.trim()
        if (errorResponse.code == 0 && errorText.isBlank()) {
            return null
        }

        return AppErrorInfo(
            code = errorResponse.code,
            text = errorText.take(180)
        )
    }

    private fun formatAppErrorSuffix(errorInfo: AppErrorInfo?): String {
        if (errorInfo == null) return ""
        return buildString {
            append(" (app_error_code=")
            append(errorInfo.code)
            if (errorInfo.text.isNotBlank()) {
                append(", app_error=\"")
                append(errorInfo.text)
                append('"')
            }
            append(')')
        }
    }

    private fun isFatalAppError(errorInfo: AppErrorInfo): Boolean {
        if (errorInfo.code == 0) return false
        val normalized = errorInfo.text.lowercase()
        if (normalized.isBlank()) return true
        return FATAL_RPC_APP_ERROR_MARKERS.any { marker -> normalized.contains(marker) }
    }

    private fun buildRpcAppStartCacheKey(appCandidates: List<String>?): String? {
        val normalized = appCandidates
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.map { it.lowercase() }
            ?.distinct()
            .orEmpty()
        if (normalized.isEmpty()) return null
        return normalized.joinToString("|")
    }

    private fun buildRpcButtonCacheKey(plan: RpcCommandPlan): String? {
        val appKey = buildRpcAppStartCacheKey(plan.appCandidates) ?: return null
        val buttonKey = plan.buttonArgsCandidates
            .map { it.trim() }
            .distinct()
            .joinToString("|")
        val fileKey = plan.filePath?.trim().orEmpty()
        return "$appKey#$fileKey#$buttonKey"
    }

    private fun buildRpcExecutionCacheKey(
        plan: RpcCommandPlan,
        appStartKey: String?
    ): String? {
        val normalizedAppKey = appStartKey?.trim().orEmpty()
        if (normalizedAppKey.isBlank()) return null
        val argsKey = plan.appArgs.trim()
        val fileKey = plan.filePath?.trim().orEmpty()
        val buttonKey = plan.buttonArgsCandidates
            .map { it.trim() }
            .distinct()
            .joinToString("|")
        return "$normalizedAppKey#$argsKey#$fileKey#$buttonKey#${plan.triggerOkPress}"
    }

    private fun reorderCandidatesWithCache(
        candidates: List<String>,
        cacheKey: String?,
        cache: ConcurrentHashMap<String, String>
    ): List<String> {
        if (cacheKey.isNullOrBlank()) return candidates
        val cached = cache[cacheKey]?.trim().orEmpty()
        if (cached.isBlank()) return candidates
        if (candidates.none { it == cached }) return candidates
        return listOf(cached) + candidates.filter { it != cached }
    }

    private fun parseRpcCommandPlan(command: String): RpcCommandPlan? {
        val normalized = command.trim().replace(Regex("\\s+"), " ")
        val badUsbTail = Regex(
            "^badusb(?:\\s+(?:run|start))?\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)
        val badUsbParsedArgs = badUsbTail?.let(::parseCommandArguments).orEmpty()
        val (badUsbAppOverride, badUsbArgs) = extractAppOverrideAndArgs(badUsbParsedArgs)
        val badUsbPath = badUsbArgs.firstOrNull()
        if (!badUsbPath.isNullOrBlank()) {
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf(
                        "Bad USB",
                        "BadUSB",
                        "Bad KB",
                        "BadKB",
                        "BadUsb",
                        "BAD USB",
                        "Bad USB App",
                        "Bad USB Keyboard"
                    ),
                    customOverride = badUsbAppOverride
                ),
                appArgs = RPC_APP_START_ARGUMENT,
                filePath = badUsbPath,
                buttonArgsCandidates = listOf("OK", "ok", ""),
                triggerOkPress = true
            )
        }

        val subGhzTail = Regex(
            "^subghz\\s+(?:tx|tx_from_file)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)
        val subGhzParsedArgs = subGhzTail?.let(::parseCommandArguments).orEmpty()
        val (subGhzAppOverride, subGhzArgs) = extractAppOverrideAndArgs(subGhzParsedArgs)
        val subGhzPath = subGhzArgs.firstOrNull()
        if (!subGhzPath.isNullOrBlank()) {
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf(
                        "Sub-GHz",
                        "SubGhz",
                        "Sub GHz",
                        "SubGHz",
                        "Sub-GHz Remote",
                        "SubGHz Remote",
                        "Sub-GHz Tx",
                        "SubGHz Tx",
                        "Sub-GHz App"
                    ),
                    customOverride = subGhzAppOverride
                ),
                appArgs = RPC_APP_START_ARGUMENT,
                filePath = subGhzPath,
                buttonArgsCandidates = listOf(""),
                triggerOkPress = true
            )
        }

        val irTail = Regex(
            "^(?:ir|infrared)\\s+tx\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)
        val irParsedArgs = irTail?.let(::parseCommandArguments).orEmpty()
        val (irAppOverride, irArgs) = extractAppOverrideAndArgs(irParsedArgs)
        val irPath = irArgs.firstOrNull()
        val irSignal = irArgs.drop(1).joinToString(" ").trim().takeIf { it.isNotBlank() }
        if (!irPath.isNullOrBlank()) {
            val irButtonArgs = buildList {
                if (!irSignal.isNullOrBlank()) {
                    add(irSignal)
                }
                add("")
                add("OK")
                add("ok")
            }.distinct()
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf(
                        "Infrared",
                        "Infrared Remote",
                        "IR Remote",
                        "IR",
                        "InfraredTx",
                        "Infrared TX",
                        "IR Tx"
                    ),
                    customOverride = irAppOverride
                ),
                appArgs = RPC_APP_START_ARGUMENT,
                filePath = irPath,
                buttonArgsCandidates = irButtonArgs,
                triggerOkPress = true
            )
        }

        val bleSpamCommand = Regex(
            "^(?:ble[_\\s]?spam|blespam)(?:\\s+.*)?$",
            RegexOption.IGNORE_CASE
        )
        val bleSpamTail = Regex(
            "^(?:ble[_\\s]?spam|blespam)(?:\\s+(.+))?$",
            RegexOption.IGNORE_CASE
        )
            .find(normalized)?.groupValues?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val bleSpamParsedArgs = parseCommandArguments(bleSpamTail)
        val (bleSpamAppOverride, bleSpamArgsTokens) = extractAppOverrideAndArgs(bleSpamParsedArgs)
        val bleSpamArgs = bleSpamArgsTokens.joinToString(" ").trim()
        if (bleSpamCommand.matches(normalized)) {
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf(
                        "BLE Spam",
                        "BLESpam",
                        "BLE_spam",
                        "BLE Spam App",
                        "BLE Spammer",
                        "Bluetooth LE Spam"
                    ),
                    customOverride = bleSpamAppOverride
                ),
                appArgs = bleSpamArgs.ifBlank { RPC_APP_START_ARGUMENT },
                buttonArgsCandidates = listOf("", "OK", "ok"),
                triggerOkPress = true
            )
        }

        val bleScanCommand = Regex(
            "^(?:ble[_\\s]?scan|blescan)(?:\\s+.*)?$",
            RegexOption.IGNORE_CASE
        )
        val bleScanTail = Regex(
            "^(?:ble[_\\s]?scan|blescan)(?:\\s+(.+))?$",
            RegexOption.IGNORE_CASE
        )
            .find(normalized)?.groupValues?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val bleScanParsedArgs = parseCommandArguments(bleScanTail)
        val (bleScanAppOverride, bleScanArgsTokens) = extractAppOverrideAndArgs(bleScanParsedArgs)
        val bleScanArgs = bleScanArgsTokens.joinToString(" ").trim()
        if (bleScanCommand.matches(normalized)) {
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf(
                        "BLE Scanner",
                        "BLE Scan",
                        "BLE Scanner App",
                        "Bluetooth Scanner"
                    ),
                    customOverride = bleScanAppOverride
                ),
                appArgs = bleScanArgs.ifBlank { RPC_APP_START_ARGUMENT },
                buttonArgsCandidates = listOf("", "SCAN", "scan", "OK", "ok"),
                triggerOkPress = true
            )
        }

        val nfcTail = Regex(
            "^nfc\\s+(?:emulate|emu|tx)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)
        val nfcParsedArgs = nfcTail?.let(::parseCommandArguments).orEmpty()
        val (nfcAppOverride, nfcArgs) = extractAppOverrideAndArgs(nfcParsedArgs)
        val nfcPath = nfcArgs.firstOrNull()
        if (!nfcPath.isNullOrBlank()) {
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf("NFC", "Nfc", "NFC App"),
                    customOverride = nfcAppOverride
                ),
                appArgs = RPC_APP_START_ARGUMENT,
                filePath = nfcPath,
                buttonArgsCandidates = listOf(""),
                triggerOkPress = false
            )
        }

        val rfidTail = Regex(
            "^(?:rfid|lfrfid|125khz)\\s+(?:emulate|emu|tx)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)
        val rfidParsedArgs = rfidTail?.let(::parseCommandArguments).orEmpty()
        val (rfidAppOverride, rfidArgs) = extractAppOverrideAndArgs(rfidParsedArgs)
        val rfidPath = rfidArgs.firstOrNull()
        if (!rfidPath.isNullOrBlank()) {
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf(
                        "125 kHz RFID",
                        "LF RFID",
                        "RFID",
                        "LFRFID",
                        "RFID App"
                    ),
                    customOverride = rfidAppOverride
                ),
                appArgs = RPC_APP_START_ARGUMENT,
                filePath = rfidPath,
                buttonArgsCandidates = listOf(""),
                triggerOkPress = false
            )
        }

        val iButtonTail = Regex(
            "^ibutton\\s+(?:emulate|emu|tx)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)
        val iButtonParsedArgs = iButtonTail?.let(::parseCommandArguments).orEmpty()
        val (iButtonAppOverride, iButtonArgs) = extractAppOverrideAndArgs(iButtonParsedArgs)
        val iButtonPath = iButtonArgs.firstOrNull()
        if (!iButtonPath.isNullOrBlank()) {
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf(
                        "iButton",
                        "IButton",
                        "iButton App"
                    ),
                    customOverride = iButtonAppOverride
                ),
                appArgs = RPC_APP_START_ARGUMENT,
                filePath = iButtonPath,
                buttonArgsCandidates = listOf(""),
                triggerOkPress = false
            )
        }

        // Generic app launcher: "loader open <AppName> [args]"
        val loaderTail = Regex(
            "^loader\\s+open\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)
        if (!loaderTail.isNullOrBlank()) {
            val loaderParsedArgs = parseCommandArguments(loaderTail)
            val (loaderAppOverride, loaderArgsTokens) = extractAppOverrideAndArgs(loaderParsedArgs)
            val appName = loaderAppOverride ?: loaderArgsTokens.firstOrNull() ?: loaderTail.trim()
            val remainingArgs = if (loaderAppOverride != null) {
                loaderArgsTokens.joinToString(" ").trim()
            } else {
                loaderArgsTokens.drop(1).joinToString(" ").trim()
            }
            return RpcCommandPlan(
                appCandidates = buildRpcAppCandidates(
                    baseCandidates = listOf(appName),
                    customOverride = null
                ),
                appArgs = remainingArgs.ifBlank { RPC_APP_START_ARGUMENT },
                buttonArgsCandidates = listOf(""),
                triggerOkPress = false
            )
        }

        return null
    }

    private fun extractAppOverrideAndArgs(tokens: List<String>): Pair<String?, List<String>> {
        if (tokens.isEmpty()) return null to emptyList()
        val cleaned = mutableListOf<String>()
        var appOverride: String? = null
        var index = 0

        while (index < tokens.size) {
            val token = tokens[index]
            val normalized = token.lowercase()
            when {
                normalized == "--app" || normalized == "--app-name" -> {
                    val candidate = tokens.getOrNull(index + 1)?.trim()
                    if (!candidate.isNullOrBlank()) {
                        appOverride = candidate
                        index += 2
                        continue
                    }
                }
                normalized.startsWith("--app=") || normalized.startsWith("--app-name=") -> {
                    val candidate = token.substringAfter('=', "").trim()
                    if (candidate.isNotBlank()) {
                        appOverride = candidate
                        index += 1
                        continue
                    }
                }
            }
            cleaned += token
            index += 1
        }

        return appOverride to cleaned
    }

    private fun buildRpcAppCandidates(
        baseCandidates: List<String>,
        customOverride: String?
    ): List<String> {
        val allCandidates = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            val candidate = raw?.trim().orEmpty()
            if (candidate.isBlank()) return
            allCandidates += candidate
            allCandidates += candidate.replace('_', ' ')
            allCandidates += candidate.replace('-', ' ')
            allCandidates += candidate.replace(' ', '_')
            allCandidates += candidate.replace(' ', '-')
            allCandidates += candidate.replace(" ", "")
            allCandidates += candidate.lowercase()
            allCandidates += candidate.uppercase()
        }

        addCandidate(customOverride)
        baseCandidates.forEach(::addCandidate)
        return allCandidates.toList()
    }

    private fun parseCommandArguments(argumentTail: String): List<String> {
        val input = argumentTail.trim()
        if (input.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        input.forEach { ch ->
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && quote != null -> {
                    escaped = true
                }
                quote != null -> {
                    if (ch == quote) {
                        quote = null
                    } else {
                        current.append(ch)
                    }
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                }
                ch.isWhitespace() -> {
                    flush()
                }
                else -> {
                    current.append(ch)
                }
            }
        }

        if (escaped) {
            current.append('\\')
        }
        flush()
        return tokens.filter { it.isNotBlank() }
    }

    suspend fun sendRawCliCommand(command: String): ProtocolResponse = withCommandLock(
        operation = "raw CLI command",
        onTimeout = {
            ProtocolResponse.Error("Command pipeline busy; CLI command timed out waiting for lock: $command")
        }
    ) {
        val service = bleService
            ?: return@withCommandLock ProtocolResponse.Error("Flipper device service unavailable").also {
                markCliUnavailable("Device service unavailable")
            }
        if (!ensureTransportConnected(service)) {
            return@withCommandLock ProtocolResponse.Error("Flipper command transport is not connected").also {
                markCliUnavailable("Flipper command transport is not connected")
            }
        }

        val responseText = collectRawCliResponse("$command\r\n", RAW_CLI_TIMEOUT_MS)
        if (responseText.isNotBlank() && isLikelyCliText(responseText)) {
            markCliReady(responseText)
            return@withCommandLock ProtocolResponse.FileContent(responseText.trim())
        }

        // Validate that the CLI transport is alive before claiming command success.
        val probeResponse = collectRawCliResponse("version\r\n", RAW_CLI_PROBE_TIMEOUT_MS).trim()
        if (probeResponse.isBlank() || !isLikelyCliText(probeResponse)) {
            val rpcStatus = probeRpcTransportAvailability()
            if (rpcStatus != null) {
                val recovered = recoverCliFromRpcSessionLocked()
                if (recovered.level == CliCapabilityLevel.READY && recovered.supportsCli) {
                    val retriedResponse = collectRawCliResponse("$command\r\n", RAW_CLI_TIMEOUT_MS)
                    if (retriedResponse.isNotBlank() && isLikelyCliText(retriedResponse)) {
                        markCliReady(retriedResponse)
                        return@withCommandLock ProtocolResponse.FileContent(retriedResponse.trim())
                    }
                }
                return@withCommandLock ProtocolResponse.Error(
                    "CLI is unavailable on this transport, but RPC is responsive. ${recovered.details}"
                )
            }
            markCliUnavailable("No CLI response from Flipper over active transport")
            return@withCommandLock ProtocolResponse.Error(
                "No CLI response from Flipper over active transport. Command not confirmed. " +
                        "Ensure CLI/RPC is available for this firmware and no other app owns the connection."
            )
        }
        markCliReady(probeResponse)

        return@withCommandLock if (isLikelyNoOutputCommand(command)) {
            ProtocolResponse.Error(
                "Command produced no CLI output, so execution cannot be confirmed: $command. " +
                        "This firmware may require RPC/app-specific control instead of raw CLI."
            )
        } else {
            ProtocolResponse.Error("Command produced no CLI output and cannot be confirmed: $command")
        }
    }

    private suspend fun collectRawCliResponse(commandText: String, timeoutMs: Long): String {
        val attempts = buildList {
            add(commandText)
            val lf = commandText
                .replace("\r\n", "\n")
                .replace("\r", "\n")
            if (lf != commandText) add(lf)
            val cr = commandText
                .replace("\r\n", "\r")
                .replace("\n", "\r")
            if (cr != commandText && cr != lf) add(cr)
        }.distinct()

        attempts.forEach { candidate ->
            val response = collectRawCliResponseAttempt(candidate.toByteArray(Charsets.UTF_8), timeoutMs)
            if (response.isNotBlank()) {
                return response
            }
        }
        return ""
    }

    private suspend fun collectRawCliResponseAttempt(payload: ByteArray, timeoutMs: Long): String {
        val collector = RawCliCollector()
        rawCliCollector = collector
        return try {
            val service = bleService
            if (service == null) {
                return ""
            }
            val sent = service.sendData(payload)
            if (!sent) {
                return ""
            }
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    delay(RAW_CLI_QUIET_PERIOD_MS)
                    val quietForMs = System.currentTimeMillis() - collector.lastUpdateMs
                    if (collector.hasData() && quietForMs >= RAW_CLI_QUIET_PERIOD_MS) {
                        break
                    }
                }
                collector.snapshot()
            } ?: collector.snapshot()
        } finally {
            rawCliCollector = null
        }
    }

    private suspend fun collectRawBinaryResponseAttempt(payload: ByteArray, timeoutMs: Long): ByteArray {
        val collector = RawBinaryCollector()
        rawBinaryCollector = collector
        return try {
            val service = bleService
            if (service == null) {
                return ByteArray(0)
            }
            val sent = service.sendData(payload)
            if (!sent) {
                return ByteArray(0)
            }
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    delay(RAW_BINARY_QUIET_PERIOD_MS)
                    val quietForMs = System.currentTimeMillis() - collector.lastUpdateMs
                    if (collector.hasData() && quietForMs >= RAW_BINARY_QUIET_PERIOD_MS) {
                        break
                    }
                }
                collector.snapshot()
            } ?: collector.snapshot()
        } finally {
            rawBinaryCollector = null
        }
    }

    private suspend fun collectRawBinaryTailResponse(timeoutMs: Long): ByteArray {
        val collector = RawBinaryCollector()
        rawBinaryCollector = collector
        return try {
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    delay(RAW_BINARY_QUIET_PERIOD_MS)
                    val quietForMs = System.currentTimeMillis() - collector.lastUpdateMs
                    if (collector.hasData() && quietForMs >= RAW_BINARY_QUIET_PERIOD_MS) {
                        break
                    }
                }
                collector.snapshot()
            } ?: collector.snapshot()
        } finally {
            rawBinaryCollector = null
        }
    }

    private suspend fun probeRpcTransportAvailability(
        detail: String = "RPC ping responded (CLI unavailable on this transport)"
    ): CliCapabilityStatus? {
        var response = sendRpcMainAndAwaitResponse(
            timeoutMs = RPC_COMMAND_TIMEOUT_MS
        ) {
            setSystemPingRequest(
                PBSystem.PingRequest.newBuilder()
                    .setData(ByteString.copyFromUtf8("vesper-ping"))
                    .build()
            )
        }
        if (response == null && tryStartRpcSession()) {
            delay(RPC_SESSION_START_DELAY_MS)
            response = sendRpcMainAndAwaitResponse(
                timeoutMs = RPC_COMMAND_TIMEOUT_MS
            ) {
                setSystemPingRequest(
                    PBSystem.PingRequest.newBuilder()
                        .setData(ByteString.copyFromUtf8("vesper-ping"))
                        .build()
                )
            }
        }
        if (response == null) return null
        return if (response.commandStatus == Flipper.CommandStatus.OK) {
            lastRpcActivityAtMs = System.currentTimeMillis()
            markRpcReady(detail)
        } else {
            null
        }
    }

    private suspend fun sendRpcMainAndAwaitResponse(
        timeoutMs: Long = RPC_COMMAND_TIMEOUT_MS,
        build: Flipper.Main.Builder.() -> Unit
    ): Flipper.Main? {
        return sendRpcMainAndCollectResponses(timeoutMs = timeoutMs, build = build).lastOrNull()
    }

    private suspend fun sendRpcMainAndCollectResponses(
        timeoutMs: Long = RPC_COMMAND_TIMEOUT_MS,
        build: Flipper.Main.Builder.() -> Unit
    ): List<Flipper.Main> {
        lastRpcActivityAtMs = System.currentTimeMillis()
        val commandId = nextRpcProbeCommandId()
        val request = buildRpcMainPacket(commandId, build)
        val firstAttempt = collectRpcMainResponsesOnce(
            request = request,
            commandId = commandId,
            timeoutMs = timeoutMs
        )
        if (firstAttempt.isNotEmpty()) {
            return firstAttempt
        }

        val restarted = tryStartRpcSession()
        if (!restarted) {
            return emptyList()
        }
        delay(RPC_SESSION_START_DELAY_MS)
        val retryTimeoutMs = timeoutMs.coerceAtMost(RPC_RETRY_COMMAND_TIMEOUT_MS)
        return collectRpcMainResponsesOnce(
            request = request,
            commandId = commandId,
            timeoutMs = retryTimeoutMs
        )
    }

    private suspend fun collectRpcMainResponsesOnce(
        request: ByteArray,
        commandId: Int,
        timeoutMs: Long
    ): List<Flipper.Main> {
        val responseBytes = collectRawBinaryResponseAttempt(request, timeoutMs)
        if (responseBytes.isEmpty()) return emptyList()
        val collected = findRpcMainMessages(responseBytes, commandId).toMutableList()
        if (collected.isEmpty()) {
            return emptyList()
        }

        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (collected.lastOrNull()?.hasNext == true) {
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0L) break

            val tailBytes = collectRawBinaryTailResponse(
                timeoutMs = remainingMs.coerceAtMost(RPC_CONTINUATION_COLLECT_WINDOW_MS)
            )
            if (tailBytes.isEmpty()) break

            val tailResponses = findRpcMainMessages(tailBytes, commandId)
            if (tailResponses.isEmpty()) break
            collected += tailResponses
        }
        if (collected.isNotEmpty()) {
            lastRpcActivityAtMs = System.currentTimeMillis()
        }
        return collected
    }

    private suspend fun tryStartRpcSession(): Boolean {
        val service = bleService ?: return false
        val sessionCommands = listOf(
            "start_rpc_session\r",
            "start_rpc_session\r\n",
            "start_rpc_session\n"
        )
        sessionCommands.forEach { command ->
            val sent = service.sendData(command.toByteArray(Charsets.UTF_8))
            if (sent) {
                lastRpcActivityAtMs = System.currentTimeMillis()
                return true
            }
        }
        return false
    }

    private suspend fun tryStopRpcSession(): Boolean {
        val framedStop = sendRpcMainAndAwaitResponse(timeoutMs = RPC_COMMAND_TIMEOUT_MS) {
            setStopSession(Flipper.StopSession.getDefaultInstance())
        }
        if (framedStop?.commandStatus == Flipper.CommandStatus.OK) {
            return true
        }

        val service = bleService ?: return false
        val stopCommands = listOf(
            "stop_rpc_session\r",
            "stop_rpc_session\r\n",
            "stop_rpc_session\n"
        )
        stopCommands.forEach { command ->
            val sent = service.sendData(command.toByteArray(Charsets.UTF_8))
            if (sent) {
                lastRpcActivityAtMs = System.currentTimeMillis()
                return true
            }
        }
        return false
    }

    private fun nextRpcProbeCommandId(): Int {
        val value = currentRequestId.toInt().coerceAtLeast(1)
        currentRequestId += 1u
        return value
    }

    private fun nextImmediateRpcCommandId(): Int {
        return immediateRpcCommandId.updateAndGet { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
    }

    private fun buildRpcMainPacket(
        commandId: Int,
        build: Flipper.Main.Builder.() -> Unit
    ): ByteArray {
        val body = Flipper.Main.newBuilder()
            .setCommandId(commandId)
            .apply(build)
            .build()
            .toByteArray()
        val envelope = ByteArrayOutputStream()
        writeVarint(envelope, body.size.toLong())
        envelope.write(body)
        return envelope.toByteArray()
    }

    private fun writeVarint(stream: ByteArrayOutputStream, value: Long) {
        var current = value
        while (true) {
            if ((current and 0x7FL.inv()) == 0L) {
                stream.write(current.toInt())
                return
            }
            stream.write(((current and 0x7F) or 0x80).toInt())
            current = current ushr 7
        }
    }

    private fun findRpcMainMessages(bytes: ByteArray, expectedCommandId: Int): List<Flipper.Main> {
        val matches = mutableListOf<Flipper.Main>()
        var offset = 0
        while (offset < bytes.size) {
            val lengthRead = readVarint(bytes, offset)
            if (lengthRead == null) {
                offset++
                continue
            }
            val frameLength = lengthRead.value.toInt()
            val frameStart = lengthRead.nextOffset
            val frameEnd = frameStart + frameLength
            if (frameLength <= 0 || frameLength > MAX_FRAME_SIZE || frameEnd > bytes.size) {
                offset++
                continue
            }

            val frame = bytes.copyOfRange(frameStart, frameEnd)
            val main = runCatching { Flipper.Main.parseFrom(frame) }.getOrNull()
            if (main != null && main.commandId == expectedCommandId) {
                matches += main
            }
            offset = frameEnd
        }
        return matches
    }

    private fun readVarint(bytes: ByteArray, startOffset: Int): VarintReadResult? {
        var result = 0L
        var shift = 0
        var offset = startOffset
        while (offset < bytes.size && shift < 64) {
            val b = bytes[offset].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            offset++
            if (b and 0x80 == 0) {
                return VarintReadResult(result, offset)
            }
            shift += 7
        }
        return null
    }

    private fun isLikelyNoOutputCommand(command: String): Boolean {
        val lower = command.trim().lowercase()
        return lower.startsWith("badusb ") ||
                lower.startsWith("subghz tx ") ||
                lower.startsWith("subghz tx_from_file ") ||
                lower.startsWith("ir tx ") ||
                lower.startsWith("infrared tx ") ||
                lower == "ble_spam" ||
                lower == "blespam" ||
                lower == "ble spam" ||
                lower.startsWith("ble_spam ") ||
                lower.startsWith("blespam ") ||
                lower.startsWith("ble spam ")
    }

    private fun markCliReady(rawOutput: String): CliCapabilityStatus {
        val output = rawOutput.trim()
        val previous = _cliStatus.value
        val status = CliCapabilityStatus(
            level = CliCapabilityLevel.READY,
            checkedAtMs = System.currentTimeMillis(),
            supportsCli = true,
            supportsRpc = previous.supportsRpc,
            firmwareHint = extractFirmwareHint(output),
            details = if (output.isBlank()) {
                "CLI channel is responsive."
            } else {
                output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(180)
            }
        )
        _cliStatus.value = status
        refreshFirmwareCompatibility()
        lastCliProbeAtMs = status.checkedAtMs
        return status
    }

    private fun markRpcReady(detail: String): CliCapabilityStatus {
        val previous = _cliStatus.value
        val status = CliCapabilityStatus(
            level = CliCapabilityLevel.READY,
            checkedAtMs = System.currentTimeMillis(),
            supportsCli = previous.supportsCli,
            supportsRpc = true,
            firmwareHint = null,
            details = detail
        )
        _cliStatus.value = status
        refreshFirmwareCompatibility()
        lastCliProbeAtMs = status.checkedAtMs
        return status
    }

    private fun markCliUnavailable(reason: String): CliCapabilityStatus {
        val status = CliCapabilityStatus(
            level = CliCapabilityLevel.UNAVAILABLE,
            checkedAtMs = System.currentTimeMillis(),
            supportsCli = false,
            supportsRpc = false,
            firmwareHint = null,
            details = reason
        )
        _cliStatus.value = status
        refreshFirmwareCompatibility()
        lastCliProbeAtMs = status.checkedAtMs
        return status
    }

    private fun markProbeDeferred(reason: String): CliCapabilityStatus {
        val previous = _cliStatus.value
        val detail = when {
            previous.details.isBlank() -> reason
            previous.details.contains(reason, ignoreCase = true) -> previous.details
            else -> "${previous.details} $reason"
        }.take(220)
        val status = previous.copy(
            checkedAtMs = System.currentTimeMillis(),
            details = detail
        )
        _cliStatus.value = status
        lastCliProbeAtMs = status.checkedAtMs
        return status
    }

    private fun extractFirmwareHint(output: String): String? {
        val lowered = output.lowercase()
        return when {
            lowered.contains("momentum") || lowered.contains("mntm") -> "Momentum"
            lowered.contains("unleashed") -> "Unleashed"
            lowered.contains("roguemaster") || lowered.contains("rogue master") -> "RogueMaster"
            lowered.contains("xtreme") -> "Xtreme"
            output.isBlank() -> null
            else -> output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
        }
    }

    private fun normalizeCliResponse(command: String, response: ProtocolResponse): ProtocolResponse {
        val responseText = extractResponseText(response)
        if (responseText.isBlank()) {
            return response
        }

        val normalized = responseText.lowercase()
        val firstLine = responseText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(220)
            ?: "CLI command failed"

        return when {
            CLI_FAILURE_MARKERS.any { normalized.contains(it) } -> {
                ProtocolResponse.Error("CLI \"$command\" failed: $firstLine")
            }
            response is ProtocolResponse.Error &&
                    response.message.startsWith("CLI \"").not() &&
                    normalized.startsWith("error") -> {
                ProtocolResponse.Error("CLI \"$command\" failed: $firstLine", response.code)
            }
            else -> response
        }
    }

    private fun summarizeResponse(response: ProtocolResponse): String {
        return when (response) {
            is ProtocolResponse.Success -> response.message.take(140)
            is ProtocolResponse.Error -> response.message.take(140)
            is ProtocolResponse.FileContent -> response.content.lineSequence().firstOrNull().orEmpty().take(140)
            is ProtocolResponse.BinaryContent -> "binary content (${response.data.size} bytes)"
            is ProtocolResponse.DirectoryList -> "directory list (${response.entries.size} entries)"
            is ProtocolResponse.DeviceInformation -> "device information"
        }
    }

    private fun extractResponseText(response: ProtocolResponse): String {
        return when (response) {
            is ProtocolResponse.Success -> response.message
            is ProtocolResponse.Error -> response.message
            is ProtocolResponse.FileContent -> response.content
            is ProtocolResponse.BinaryContent -> response.data.toString(Charsets.UTF_8)
            else -> ""
        }
    }

    private fun learnFirmwareProfile(response: ProtocolResponse) {
        if (firmwareProfile != FirmwareProfile.UNKNOWN) return
        val text = extractResponseText(response).lowercase()
        firmwareProfile = when {
            text.contains("momentum") || text.contains("mntm") -> FirmwareProfile.MOMENTUM
            text.contains("unleashed") -> FirmwareProfile.UNLEASHED
            text.contains("roguemaster") || text.contains("rogue master") -> FirmwareProfile.ROGUEMASTER
            text.contains("xtreme") -> FirmwareProfile.XTREME
            else -> firmwareProfile
        }
        refreshFirmwareCompatibility()
    }

    private fun refreshFirmwareCompatibility() {
        val cli = _cliStatus.value
        val family = resolveFirmwareFamily(cli.firmwareHint)
        val mode = when {
            cli.supportsCli && cli.supportsRpc -> FirmwareTransportMode.CLI_AND_RPC
            cli.supportsCli -> FirmwareTransportMode.CLI_ONLY
            cli.supportsRpc -> FirmwareTransportMode.RPC_ONLY
            else -> FirmwareTransportMode.UNAVAILABLE
        }
        val label = when (family) {
            FirmwareFamily.MOMENTUM -> "Momentum"
            FirmwareFamily.UNLEASHED -> "Unleashed"
            FirmwareFamily.ROGUEMASTER -> "RogueMaster"
            FirmwareFamily.XTREME -> "Xtreme"
            FirmwareFamily.OFFICIAL -> "Official"
            FirmwareFamily.UNKNOWN -> cli.firmwareHint?.takeIf { it.isNotBlank() } ?: "Unknown"
        }
        val notes = when (mode) {
            FirmwareTransportMode.CLI_AND_RPC ->
                "CLI and RPC are both available. Prefer RPC bridge for app-control commands."
            FirmwareTransportMode.CLI_ONLY ->
                "CLI is available. RPC app bridge is unavailable on this session."
            FirmwareTransportMode.RPC_ONLY ->
                "RPC-only transport detected. Use mapped app-control commands for reliable execution."
            FirmwareTransportMode.PROBING ->
                "Transport capability is being probed. Commands may be queued."
            FirmwareTransportMode.UNAVAILABLE ->
                "No automation transport is currently available."
        }
        val confidence = when {
            family != FirmwareFamily.UNKNOWN && cli.level == CliCapabilityLevel.READY -> 0.95f
            family != FirmwareFamily.UNKNOWN -> 0.8f
            cli.firmwareHint != null -> 0.6f
            else -> 0.35f
        }
        _firmwareCompatibility.value = FirmwareCompatibilityProfile(
            family = family,
            label = label,
            transportMode = mode,
            supportsCli = cli.supportsCli,
            supportsRpc = cli.supportsRpc,
            supportsRpcAppBridge = cli.supportsRpc,
            confidence = confidence,
            notes = notes
        )
    }

    private fun resolveFirmwareFamily(firmwareHint: String?): FirmwareFamily {
        return when (firmwareProfile) {
            FirmwareProfile.MOMENTUM -> FirmwareFamily.MOMENTUM
            FirmwareProfile.UNLEASHED -> FirmwareFamily.UNLEASHED
            FirmwareProfile.ROGUEMASTER -> FirmwareFamily.ROGUEMASTER
            FirmwareProfile.XTREME -> FirmwareFamily.XTREME
            FirmwareProfile.UNKNOWN -> {
                val hint = firmwareHint?.lowercase().orEmpty()
                when {
                    hint.contains("momentum") || hint.contains("mntm") -> FirmwareFamily.MOMENTUM
                    hint.contains("unleashed") -> FirmwareFamily.UNLEASHED
                    hint.contains("roguemaster") || hint.contains("rogue master") -> FirmwareFamily.ROGUEMASTER
                    hint.contains("xtreme") -> FirmwareFamily.XTREME
                    hint.contains("flipper") || hint.contains("fw") || hint.contains("firmware") ->
                        FirmwareFamily.OFFICIAL
                    else -> FirmwareFamily.UNKNOWN
                }
            }
        }
    }

    private enum class FirmwareProfile {
        UNKNOWN,
        MOMENTUM,
        UNLEASHED,
        ROGUEMASTER,
        XTREME
    }

    private class RawCliCollector {
        private val buffer = StringBuilder()
        @Volatile
        var lastUpdateMs: Long = System.currentTimeMillis()
            private set

        fun append(data: ByteArray) {
            val chunk = String(data, Charsets.UTF_8)
            synchronized(buffer) {
                buffer.append(chunk)
            }
            lastUpdateMs = System.currentTimeMillis()
        }

        fun hasData(): Boolean = synchronized(buffer) {
            buffer.isNotEmpty()
        }

        fun snapshot(): String = synchronized(buffer) {
            buffer.toString()
        }
    }

    private class RawBinaryCollector {
        private val buffer = ByteArrayOutputStream()
        @Volatile
        var lastUpdateMs: Long = System.currentTimeMillis()
            private set

        fun append(data: ByteArray) {
            synchronized(buffer) {
                buffer.write(data)
            }
            lastUpdateMs = System.currentTimeMillis()
        }

        fun hasData(): Boolean = synchronized(buffer) {
            buffer.size() > 0
        }

        fun snapshot(): ByteArray = synchronized(buffer) {
            buffer.toByteArray()
        }
    }

    private data class VarintReadResult(
        val value: Long,
        val nextOffset: Int
    )

    private sealed class RpcFrameConsumeResult {
        data object None : RpcFrameConsumeResult()
        data object Partial : RpcFrameConsumeResult()
        data class Consumed(val bytes: Int) : RpcFrameConsumeResult()
    }

    private data class AppErrorInfo(
        val code: Int,
        val text: String
    )

    private data class RpcCommandPlan(
        val appCandidates: List<String>? = null,
        val appArgs: String = "",
        val filePath: String? = null,
        val buttonArgsCandidates: List<String> = listOf("OK", "ok", ""),
        val triggerOkPress: Boolean = true
    )

    private data class RpcExecutionSnapshot(
        val executionKey: String,
        val completedAtMs: Long
    )

}

private data class PendingRequest(
    val command: ByteArray,
    val continuation: CompletableDeferred<ProtocolResponse>
)

/**
 * Protocol response types
 */
sealed class ProtocolResponse {
    data class Success(val message: String) : ProtocolResponse()
    data class Error(val message: String, val code: Int = -1) : ProtocolResponse()
    data class DirectoryList(val entries: List<FileEntry>) : ProtocolResponse()
    data class FileContent(val content: String) : ProtocolResponse()
    data class BinaryContent(val data: ByteArray) : ProtocolResponse()
    data class DeviceInformation(
        val deviceInfo: DeviceInfo,
        val storageInfo: StorageInfo
    ) : ProtocolResponse()
}
