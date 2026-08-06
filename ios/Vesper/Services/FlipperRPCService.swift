import Foundation
import SwiftProtobuf
import VesperCore

actor FlipperRPCService: FlipperRPCClient {
    private struct PendingResponse {
        var messages: [PB_Main]
        let continuation: CheckedContinuation<[PB_Main], Error>
    }

    private let transport: any FlipperTransport
    private var nextCommandID: UInt32 = 1
    private var pending: [UInt32: PendingResponse] = [:]
    private var frameDecoder = RPCFrameDecoder()
    private var receiverTask: Task<Void, Never>?
    private var connectionTask: Task<Void, Never>?
    private var lastDiagnostic = "RPC idle"
    private var rawMode = false
    private var rawBuffer = Data()
    private var rawContinuation: CheckedContinuation<String, Error>?

    init(transport: any FlipperTransport) { self.transport = transport }

    deinit { receiverTask?.cancel(); connectionTask?.cancel() }

    func getDeviceInfo() async throws -> DeviceInfo {
        let deviceResponses = try await request { $0.systemDeviceInfoRequest = PBSystem_DeviceInfoRequest() }
        let powerResponses = try await request { $0.systemPowerInfoRequest = PBSystem_PowerInfoRequest() }
        let values = pairs(deviceResponses) { message in
            guard case .systemDeviceInfoResponse(let value)? = message.content else { return nil }
            return (value.key, value.value)
        }
        let power = pairs(powerResponses) { message in
            guard case .systemPowerInfoResponse(let value)? = message.content else { return nil }
            return (value.key, value.value)
        }
        let battery = percent(power, keys: ["charge_percent", "charge_level", "battery_level", "capacity_percent"]) ?? 0
        let charging = bool(power, keys: ["is_charging", "charging", "charger_connected", "charge_state"]) ?? false
        return DeviceInfo(
            name: first(values, keys: ["device_name", "name", "hardware_name", "product_name"]) ?? "Flipper Zero",
            firmwareVersion: first(values, keys: ["firmware_version", "firmware", "version", "git_commit", "branch"]) ?? "unknown",
            hardwareVersion: first(values, keys: ["hardware_version", "hardware", "hardware_name", "target"]) ?? "unknown",
            batteryLevel: min(100, max(0, battery)),
            isCharging: charging
        )
    }

    func getStorageInfo() async throws -> StorageInfo {
        let internalInfo = try? await storageInfo(path: "/int")
        let externalInfo = try? await storageInfo(path: "/ext")
        guard internalInfo != nil || externalInfo != nil else { throw RPCError.emptyResponse }
        return StorageInfo(
            internalTotal: internalInfo?.total ?? 0,
            internalFree: internalInfo?.free ?? 0,
            externalTotal: externalInfo?.total,
            externalFree: externalInfo?.free,
            hasSDCard: (externalInfo?.total ?? 0) > 0
        )
    }

    func listDirectory(_ path: String) async throws -> [FileEntry] {
        var requestValue = PBStorage_ListRequest()
        requestValue.path = path
        let responses = try await request { $0.storageListRequest = requestValue }
        return responses.flatMap { message -> [FileEntry] in
            guard case .storageListResponse(let response)? = message.content else { return [] }
            return response.file.map { file in
                let childPath = path == "/" ? "/\(file.name)" : "\(path)/\(file.name)"
                return FileEntry(name: file.name, path: childPath, isDirectory: file.type == .dir, size: UInt64(file.size))
            }
        }
    }

    func readFile(_ path: String) async throws -> Data {
        var requestValue = PBStorage_ReadRequest()
        requestValue.path = path
        let responses = try await request(timeout: 20) { $0.storageReadRequest = requestValue }
        let data = responses.reduce(into: Data()) { result, message in
            if case .storageReadResponse(let response)? = message.content { result.append(response.file.data) }
        }
        guard !responses.isEmpty else { throw RPCError.emptyResponse }
        return data
    }

    @discardableResult
    func writeFile(_ path: String, data: Data) async throws -> UInt64 {
        let id = allocateCommandID()
        let chunks = data.isEmpty ? [Data()] : stride(from: 0, to: data.count, by: 512).map { offset in
            data.subdata(in: offset..<min(data.count, offset + 512))
        }
        var packets: [Data] = []
        for (index, chunk) in chunks.enumerated() {
            var file = PBStorage_File()
            file.type = .file
            file.name = URL(fileURLWithPath: path).lastPathComponent
            file.size = UInt32(chunk.count)
            file.data = chunk
            var write = PBStorage_WriteRequest()
            write.path = path
            write.file = file
            var main = PB_Main()
            main.commandID = id
            main.hasNext_p = index < chunks.count - 1
            main.storageWriteRequest = write
            packets.append(try framed(main))
        }
        _ = try await sendAndAwait(id: id, packets: packets, timeout: max(20, Double(data.count) / 4_000))
        return UInt64(data.count)
    }

    func createDirectory(_ path: String) async throws {
        var value = PBStorage_MkdirRequest(); value.path = path
        _ = try await request { $0.storageMkdirRequest = value }
    }

    func delete(_ path: String, recursive: Bool) async throws {
        var value = PBStorage_DeleteRequest(); value.path = path; value.recursive = recursive
        _ = try await request { $0.storageDeleteRequest = value }
    }

    func move(_ source: String, to destination: String) async throws {
        var value = PBStorage_RenameRequest(); value.oldPath = source; value.newPath = destination
        _ = try await request { $0.storageRenameRequest = value }
    }

    func copy(_ source: String, to destination: String) async throws {
        let data = try await readFile(source)
        _ = try await writeFile(destination, data: data)
    }

    func executeCLI(_ command: String) async throws -> String {
        var stop = PB_Main()
        stop.stopSession = PB_StopSession()
        _ = try? await request(main: stop, timeout: 3)
        rawMode = true
        rawBuffer.removeAll(keepingCapacity: true)
        let output = try await withCheckedThrowingContinuation { continuation in
            rawContinuation = continuation
            Task {
                do { try await transport.send(Data("\r\(command)\r".utf8)) }
                catch { self.failRaw(error) }
            }
            Task {
                try? await Task.sleep(for: .seconds(8))
                self.timeoutRaw()
            }
        }
        rawMode = false
        frameDecoder.reset()
        try await transport.send(Data("start_rpc_session\r".utf8))
        try? await Task.sleep(for: .milliseconds(500))
        return output
    }

    func diagnostics() async -> String { lastDiagnostic }

    private func ensureReceiver() {
        guard receiverTask == nil else { return }
        receiverTask = Task {
            let stream = await transport.receivedBytes()
            for await bytes in stream { self.ingest(bytes) }
        }
        connectionTask = Task {
            let stream = await transport.connectionStates()
            for await state in stream {
                switch state {
                case .disconnected, .failed: self.handleDisconnect()
                default: break
                }
            }
        }
    }

    private func request(timeout: TimeInterval = 8, configure: (inout PB_Main) -> Void) async throws -> [PB_Main] {
        var main = PB_Main()
        configure(&main)
        return try await request(main: main, timeout: timeout)
    }

    private func request(main initial: PB_Main, timeout: TimeInterval) async throws -> [PB_Main] {
        var main = initial
        let id = allocateCommandID()
        main.commandID = id
        return try await sendAndAwait(id: id, packets: [try framed(main)], timeout: timeout)
    }

    private func sendAndAwait(id: UInt32, packets: [Data], timeout: TimeInterval) async throws -> [PB_Main] {
        ensureReceiver()
        return try await withCheckedThrowingContinuation { continuation in
            pending[id] = PendingResponse(messages: [], continuation: continuation)
            Task {
                do {
                    for packet in packets { try await transport.send(packet) }
                } catch {
                    self.fail(id: id, error: error)
                }
            }
            Task {
                try? await Task.sleep(for: .seconds(timeout))
                self.timeout(id: id)
            }
        }
    }

    private func ingest(_ bytes: Data) {
        if rawMode {
            rawBuffer.append(bytes)
            let text = String(decoding: rawBuffer, as: UTF8.self)
            if text.contains("\r\n>: ") || text.hasSuffix(">: ") || text.hasSuffix("\r\n> ") {
                rawContinuation?.resume(returning: cleanCLI(text))
                rawContinuation = nil
                rawBuffer.removeAll()
            }
            return
        }
        do {
            for message in try frameDecoder.append(bytes) { accept(message) }
        } catch {
            lastDiagnostic = error.localizedDescription
            frameDecoder.reset()
        }
    }

    private func accept(_ message: PB_Main) {
        guard var value = pending[message.commandID] else { return }
        value.messages.append(message)
        if message.commandStatus != .ok {
            pending.removeValue(forKey: message.commandID)
            value.continuation.resume(throwing: RPCError.status(message.commandStatus.rawValue))
        } else if !message.hasNext_p {
            pending.removeValue(forKey: message.commandID)
            lastDiagnostic = "RPC ready; command \(message.commandID) completed"
            value.continuation.resume(returning: value.messages)
        } else {
            pending[message.commandID] = value
        }
    }

    private func framed(_ message: PB_Main) throws -> Data {
        let payload = try message.serializedData()
        return Varint.encode(UInt64(payload.count)) + payload
    }

    private func storageInfo(path: String) async throws -> (total: UInt64, free: UInt64) {
        var value = PBStorage_InfoRequest(); value.path = path
        let responses = try await request { $0.storageInfoRequest = value }
        guard let response = responses.compactMap({ message -> PBStorage_InfoResponse? in
            guard case .storageInfoResponse(let value)? = message.content else { return nil }; return value
        }).last else { throw RPCError.emptyResponse }
        return (response.totalSpace, response.freeSpace)
    }

    private func pairs(_ messages: [PB_Main], extract: (PB_Main) -> (String, String)?) -> [String: String] {
        Dictionary(uniqueKeysWithValues: messages.compactMap(extract).map { ($0.0.lowercased(), $0.1) })
    }
    private func first(_ values: [String: String], keys: [String]) -> String? { keys.compactMap { values[$0] }.first { !$0.isEmpty } }
    private func percent(_ values: [String: String], keys: [String]) -> Int? { first(values, keys: keys).flatMap { Int($0.filter(\.isNumber)) } }
    private func bool(_ values: [String: String], keys: [String]) -> Bool? {
        guard let value = first(values, keys: keys)?.lowercased() else { return nil }
        if ["true", "yes", "1", "charging"].contains(value) { return true }
        if ["false", "no", "0", "discharging"].contains(value) { return false }
        return nil
    }
    private func cleanCLI(_ value: String) -> String {
        value.replacingOccurrences(of: "\0", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private func allocateCommandID() -> UInt32 { defer { nextCommandID = nextCommandID == UInt32.max ? 1 : nextCommandID + 1 }; return nextCommandID }
    private func fail(id: UInt32, error: Error) { pending.removeValue(forKey: id)?.continuation.resume(throwing: error) }
    private func timeout(id: UInt32) { pending.removeValue(forKey: id)?.continuation.resume(throwing: VesperCoreError.responseTimeout) }
    private func handleDisconnect() {
        frameDecoder.reset()
        rawBuffer.removeAll()
        rawMode = false
        rawContinuation?.resume(throwing: VesperCoreError.disconnected)
        rawContinuation = nil
        let outstanding = pending.values
        pending.removeAll()
        for response in outstanding { response.continuation.resume(throwing: VesperCoreError.disconnected) }
        lastDiagnostic = "RPC disconnected; pending commands cancelled"
    }
    private func failRaw(_ error: Error) { rawContinuation?.resume(throwing: error); rawContinuation = nil; rawMode = false }
    private func timeoutRaw() { guard let continuation = rawContinuation else { return }; rawContinuation = nil; rawMode = false; continuation.resume(throwing: VesperCoreError.responseTimeout) }
}

enum RPCError: LocalizedError {
    case emptyResponse, status(Int)
    var errorDescription: String? {
        switch self {
        case .emptyResponse: "Flipper returned no RPC response."
        case .status(let value): "Flipper RPC failed with status \(value)."
        }
    }
}

enum Varint {
    static func encode(_ value: UInt64) -> Data {
        var value = value
        var bytes: [UInt8] = []
        repeat {
            var byte = UInt8(value & 0x7f)
            value >>= 7
            if value != 0 { byte |= 0x80 }
            bytes.append(byte)
        } while value != 0
        return Data(bytes)
    }

    static func decode(_ data: Data) -> (value: UInt64, length: Int)? {
        var value: UInt64 = 0
        for (index, byte) in data.prefix(10).enumerated() {
            value |= UInt64(byte & 0x7f) << UInt64(index * 7)
            if byte & 0x80 == 0 { return (value, index + 1) }
        }
        return nil
    }
}

struct RPCFrameDecoder {
    private var buffer = Data()

    mutating func append(_ bytes: Data) throws -> [PB_Main] {
        buffer.append(bytes)
        var output: [PB_Main] = []
        while !buffer.isEmpty {
            guard let prefix = Varint.decode(buffer) else {
                if buffer.count >= 10 { throw VesperCoreError.malformedFrame }
                break
            }
            guard prefix.value > 0, prefix.value <= 10 * 1024 * 1024 else { throw VesperCoreError.malformedFrame }
            let end = prefix.length + Int(prefix.value)
            guard buffer.count >= end else { break }
            output.append(try PB_Main(serializedBytes: buffer.subdata(in: prefix.length..<end)))
            buffer.removeSubrange(0..<end)
        }
        return output
    }

    mutating func reset() { buffer.removeAll(keepingCapacity: true) }
}
