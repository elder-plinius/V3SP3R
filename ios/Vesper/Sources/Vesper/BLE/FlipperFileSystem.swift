// FlipperFileSystem.swift
// Vesper - AI-powered Flipper Zero controller
// High-level file operations wrapping FlipperProtocol with path validation and CLI fallback

import Foundation
import os.log

private let logger = Logger(subsystem: "com.vesper.flipper", category: "FileSystem")

class FlipperFileSystem {

    private let flipperProtocol: FlipperProtocol

    // MARK: - Constants

    private static let maxCliCommandLength = 512
    private static let maxContentSize = 256 * 1024 // 256 KB
    private static let allowedPathPrefixes = ["/ext/", "/int/", "/ext", "/int"]
    private static let batteryMinMV = 3_300
    private static let batteryMaxMV = 4_200

    init(protocol flipperProtocol: FlipperProtocol) {
        self.flipperProtocol = flipperProtocol
    }

    // MARK: - Public API

    func listDirectory(_ path: String) async throws -> [FileEntry] {
        let validPath = try validatePath(path)
        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.listDirectory(path: validPath)) },
            cliCommand: "storage list \(validPath)"
        )

        switch response {
        case .directoryList(let entries):
            return entries.map { entry in
                FileEntry(
                    name: entry.name,
                    path: normalizePath("\(validPath)/\(entry.name)"),
                    isDirectory: entry.isDirectory,
                    size: entry.size
                )
            }
        case .fileContent(let content):
            return parseCliDirectoryListing(content, basePath: validPath)
        case .binaryContent(let data):
            let content = String(data: data, encoding: .utf8) ?? ""
            return parseCliDirectoryListing(content, basePath: validPath)
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func readFile(_ path: String) async throws -> String {
        let validPath = try validatePath(path)
        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.readFile(path: validPath)) },
            cliCommand: "storage read \(validPath)"
        )

        switch response {
        case .fileContent(let content):
            return content
        case .binaryContent(let data):
            return String(data: data, encoding: .utf8) ?? ""
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func readFileBytes(_ path: String) async throws -> Data {
        let validPath = try validatePath(path)
        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.readFile(path: validPath)) },
            cliCommand: "storage read \(validPath)"
        )

        switch response {
        case .binaryContent(let data):
            return data
        case .fileContent(let content):
            return content.data(using: .utf8) ?? Data()
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func writeFile(_ path: String, content: String) async throws -> Int64 {
        let bytes = content.data(using: .utf8) ?? Data()
        return try await writeFileBytes(path, content: bytes)
    }

    func writeFileBytes(_ path: String, content: Data) async throws -> Int64 {
        let validPath = try validatePath(path)
        try validateContentSize(content)

        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.writeFile(path: validPath, data: content)) },
            cliCommand: "storage write \(validPath)"
        )

        switch response {
        case .success:
            return Int64(content.count)
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func createDirectory(_ path: String) async throws {
        let validPath = try validatePath(path)
        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.createDirectory(path: validPath)) },
            cliCommand: "storage mkdir \(validPath)"
        )

        switch response {
        case .success, .fileContent:
            return
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func delete(_ path: String, recursive: Bool = false) async throws {
        let validPath = try validatePath(path)
        let cliCommand = recursive
            ? "storage remove_recursive \(validPath)"
            : "storage remove \(validPath)"

        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.deleteFile(path: validPath, recursive: recursive)) },
            cliCommand: cliCommand
        )

        switch response {
        case .success, .fileContent:
            return
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func move(from sourcePath: String, to destPath: String) async throws {
        let validSource = try validatePath(sourcePath)
        let validDest = try validatePath(destPath)

        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.move(sourcePath: validSource, destPath: validDest)) },
            cliCommand: "storage move \(validSource) \(validDest)"
        )

        switch response {
        case .success, .fileContent:
            return
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func copy(from sourcePath: String, to destPath: String) async throws {
        let validSource = try validatePath(sourcePath)
        let validDest = try validatePath(destPath)

        // Prefer device-native copy via CLI
        let cliResponse: ProtocolResponse
        do {
            cliResponse = try await flipperProtocol.sendCommand(.cli(command: "storage copy \(validSource) \(validDest)"))
        } catch {
            cliResponse = .error("CLI copy failed: \(error.localizedDescription)", nil)
        }

        switch cliResponse {
        case .success, .fileContent, .binaryContent:
            return
        default:
            break
        }

        // Fallback: read source and write to destination
        let content = try await readFileBytes(validSource)
        _ = try await writeFileBytes(validDest, content: content)
    }

    func rename(path: String, newName: String) async throws {
        let sanitizedName = sanitizeFileName(newName)
        guard !sanitizedName.isEmpty else {
            throw FlipperException(message: "Invalid file name: \(newName)")
        }

        let parentPath: String
        if let lastSlash = path.lastIndex(of: "/") {
            parentPath = String(path[path.startIndex..<lastSlash])
        } else {
            parentPath = ""
        }

        let newPath = "\(parentPath)/\(sanitizedName)"
        try await move(from: path, to: newPath)
    }

    func getDeviceInfo() async throws -> DeviceInfo {
        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.getDeviceInfo) },
            cliCommand: "device_info"
        )

        switch response {
        case .deviceInfo(let info):
            return info
        case .fileContent(let content):
            return parseCliDeviceInfo(content)
        case .binaryContent(let data):
            let content = String(data: data, encoding: .utf8) ?? ""
            return parseCliDeviceInfo(content)
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func getStorageInfo() async throws -> StorageInfo {
        let response = await withCliFallback(
            primary: { try await self.flipperProtocol.sendCommand(.getStorageInfo) },
            cliCommand: "storage info"
        )

        switch response {
        case .storageInfo(let info):
            return info
        case .fileContent(let content):
            return parseCliStorageInfo(content)
        case .binaryContent(let data):
            let content = String(data: data, encoding: .utf8) ?? ""
            return parseCliStorageInfo(content)
        case .error(let msg, let code):
            throw FlipperException(message: msg, code: code ?? -1)
        default:
            throw FlipperException(message: "Unexpected response type")
        }
    }

    func sendCliCommand(_ command: String) async throws -> String {
        let validated = try validateCliCommand(command)
        return try await flipperProtocol.sendCliCommand(validated)
    }

    // MARK: - CLI Fallback Pattern

    /// Execute the primary RPC command and fall back to CLI if the error suggests RPC is unavailable.
    private func withCliFallback(
        primary: () async throws -> ProtocolResponse,
        cliCommand: String
    ) async -> ProtocolResponse {
        let response: ProtocolResponse
        do {
            response = try await primary()
        } catch {
            response = .error("Primary command failed: \(error.localizedDescription)", nil)
        }

        if case .error(let message, _) = response, shouldUseCliFallback(message) {
            logger.info("RPC failed, falling back to CLI: \(cliCommand)")
            do {
                return try await flipperProtocol.sendCommand(.cli(command: cliCommand))
            } catch {
                return .error("CLI fallback also failed: \(error.localizedDescription)", nil)
            }
        }

        return response
    }

    private func shouldUseCliFallback(_ message: String) -> Bool {
        let normalized = message.lowercased()
        return normalized.contains("non-protocol response") ||
            normalized.contains("invalid protocol frame") ||
            normalized.contains("timed out") ||
            normalized.contains("unknown response type") ||
            normalized.contains("no rpc response") ||
            normalized.contains("rpc transport is unavailable") ||
            normalized.contains("rpc transport unavailable") ||
            normalized.contains("rpc ping did not respond")
    }

    // MARK: - Path Validation

    /// Validate and normalize a Flipper storage path.
    /// Rejects paths with "..", null bytes, and paths not starting with /ext/ or /int/.
    func validatePath(_ path: String) throws -> String {
        // Check for null bytes
        guard !path.contains("\0") else {
            throw FlipperException(message: "Path contains null bytes")
        }

        // Check for path traversal
        guard !path.contains("..") else {
            throw FlipperException(message: "Path traversal ('..') is not allowed")
        }

        // Normalize the path
        let normalized = normalizePath(path)

        // Must start with /ext/ or /int/
        guard Self.allowedPathPrefixes.contains(where: { normalized.hasPrefix($0) }) else {
            throw FlipperException(message: "Path must start with /ext/ or /int/: \(normalized)")
        }

        // Check for empty segments
        if normalized.contains("//") {
            throw FlipperException(message: "Path contains empty segments: \(normalized)")
        }

        return normalized
    }

    private func validateContentSize(_ content: Data) throws {
        guard content.count <= Self.maxContentSize else {
            throw FlipperException(message: "Content exceeds maximum size of \(Self.maxContentSize) bytes (got \(content.count))")
        }
    }

    private func validateCliCommand(_ command: String) throws -> String {
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !trimmed.isEmpty else {
            throw FlipperException(message: "CLI command cannot be empty")
        }

        guard trimmed.count <= Self.maxCliCommandLength else {
            throw FlipperException(message: "CLI command exceeds maximum length of \(Self.maxCliCommandLength) characters")
        }

        // Check for null bytes
        guard !trimmed.contains("\0") else {
            throw FlipperException(message: "CLI command contains null bytes")
        }

        return trimmed
    }

    private func sanitizeFileName(_ name: String) -> String {
        // Remove path separators and dangerous characters
        var sanitized = name
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: "\0", with: "")
            .replacingOccurrences(of: "..", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // Limit length
        if sanitized.count > 255 {
            sanitized = String(sanitized.prefix(255))
        }

        return sanitized
    }

    private func normalizePath(_ path: String) -> String {
        var result = path
        // Collapse multiple slashes
        while result.contains("//") {
            result = result.replacingOccurrences(of: "//", with: "/")
        }
        // Remove trailing slash (unless it's just "/")
        if result.count > 1 && result.hasSuffix("/") {
            result = String(result.dropLast())
        }
        return result
    }

    // MARK: - CLI Response Parsing

    private func parseCliDirectoryListing(_ content: String, basePath: String) -> [FileEntry] {
        let lines = content
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .filter { !$0.hasPrefix(">") && !$0.hasPrefix("storage>") }

        return lines.map { line in
            let isDirectory = line.hasPrefix("[D]") || line.hasSuffix("/")
            let cleaned = line
                .replacingOccurrences(of: "[D]", with: "")
                .replacingOccurrences(of: "[F]", with: "")
                .trimmingCharacters(in: .whitespaces)

            // Name is everything before the last space (which is the size)
            let parts = cleaned.split(separator: " ", maxSplits: .max, omittingEmptySubsequences: true)
            let name: String
            if parts.count > 1, let _ = Int64(String(parts.last!)) {
                name = parts.dropLast().joined(separator: " ")
            } else {
                name = cleaned
            }

            return FileEntry(
                name: name,
                path: normalizePath("\(basePath)/\(name)"),
                isDirectory: isDirectory,
                size: 0
            )
        }
    }

    private func parseCliDeviceInfo(_ content: String) -> DeviceInfo {
        // Parse firmware version
        let firmwareRegex = try? NSRegularExpression(
            pattern: "(?im)^(?:firmware(?:_version)?|version|fw)\\s*[:=]\\s*([^\\r\\n]+)$"
        )
        let fwMatch = firmwareRegex?.firstMatch(
            in: content,
            range: NSRange(content.startIndex..., in: content)
        )
        var firmware = "unknown"
        if let fwMatch, let range = Range(fwMatch.range(at: 1), in: content) {
            firmware = String(content[range]).trimmingCharacters(in: .whitespaces)
        } else {
            // Fallback regex
            let fallbackRegex = try? NSRegularExpression(
                pattern: "(?i)(firmware|version)\\D+([\\w.\\-]+)"
            )
            if let match = fallbackRegex?.firstMatch(in: content, range: NSRange(content.startIndex..., in: content)),
               let range = Range(match.range(at: 2), in: content) {
                firmware = String(content[range]).trimmingCharacters(in: .whitespaces)
            }
        }

        // Parse battery level
        let batteryLevel = parseCliBatteryPercent(content)
            ?? parseCliBatteryPercentFromVoltage(content)
            ?? 0

        // Parse charging status
        let chargingRegex = try? NSRegularExpression(
            pattern: "(?i)\\b(charging|charger_connected\\s*[:=]\\s*(?:1|true|yes)|charge_state\\s*[:=]\\s*charging)\\b"
        )
        let isCharging = chargingRegex?.firstMatch(
            in: content,
            range: NSRange(content.startIndex..., in: content)
        ) != nil

        return DeviceInfo(
            name: "Flipper Zero",
            firmwareVersion: firmware,
            hardwareVersion: "unknown",
            batteryLevel: min(max(batteryLevel, 0), 100),
            isCharging: isCharging
        )
    }

    private func parseCliBatteryPercent(_ content: String) -> Int? {
        // Key-value format: battery_level: 85
        let kvRegex = try? NSRegularExpression(
            pattern: "(?im)^(?:battery_level|charge_percent|charge_level|capacity_percent|battery|charge)\\s*[:=]\\s*(\\d{1,3})\\s*%?\\s*$"
        )
        if let match = kvRegex?.firstMatch(in: content, range: NSRange(content.startIndex..., in: content)),
           let range = Range(match.range(at: 1), in: content),
           let value = Int(content[range]),
           (0...100).contains(value) {
            return value
        }

        // Inline percent format: battery 85%
        let inlineRegex = try? NSRegularExpression(
            pattern: "(?i)(?:battery|charge|capacity)[^\\r\\n\\d]{0,16}(\\d{1,3})\\s*%"
        )
        if let match = inlineRegex?.firstMatch(in: content, range: NSRange(content.startIndex..., in: content)),
           let range = Range(match.range(at: 1), in: content),
           let value = Int(content[range]),
           (0...100).contains(value) {
            return value
        }

        return nil
    }

    private func parseCliBatteryPercentFromVoltage(_ content: String) -> Int? {
        let voltageRegex = try? NSRegularExpression(
            pattern: "(?i)(?:battery_voltage|power_voltage|vbat|vbatt|voltage)\\s*[:=]?\\s*(-?\\d+(?:\\.\\d+)?)\\s*(mv|mV|volt|volts|v)?"
        )

        guard let regex = voltageRegex else { return nil }

        let matches = regex.matches(in: content, range: NSRange(content.startIndex..., in: content))
        for match in matches {
            guard let valueRange = Range(match.range(at: 1), in: content),
                  let value = Double(content[valueRange]),
                  value > 0 else {
                continue
            }

            let unit: String
            if let unitRange = Range(match.range(at: 2), in: content) {
                unit = String(content[unitRange]).lowercased()
            } else {
                unit = ""
            }

            let mv: Int
            switch unit {
            case "mv":
                mv = Int(value)
            case "v", "volt", "volts":
                mv = Int(value * 1000.0)
            default:
                if value >= 2.5 && value <= 5.5 {
                    mv = Int(value * 1000.0)
                } else if value >= 1000 && value <= 5000 {
                    mv = Int(value)
                } else {
                    continue
                }
            }

            let clampedMv = min(max(mv, Self.batteryMinMV), Self.batteryMaxMV)
            let percent = Int(Double(clampedMv - Self.batteryMinMV) / Double(Self.batteryMaxMV - Self.batteryMinMV) * 100.0)
            return min(max(percent, 0), 100)
        }

        return nil
    }

    private func parseCliStorageInfo(_ content: String) -> StorageInfo {
        let totalRegex = try? NSRegularExpression(pattern: "(?i)(total|size)\\D+(\\d+)")
        let freeRegex = try? NSRegularExpression(pattern: "(?i)(free|available)\\D+(\\d+)")

        var total: Int64 = 0
        var free: Int64 = 0

        if let match = totalRegex?.firstMatch(in: content, range: NSRange(content.startIndex..., in: content)),
           let range = Range(match.range(at: 2), in: content) {
            total = Int64(content[range]) ?? 0
        }

        if let match = freeRegex?.firstMatch(in: content, range: NSRange(content.startIndex..., in: content)),
           let range = Range(match.range(at: 2), in: content) {
            free = Int64(content[range]) ?? 0
        }

        let hasSdCard = content.range(of: "sd", options: .caseInsensitive) != nil

        return StorageInfo(
            internalTotal: total,
            internalFree: free,
            hasSdCard: hasSdCard
        )
    }
}

// MARK: - FlipperException

struct FlipperException: Error, LocalizedError {
    let message: String
    let code: Int

    init(message: String, code: Int = -1) {
        self.message = message
        self.code = code
    }

    var errorDescription: String? {
        if code >= 0 {
            return "\(message) (code: \(code))"
        }
        return message
    }
}
