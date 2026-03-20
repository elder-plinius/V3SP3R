// FlipperProtocol.swift
// Vesper - AI-powered Flipper Zero controller
// Protocol handler for Flipper Zero serial communication with manual protobuf encoding

import Foundation
import os.log

private let logger = Logger(subsystem: "com.vesper.flipper", category: "Protocol")

// MARK: - FlipperCommand

enum FlipperCommand: Sendable {
    case listDirectory(path: String)
    case readFile(path: String)
    case writeFile(path: String, data: Data)
    case deleteFile(path: String, recursive: Bool)
    case createDirectory(path: String)
    case move(sourcePath: String, destPath: String)
    case getDeviceInfo
    case getStorageInfo
    case appStart(name: String, args: String?)
    case cli(command: String)
}

// MARK: - ProtocolResponse

enum ProtocolResponse: Sendable {
    case success(String)
    case directoryList([FileEntry])
    case fileContent(String)
    case binaryContent(Data)
    case deviceInfo(DeviceInfo)
    case storageInfo(StorageInfo)
    case error(String, Int?)

    init(error message: String) {
        self = .error(message, nil)
    }
}

// MARK: - FlipperProtocol

class FlipperProtocol {

    private let bleManager: FlipperBLEManager
    private var currentRequestId: UInt32 = 0
    private var responseBuffer = Data()
    private let commandQueue = DispatchQueue(label: "com.vesper.flipper.protocol", qos: .userInitiated)
    private let actor = ProtocolActor()

    // Legacy frame command types (matching Android constants)
    private static let cmdList: UInt8 = 0x02
    private static let cmdRead: UInt8 = 0x03
    private static let cmdWrite: UInt8 = 0x04
    private static let cmdDelete: UInt8 = 0x05
    private static let cmdMkdir: UInt8 = 0x06
    private static let cmdMove: UInt8 = 0x07
    private static let cmdInfo: UInt8 = 0x08
    private static let cmdStorageInfo: UInt8 = 0x09

    // Response type markers
    private static let respOK: UInt8 = 0x00
    private static let respError: UInt8 = 0x01
    private static let respList: UInt8 = 0x02
    private static let respData: UInt8 = 0x03
    private static let respInfo: UInt8 = 0x04
    private static let respCli: UInt8 = 0x10

    // Protobuf field numbers for Flipper.Main
    private static let fieldCommandId: UInt32 = 1
    private static let fieldCommandStatus: UInt32 = 2
    private static let fieldHasNext: UInt32 = 3

    // Protobuf field numbers for storage requests (within Main content oneof)
    private static let fieldStorageListRequest: UInt32 = 20
    private static let fieldStorageReadRequest: UInt32 = 21
    private static let fieldStorageWriteRequest: UInt32 = 22
    private static let fieldStorageDeleteRequest: UInt32 = 23
    private static let fieldStorageMkdirRequest: UInt32 = 24
    private static let fieldStorageRenameRequest: UInt32 = 26
    private static let fieldStorageInfoRequest: UInt32 = 28

    // Protobuf field numbers for system/app requests
    private static let fieldSystemDeviceInfoRequest: UInt32 = 32
    private static let fieldAppStartRequest: UInt32 = 40

    private static let maxFrameSize = 256 * 1024
    private static let commandTimeoutNs: UInt64 = 5_000_000_000 // 5 seconds
    private static let cliTimeoutNs: UInt64 = 3_000_000_000 // 3 seconds
    private static let writeChunkSize = 512

    init(bleManager: FlipperBLEManager) {
        self.bleManager = bleManager
        setupDataHandler()
    }

    private func setupDataHandler() {
        bleManager.onDataReceived = { [weak self] data in
            self?.processIncomingData(data)
        }
    }

    // MARK: - Public API

    func sendCommand(_ command: FlipperCommand) async throws -> ProtocolResponse {
        switch command {
        case .listDirectory(let path):
            return await listDirectory(path: path)
        case .readFile(let path):
            return await readFile(path: path)
        case .writeFile(let path, let data):
            return await writeFile(path: path, data: data)
        case .deleteFile(let path, let recursive):
            return await deleteFile(path: path, recursive: recursive)
        case .createDirectory(let path):
            return await createDirectory(path: path)
        case .move(let source, let dest):
            return await moveFile(sourcePath: source, destPath: dest)
        case .getDeviceInfo:
            return await getDeviceInfo()
        case .getStorageInfo:
            return await getStorageInfo()
        case .appStart(let name, let args):
            return await appStart(name: name, args: args)
        case .cli(let command):
            return await sendCliCommandInternal(command)
        }
    }

    func sendCliCommand(_ command: String) async throws -> String {
        let response = await sendCliCommandInternal(command)
        switch response {
        case .success(let msg):
            return msg
        case .fileContent(let content):
            return content
        case .binaryContent(let data):
            return String(data: data, encoding: .utf8) ?? ""
        case .error(let msg, _):
            throw FlipperProtocolError.commandFailed(msg)
        default:
            throw FlipperProtocolError.unexpectedResponse
        }
    }

    // MARK: - Protobuf Message Building

    /// Build a StorageListRequest: field 1 (path) = string
    func buildStorageListRequest(path: String) -> Data {
        // Inner message: ListRequest { string path = 1; }
        let innerMessage = encodeStringField(fieldNumber: 1, value: path)
        // Main message: Main { uint32 command_id = 1; StorageListRequest = field 20 }
        return buildMainMessage(contentFieldNumber: Self.fieldStorageListRequest, contentMessage: innerMessage)
    }

    /// Build a StorageReadRequest: field 1 (path) = string
    func buildStorageReadRequest(path: String) -> Data {
        let innerMessage = encodeStringField(fieldNumber: 1, value: path)
        return buildMainMessage(contentFieldNumber: Self.fieldStorageReadRequest, contentMessage: innerMessage)
    }

    /// Build a StorageWriteRequest: field 1 (path) = string, field 2 (file) = File message
    func buildStorageWriteRequest(path: String, data: Data) -> Data {
        // File message: { FileType type = 1; string name = 2; uint32 size = 3; bytes data = 4; }
        var fileMessage = Data()
        fileMessage.append(encodeVarintField(fieldNumber: 1, value: 0)) // FILE type = 0
        let fileName = (path as NSString).lastPathComponent
        fileMessage.append(encodeStringField(fieldNumber: 2, value: fileName))
        fileMessage.append(encodeVarintField(fieldNumber: 3, value: UInt64(data.count)))
        fileMessage.append(encodeBytesField(fieldNumber: 4, value: data))

        // WriteRequest: { string path = 1; File file = 2; }
        var innerMessage = Data()
        innerMessage.append(encodeStringField(fieldNumber: 1, value: path))
        innerMessage.append(encodeLengthDelimitedField(fieldNumber: 2, value: fileMessage))

        return buildMainMessage(contentFieldNumber: Self.fieldStorageWriteRequest, contentMessage: innerMessage)
    }

    /// Build a StorageDeleteRequest: field 1 (path) = string, field 2 (recursive) = bool
    func buildStorageDeleteRequest(path: String, recursive: Bool) -> Data {
        var innerMessage = Data()
        innerMessage.append(encodeStringField(fieldNumber: 1, value: path))
        if recursive {
            innerMessage.append(encodeVarintField(fieldNumber: 2, value: 1)) // true
        }
        return buildMainMessage(contentFieldNumber: Self.fieldStorageDeleteRequest, contentMessage: innerMessage)
    }

    /// Build a StorageMkdirRequest: field 1 (path) = string
    func buildStorageMkdirRequest(path: String) -> Data {
        let innerMessage = encodeStringField(fieldNumber: 1, value: path)
        return buildMainMessage(contentFieldNumber: Self.fieldStorageMkdirRequest, contentMessage: innerMessage)
    }

    /// Build a StorageRenameRequest (used for move): field 1 (old_path) = string, field 2 (new_path) = string
    func buildStorageRenameRequest(oldPath: String, newPath: String) -> Data {
        var innerMessage = Data()
        innerMessage.append(encodeStringField(fieldNumber: 1, value: oldPath))
        innerMessage.append(encodeStringField(fieldNumber: 2, value: newPath))
        return buildMainMessage(contentFieldNumber: Self.fieldStorageRenameRequest, contentMessage: innerMessage)
    }

    /// Build a SystemDeviceInfoRequest (empty message)
    func buildSystemInfoRequest() -> Data {
        return buildMainMessage(contentFieldNumber: Self.fieldSystemDeviceInfoRequest, contentMessage: Data())
    }

    /// Build a StorageInfoRequest: field 1 (path) = string
    func buildStorageInfoRequest(path: String) -> Data {
        let innerMessage = encodeStringField(fieldNumber: 1, value: path)
        return buildMainMessage(contentFieldNumber: Self.fieldStorageInfoRequest, contentMessage: innerMessage)
    }

    /// Build an AppStartRequest: field 1 (name) = string, field 2 (args) = string
    func buildAppStartRequest(name: String, args: String?) -> Data {
        var innerMessage = Data()
        innerMessage.append(encodeStringField(fieldNumber: 1, value: name))
        if let args, !args.isEmpty {
            innerMessage.append(encodeStringField(fieldNumber: 2, value: args))
        }
        return buildMainMessage(contentFieldNumber: Self.fieldAppStartRequest, contentMessage: innerMessage)
    }

    // MARK: - Response Parsing

    func parseResponse(_ data: Data) -> ProtocolResponse {
        guard !data.isEmpty else {
            return .error("Empty response", nil)
        }

        let firstByte = data[data.startIndex]

        switch firstByte {
        case Self.respOK:
            return parseOkResponse(data)
        case Self.respError:
            return parseErrorResponse(data)
        case Self.respList:
            return parseListResponse(data)
        case Self.respData, Self.respCli:
            return parseDataResponse(data)
        case Self.respInfo:
            return parseInfoResponse(data)
        default:
            // Try to parse as protobuf
            return parseProtobufResponse(data)
        }
    }

    // MARK: - Private Command Implementations

    private func listDirectory(path: String) async -> ProtocolResponse {
        // Try RPC first, fall back to legacy
        let rpcData = buildStorageListRequest(path: path)
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            let parsed = parseResponse(responseData)
            if case .error = parsed {
                return await sendLegacyCommand(type: Self.cmdList, payload: path.data(using: .utf8) ?? Data())
            }
            return parsed
        } catch {
            return await sendLegacyCommand(type: Self.cmdList, payload: path.data(using: .utf8) ?? Data())
        }
    }

    private func readFile(path: String) async -> ProtocolResponse {
        let rpcData = buildStorageReadRequest(path: path)
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            let parsed = parseResponse(responseData)
            if case .error = parsed {
                return await sendLegacyCommand(type: Self.cmdRead, payload: path.data(using: .utf8) ?? Data())
            }
            return parsed
        } catch {
            return await sendLegacyCommand(type: Self.cmdRead, payload: path.data(using: .utf8) ?? Data())
        }
    }

    private func writeFile(path: String, data content: Data) async -> ProtocolResponse {
        // For large files, chunk the writes
        if content.count <= Self.writeChunkSize {
            return await writeFileSingle(path: path, data: content)
        } else {
            return await writeFileChunked(path: path, data: content)
        }
    }

    private func writeFileSingle(path: String, data content: Data) async -> ProtocolResponse {
        let rpcData = buildStorageWriteRequest(path: path, data: content)
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            let parsed = parseResponse(responseData)
            if case .error = parsed {
                return await sendLegacyWriteCommand(path: path, content: content)
            }
            return parsed
        } catch {
            return await sendLegacyWriteCommand(path: path, content: content)
        }
    }

    private func writeFileChunked(path: String, data content: Data) async -> ProtocolResponse {
        var offset = 0
        while offset < content.count {
            let end = min(offset + Self.writeChunkSize, content.count)
            let chunk = content[offset..<end]
            let chunkData = Data(chunk)

            let response = await writeFileSingle(path: path, data: chunkData)
            if case .error = response {
                return response
            }

            offset = end
        }
        return .success("Written \(content.count) bytes to \(path)")
    }

    private func deleteFile(path: String, recursive: Bool) async -> ProtocolResponse {
        let rpcData = buildStorageDeleteRequest(path: path, recursive: recursive)
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            let parsed = parseResponse(responseData)
            if case .error = parsed {
                var payload = Data([recursive ? 1 : 0])
                payload.append(path.data(using: .utf8) ?? Data())
                return await sendLegacyCommand(type: Self.cmdDelete, payload: payload)
            }
            return parsed
        } catch {
            var payload = Data([recursive ? 1 : 0])
            payload.append(path.data(using: .utf8) ?? Data())
            return await sendLegacyCommand(type: Self.cmdDelete, payload: payload)
        }
    }

    private func createDirectory(path: String) async -> ProtocolResponse {
        let rpcData = buildStorageMkdirRequest(path: path)
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            let parsed = parseResponse(responseData)
            if case .error = parsed {
                return await sendLegacyCommand(type: Self.cmdMkdir, payload: path.data(using: .utf8) ?? Data())
            }
            return parsed
        } catch {
            return await sendLegacyCommand(type: Self.cmdMkdir, payload: path.data(using: .utf8) ?? Data())
        }
    }

    private func moveFile(sourcePath: String, destPath: String) async -> ProtocolResponse {
        let rpcData = buildStorageRenameRequest(oldPath: sourcePath, newPath: destPath)
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            let parsed = parseResponse(responseData)
            if case .error = parsed {
                return await sendLegacyMoveCommand(source: sourcePath, dest: destPath)
            }
            return parsed
        } catch {
            return await sendLegacyMoveCommand(source: sourcePath, dest: destPath)
        }
    }

    private func getDeviceInfo() async -> ProtocolResponse {
        let rpcData = buildSystemInfoRequest()
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            let parsed = parseResponse(responseData)
            if case .error = parsed {
                return await sendLegacyCommand(type: Self.cmdInfo, payload: Data())
            }
            return parsed
        } catch {
            return await sendLegacyCommand(type: Self.cmdInfo, payload: Data())
        }
    }

    private func getStorageInfo() async -> ProtocolResponse {
        let rpcData = buildStorageInfoRequest(path: "/ext")
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            let parsed = parseResponse(responseData)
            if case .error = parsed {
                return await sendLegacyCommand(type: Self.cmdStorageInfo, payload: Data())
            }
            return parsed
        } catch {
            return await sendLegacyCommand(type: Self.cmdStorageInfo, payload: Data())
        }
    }

    private func appStart(name: String, args: String?) async -> ProtocolResponse {
        let rpcData = buildAppStartRequest(name: name, args: args)
        let rpcFrame = wrapWithLengthPrefix(rpcData)

        do {
            let responseData = try await bleManager.sendFramedData(rpcFrame)
            return parseResponse(responseData)
        } catch {
            // App start has no legacy fallback; try CLI
            let cliCommand = args != nil ? "app \(name) \(args!)" : "app \(name)"
            return await sendCliCommandInternal(cliCommand)
        }
    }

    private func sendCliCommandInternal(_ command: String) async -> ProtocolResponse {
        let commandData = (command + "\r\n").data(using: .utf8) ?? Data()

        do {
            try await bleManager.sendData(commandData)

            // Wait for CLI response with timeout
            let responseData = try await withThrowingTimeout(nanoseconds: Self.cliTimeoutNs) { [weak self] in
                // Collect data for a brief period
                try await Task.sleep(nanoseconds: 500_000_000) // 500ms collection window
                guard let self else { throw FlipperProtocolError.disconnected }
                let collected = self.responseBuffer
                self.responseBuffer.removeAll()
                return collected
            }

            let responseText = String(data: responseData, encoding: .utf8)?
                .replacingOccurrences(of: "\0", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

            if responseText.isEmpty {
                return .error("No CLI response received", nil)
            }

            return .fileContent(responseText)
        } catch {
            return .error("CLI command failed: \(error.localizedDescription)", nil)
        }
    }

    // MARK: - Legacy Command Building

    private func sendLegacyCommand(type: UInt8, payload: Data) async -> ProtocolResponse {
        let command = buildLegacyCommand(commandType: type, payload: payload)
        do {
            let responseData = try await bleManager.sendFramedData(command)
            return parseResponse(responseData)
        } catch {
            return .error("Command failed: \(error.localizedDescription)", nil)
        }
    }

    private func sendLegacyWriteCommand(path: String, content: Data) async -> ProtocolResponse {
        let pathBytes = path.data(using: .utf8) ?? Data()
        var payload = Data()
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(pathBytes.count).littleEndian) { Data($0) })
        payload.append(pathBytes)
        payload.append(content)
        return await sendLegacyCommand(type: Self.cmdWrite, payload: payload)
    }

    private func sendLegacyMoveCommand(source: String, dest: String) async -> ProtocolResponse {
        let sourceBytes = source.data(using: .utf8) ?? Data()
        let destBytes = dest.data(using: .utf8) ?? Data()
        var payload = Data()
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(sourceBytes.count).littleEndian) { Data($0) })
        payload.append(sourceBytes)
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(destBytes.count).littleEndian) { Data($0) })
        payload.append(destBytes)
        return await sendLegacyCommand(type: Self.cmdMove, payload: payload)
    }

    private func buildLegacyCommand(commandType: UInt8, payload: Data) -> Data {
        let requestId = nextRequestId()
        var frame = Data()
        frame.append(contentsOf: withUnsafeBytes(of: requestId.littleEndian) { Data($0) })
        frame.append(commandType)
        frame.append(payload)

        // Wrap with length prefix
        return wrapWithLengthPrefix(frame)
    }

    // MARK: - Incoming Data Processing

    private func processIncomingData(_ data: Data) {
        responseBuffer.append(data)
    }

    // MARK: - Protobuf Wire Format Encoding

    /// Encode a varint (unsigned LEB128)
    private func encodeVarint(_ value: UInt64) -> Data {
        var data = Data()
        var v = value
        while v > 0x7F {
            data.append(UInt8(v & 0x7F) | 0x80)
            v >>= 7
        }
        data.append(UInt8(v & 0x7F))
        if data.isEmpty { data.append(0) }
        return data
    }

    /// Encode field tag: (field_number << 3) | wire_type
    private func encodeTag(fieldNumber: UInt32, wireType: UInt8) -> Data {
        return encodeVarint(UInt64(fieldNumber) << 3 | UInt64(wireType))
    }

    /// Encode a varint field (wire type 0)
    private func encodeVarintField(fieldNumber: UInt32, value: UInt64) -> Data {
        var data = Data()
        data.append(encodeTag(fieldNumber: fieldNumber, wireType: 0))
        data.append(encodeVarint(value))
        return data
    }

    /// Encode a string field (wire type 2: length-delimited)
    private func encodeStringField(fieldNumber: UInt32, value: String) -> Data {
        let stringBytes = value.data(using: .utf8) ?? Data()
        var data = Data()
        data.append(encodeTag(fieldNumber: fieldNumber, wireType: 2))
        data.append(encodeVarint(UInt64(stringBytes.count)))
        data.append(stringBytes)
        return data
    }

    /// Encode a bytes field (wire type 2: length-delimited)
    private func encodeBytesField(fieldNumber: UInt32, value: Data) -> Data {
        var data = Data()
        data.append(encodeTag(fieldNumber: fieldNumber, wireType: 2))
        data.append(encodeVarint(UInt64(value.count)))
        data.append(value)
        return data
    }

    /// Encode a length-delimited sub-message field (wire type 2)
    private func encodeLengthDelimitedField(fieldNumber: UInt32, value: Data) -> Data {
        var data = Data()
        data.append(encodeTag(fieldNumber: fieldNumber, wireType: 2))
        data.append(encodeVarint(UInt64(value.count)))
        data.append(value)
        return data
    }

    /// Build a Flipper.Main protobuf message wrapping a content message.
    /// Main { uint32 command_id = 1; CommandStatus command_status = 2; bool has_next = 3; <content> }
    private func buildMainMessage(contentFieldNumber: UInt32, contentMessage: Data) -> Data {
        let reqId = nextRequestId()
        var message = Data()

        // Field 1: command_id (varint)
        message.append(encodeVarintField(fieldNumber: Self.fieldCommandId, value: UInt64(reqId)))

        // Field 2: command_status = OK (0) -- omitted, default is 0

        // Field 3: has_next = false -- omitted, default is false

        // Content field (length-delimited sub-message)
        if !contentMessage.isEmpty {
            message.append(encodeLengthDelimitedField(fieldNumber: contentFieldNumber, value: contentMessage))
        } else {
            // Empty sub-message: still need the tag with zero length
            message.append(encodeTag(fieldNumber: contentFieldNumber, wireType: 2))
            message.append(encodeVarint(0))
        }

        return message
    }

    /// Wrap data with a 4-byte little-endian length prefix (legacy frame format).
    private func wrapWithLengthPrefix(_ data: Data) -> Data {
        var frame = Data()
        frame.append(contentsOf: withUnsafeBytes(of: UInt32(data.count).littleEndian) { Data($0) })
        frame.append(data)
        return frame
    }

    // MARK: - Response Parsing Helpers

    private func parseOkResponse(_ data: Data) -> ProtocolResponse {
        let startIndex = data.startIndex + 1
        if data.count > 1 {
            let message = String(data: data[startIndex...], encoding: .utf8) ?? "OK"
            return .success(message)
        }
        return .success("OK")
    }

    private func parseErrorResponse(_ data: Data) -> ProtocolResponse {
        let errorCode: Int? = data.count > 1 ? Int(data[data.startIndex + 1]) : nil
        let startIndex = data.startIndex + 2
        let message: String
        if data.count > 2 {
            message = String(data: data[startIndex...], encoding: .utf8) ?? "Error"
        } else {
            message = "Error"
        }
        return .error(message, errorCode)
    }

    private func parseListResponse(_ data: Data) -> ProtocolResponse {
        var entries: [FileEntry] = []
        var offset = data.startIndex + 1

        while offset < data.endIndex {
            guard offset + 9 <= data.endIndex else { break }

            let isDir = data[offset] == 1
            offset += 1

            let size = data[offset..<(offset + 4)].withUnsafeBytes { ptr in
                Int64(ptr.loadUnaligned(as: UInt32.self).littleEndian)
            }
            offset += 4

            let nameLen = data[offset..<(offset + 4)].withUnsafeBytes { ptr in
                Int(ptr.loadUnaligned(as: UInt32.self).littleEndian)
            }
            offset += 4

            guard offset + nameLen <= data.endIndex else { break }

            let name = String(data: data[offset..<(offset + nameLen)], encoding: .utf8) ?? ""
            offset += nameLen

            entries.append(FileEntry(
                name: name,
                path: name,
                isDirectory: isDir,
                size: size
            ))
        }

        return .directoryList(entries)
    }

    private func parseDataResponse(_ data: Data) -> ProtocolResponse {
        if data.count > 1 {
            let content = String(data: data[(data.startIndex + 1)...], encoding: .utf8) ?? ""
            return .fileContent(content)
        }
        return .fileContent("")
    }

    private func parseInfoResponse(_ data: Data) -> ProtocolResponse {
        guard data.count >= 20 else {
            return .error("Invalid info response: too short", nil)
        }

        let offset = data.startIndex + 1
        let batteryLevel = Int(data[offset]) & 0xFF
        let isCharging = data[offset + 1] == 1

        let internalTotal = data[(offset + 2)..<(offset + 10)].withUnsafeBytes { ptr in
            Int64(ptr.loadUnaligned(as: Int64.self).littleEndian)
        }
        let internalFree = data[(offset + 10)..<(offset + 18)].withUnsafeBytes { ptr in
            Int64(ptr.loadUnaligned(as: Int64.self).littleEndian)
        }

        let deviceInfoVal = DeviceInfo(
            name: "Flipper Zero",
            firmwareVersion: "0.0.0",
            hardwareVersion: "1.0",
            batteryLevel: batteryLevel,
            isCharging: isCharging
        )

        let storageInfoVal = StorageInfo(
            internalTotal: internalTotal,
            internalFree: internalFree,
            hasSdCard: false
        )

        // Return device info; the file system layer can request storage info separately
        return .deviceInfo(deviceInfoVal)
    }

    private func parseProtobufResponse(_ data: Data) -> ProtocolResponse {
        // Attempt to decode as a Flipper.Main protobuf message
        var offset = data.startIndex
        var commandStatus: UInt64 = 0
        var hasListEntries = false
        var listEntries: [FileEntry] = []
        var fileData = Data()
        var hasFileData = false
        var kvPairs: [(String, String)] = []
        var totalSpace: Int64 = 0
        var freeSpace: Int64 = 0
        var hasStorageInfo = false

        while offset < data.endIndex {
            guard let (fieldNumber, wireType, newOffset) = readTag(from: data, at: offset) else { break }
            offset = newOffset

            switch wireType {
            case 0: // varint
                guard let (value, nextOff) = readVarint(from: data, at: offset) else { return .error("Malformed varint", nil) }
                offset = nextOff

                if fieldNumber == Self.fieldCommandStatus {
                    commandStatus = value
                }

            case 2: // length-delimited
                guard let (length, lenOffset) = readVarint(from: data, at: offset) else { return .error("Malformed length", nil) }
                let contentStart = lenOffset
                let contentEnd = contentStart + Int(length)
                guard contentEnd <= data.endIndex else { return .error("Truncated message", nil) }
                let content = data[contentStart..<contentEnd]
                offset = contentEnd

                // Parse based on field number
                switch fieldNumber {
                case Self.fieldStorageListRequest + 100: // StorageListResponse at field 120 in some versions
                    // Parse list entries from sub-message
                    parseStorageListContent(content, into: &listEntries)
                    hasListEntries = true

                case 120: // storage_list_response in Flipper.Main
                    parseStorageListContent(content, into: &listEntries)
                    hasListEntries = true

                case 121: // storage_read_response
                    parseStorageReadContent(content, into: &fileData)
                    hasFileData = true

                case 132: // system_device_info_response
                    parseDeviceInfoContent(content, into: &kvPairs)

                case 128: // storage_info_response
                    parseStorageInfoContent(content, totalSpace: &totalSpace, freeSpace: &freeSpace)
                    hasStorageInfo = true

                default:
                    break
                }

            default:
                // Skip unknown wire types
                break
            }
        }

        // Determine response type
        if commandStatus != 0 {
            return .error("RPC error: status \(commandStatus)", Int(commandStatus))
        }

        if hasListEntries {
            return .directoryList(listEntries)
        }

        if hasFileData {
            return .binaryContent(fileData)
        }

        if !kvPairs.isEmpty {
            return buildDeviceInfoFromKVPairs(kvPairs)
        }

        if hasStorageInfo {
            return .storageInfo(StorageInfo(
                internalTotal: totalSpace,
                internalFree: freeSpace,
                hasSdCard: false
            ))
        }

        return .success("OK")
    }

    // MARK: - Protobuf Sub-message Parsing

    private func parseStorageListContent(_ data: Data, into entries: inout [FileEntry]) {
        // Repeated File messages: { FileType type = 1; string name = 2; uint32 size = 3; bytes data = 4; }
        var offset = data.startIndex
        while offset < data.endIndex {
            guard let (fieldNumber, wireType, newOffset) = readTag(from: data, at: offset) else { break }
            offset = newOffset

            if wireType == 2, fieldNumber == 1 { // repeated File field
                guard let (length, lenOffset) = readVarint(from: data, at: offset) else { break }
                let contentEnd = lenOffset + Int(length)
                guard contentEnd <= data.endIndex else { break }
                let fileData = data[lenOffset..<contentEnd]
                offset = contentEnd

                if let entry = parseFileMessage(fileData) {
                    entries.append(entry)
                }
            } else {
                // Skip
                offset = skipField(wireType: wireType, from: data, at: offset) ?? data.endIndex
            }
        }
    }

    private func parseFileMessage(_ data: Data) -> FileEntry? {
        var fileType: UInt64 = 0
        var name = ""
        var size: UInt64 = 0
        var offset = data.startIndex

        while offset < data.endIndex {
            guard let (fieldNumber, wireType, newOffset) = readTag(from: data, at: offset) else { break }
            offset = newOffset

            switch (fieldNumber, wireType) {
            case (1, 0): // type
                guard let (v, o) = readVarint(from: data, at: offset) else { return nil }
                fileType = v
                offset = o
            case (2, 2): // name
                guard let (len, lenOff) = readVarint(from: data, at: offset) else { return nil }
                let strEnd = lenOff + Int(len)
                guard strEnd <= data.endIndex else { return nil }
                name = String(data: data[lenOff..<strEnd], encoding: .utf8) ?? ""
                offset = strEnd
            case (3, 0): // size
                guard let (v, o) = readVarint(from: data, at: offset) else { return nil }
                size = v
                offset = o
            default:
                offset = skipField(wireType: wireType, from: data, at: offset) ?? data.endIndex
            }
        }

        guard !name.isEmpty else { return nil }

        return FileEntry(
            name: name,
            path: name,
            isDirectory: fileType == 1, // DIR = 1
            size: Int64(size)
        )
    }

    private func parseStorageReadContent(_ data: Data, into fileData: inout Data) {
        var offset = data.startIndex
        while offset < data.endIndex {
            guard let (fieldNumber, wireType, newOffset) = readTag(from: data, at: offset) else { break }
            offset = newOffset

            if wireType == 2, fieldNumber == 1 { // File message
                guard let (length, lenOffset) = readVarint(from: data, at: offset) else { break }
                let contentEnd = lenOffset + Int(length)
                guard contentEnd <= data.endIndex else { break }
                let fileMessage = data[lenOffset..<contentEnd]
                offset = contentEnd

                // Extract bytes field (field 4) from File message
                var innerOffset = fileMessage.startIndex
                while innerOffset < fileMessage.endIndex {
                    guard let (fn, wt, no) = readTag(from: fileMessage, at: innerOffset) else { break }
                    innerOffset = no
                    if wt == 2, fn == 4 { // data field
                        guard let (len, lo) = readVarint(from: fileMessage, at: innerOffset) else { break }
                        let end = lo + Int(len)
                        guard end <= fileMessage.endIndex else { break }
                        fileData.append(fileMessage[lo..<end])
                        innerOffset = end
                    } else {
                        innerOffset = skipField(wireType: wt, from: fileMessage, at: innerOffset) ?? fileMessage.endIndex
                    }
                }
            } else {
                offset = skipField(wireType: wireType, from: data, at: offset) ?? data.endIndex
            }
        }
    }

    private func parseDeviceInfoContent(_ data: Data, into kvPairs: inout [(String, String)]) {
        var offset = data.startIndex
        while offset < data.endIndex {
            guard let (fieldNumber, wireType, newOffset) = readTag(from: data, at: offset) else { break }
            offset = newOffset

            if wireType == 2 {
                guard let (length, lenOffset) = readVarint(from: data, at: offset) else { break }
                let contentEnd = lenOffset + Int(length)
                guard contentEnd <= data.endIndex else { break }
                let value = String(data: data[lenOffset..<contentEnd], encoding: .utf8) ?? ""
                offset = contentEnd

                switch fieldNumber {
                case 1: kvPairs.append(("key", value))
                case 2: kvPairs.append(("value", value))
                default: break
                }
            } else {
                offset = skipField(wireType: wireType, from: data, at: offset) ?? data.endIndex
            }
        }
    }

    private func parseStorageInfoContent(_ data: Data, totalSpace: inout Int64, freeSpace: inout Int64) {
        var offset = data.startIndex
        while offset < data.endIndex {
            guard let (fieldNumber, wireType, newOffset) = readTag(from: data, at: offset) else { break }
            offset = newOffset

            if wireType == 0 { // varint
                guard let (value, nextOff) = readVarint(from: data, at: offset) else { break }
                offset = nextOff
                switch fieldNumber {
                case 1: totalSpace = Int64(value)
                case 2: freeSpace = Int64(value)
                default: break
                }
            } else {
                offset = skipField(wireType: wireType, from: data, at: offset) ?? data.endIndex
            }
        }
    }

    private func buildDeviceInfoFromKVPairs(_ pairs: [(String, String)]) -> ProtocolResponse {
        var firmware = "unknown"
        var hardware = "unknown"
        var deviceName = "Flipper Zero"
        var batteryLevel = 0
        var isCharging = false

        // Pairs come as alternating key/value
        var i = 0
        while i + 1 < pairs.count {
            let key = pairs[i].1.lowercased()
            let value = pairs[i + 1].1
            i += 2

            switch key {
            case "firmware.version", "firmware_version":
                firmware = value
            case "hardware.model", "hardware_model":
                hardware = value
            case "hardware.name", "device_name":
                deviceName = value
            case "power.battery_level", "battery_level":
                batteryLevel = Int(value) ?? 0
            case "power.is_charging", "is_charging":
                isCharging = value == "1" || value.lowercased() == "true"
            default:
                break
            }
        }

        return .deviceInfo(DeviceInfo(
            name: deviceName,
            firmwareVersion: firmware,
            hardwareVersion: hardware,
            batteryLevel: batteryLevel,
            isCharging: isCharging
        ))
    }

    // MARK: - Protobuf Wire Format Helpers

    private func readTag(from data: Data, at offset: Int) -> (fieldNumber: UInt32, wireType: UInt8, nextOffset: Int)? {
        guard let (value, nextOffset) = readVarint(from: data, at: offset) else { return nil }
        let wireType = UInt8(value & 0x07)
        let fieldNumber = UInt32(value >> 3)
        return (fieldNumber, wireType, nextOffset)
    }

    private func readVarint(from data: Data, at offset: Int) -> (value: UInt64, nextOffset: Int)? {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        var pos = offset

        while pos < data.endIndex {
            let byte = data[pos]
            result |= UInt64(byte & 0x7F) << shift
            pos += 1

            if byte & 0x80 == 0 {
                return (result, pos)
            }
            shift += 7

            if shift >= 64 { return nil }
        }

        return nil
    }

    private func skipField(wireType: UInt8, from data: Data, at offset: Int) -> Int? {
        switch wireType {
        case 0: // varint
            var pos = offset
            while pos < data.endIndex {
                if data[pos] & 0x80 == 0 { return pos + 1 }
                pos += 1
            }
            return nil
        case 1: // 64-bit
            return offset + 8 <= data.endIndex ? offset + 8 : nil
        case 2: // length-delimited
            guard let (length, nextOffset) = readVarint(from: data, at: offset) else { return nil }
            let end = nextOffset + Int(length)
            return end <= data.endIndex ? end : nil
        case 5: // 32-bit
            return offset + 4 <= data.endIndex ? offset + 4 : nil
        default:
            return nil
        }
    }

    // MARK: - Utilities

    private func nextRequestId() -> UInt32 {
        currentRequestId += 1
        return currentRequestId
    }

    private func withThrowingTimeout<T: Sendable>(nanoseconds: UInt64, operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask {
                try await operation()
            }
            group.addTask {
                try await Task.sleep(nanoseconds: nanoseconds)
                throw FlipperProtocolError.timeout
            }
            guard let result = try await group.next() else {
                throw FlipperProtocolError.timeout
            }
            group.cancelAll()
            return result
        }
    }
}

// MARK: - ProtocolActor (serializes concurrent access)

private actor ProtocolActor {
    var lastRequestId: UInt32 = 0

    func nextRequestId() -> UInt32 {
        lastRequestId += 1
        return lastRequestId
    }
}

// MARK: - Errors

enum FlipperProtocolError: Error, LocalizedError {
    case commandFailed(String)
    case timeout
    case disconnected
    case unexpectedResponse
    case invalidPath(String)
    case encodingError

    var errorDescription: String? {
        switch self {
        case .commandFailed(let msg): return "Command failed: \(msg)"
        case .timeout: return "Command timed out"
        case .disconnected: return "Device disconnected"
        case .unexpectedResponse: return "Unexpected response from device"
        case .invalidPath(let path): return "Invalid path: \(path)"
        case .encodingError: return "Failed to encode command"
        }
    }
}
