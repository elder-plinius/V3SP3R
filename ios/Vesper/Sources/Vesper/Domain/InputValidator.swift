// InputValidator.swift
// Vesper - AI-powered Flipper Zero controller
// Validates and sanitizes LLM output before execution

import Foundation

// MARK: - Validation Errors

enum InputValidationError: LocalizedError, Sendable {
    case invalidPath(String)
    case pathTraversal
    case nullByteDetected
    case contentTooLarge(Int)
    case injectionDetected(String)

    var errorDescription: String? {
        switch self {
        case .invalidPath(let detail):
            return "Invalid path: \(detail)"
        case .pathTraversal:
            return "Path traversal attempt detected (contains '..')"
        case .nullByteDetected:
            return "Null byte detected in path"
        case .contentTooLarge(let size):
            return "Content too large: \(size) bytes exceeds 10 MB limit"
        case .injectionDetected(let detail):
            return "Injection attempt detected: \(detail)"
        }
    }
}

// MARK: - Input Validator

/// Validates and sanitizes all LLM-generated input before it reaches the command executor.
/// Acts as a security boundary between the AI layer and the execution layer.
enum InputValidator {

    // MARK: - Constants

    /// Maximum content size: 10 MB
    static let maxContentSizeBytes = 10 * 1024 * 1024

    /// Minimum API key length for OpenRouter keys
    private static let minAPIKeyLength = 20

    /// API key prefix for OpenRouter
    private static let apiKeyPrefix = "sk-or-"

    /// Valid Flipper path prefixes
    private static let validPathPrefixes = ["/ext/", "/int/"]

    /// Known injection patterns to detect in LLM output
    private static let injectionPatterns: [String] = [
        "$(", "`",                             // Shell command substitution
        "&&", "||", ";",                       // Shell command chaining
        "|",                                   // Pipe
        "> ", ">> ",                           // Redirect
        "< ",                                  // Input redirect
        "\\x00", "\\0",                        // Null byte literals
        "%00",                                 // URL-encoded null
        "../",                                 // Path traversal
        "..\\",                                // Windows path traversal
        "\n", "\r",                            // Newline injection in commands
    ]

    /// Patterns specifically dangerous in CLI commands
    private static let cliInjectionPatterns: [String] = [
        "$(", "`",
        "&&", "||",
        "; ",
        "| ",
        "> ", ">> ",
        "< ",
    ]

    // MARK: - API Key Validation

    /// Validates that a string looks like a valid OpenRouter API key.
    /// Checks prefix format and minimum length; does not verify against the API.
    static func isValidApiKey(_ key: String) -> Bool {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= minAPIKeyLength else { return false }
        guard trimmed.hasPrefix(apiKeyPrefix) else { return false }
        // Ensure only valid characters (alphanumeric, hyphens, underscores)
        let allowedCharacters = CharacterSet.alphanumerics
            .union(CharacterSet(charactersIn: "-_"))
        guard trimmed.unicodeScalars.allSatisfy({ allowedCharacters.contains($0) }) else {
            return false
        }
        return true
    }

    // MARK: - Command Sanitization

    /// Sanitizes a command by cleaning up paths, stripping injection attempts from
    /// CLI commands, and normalizing arguments. Returns a cleaned copy of the command.
    static func sanitizeCommand(_ command: ExecuteCommand) -> ExecuteCommand {
        var args = command.args

        // Sanitize path fields
        if let path = args.path {
            args = CommandArgs(
                command: args.command,
                path: sanitizePath(path),
                destinationPath: args.destinationPath.map(sanitizePath),
                content: args.content,
                newName: args.newName.map(sanitizeName),
                recursive: args.recursive,
                artifactType: args.artifactType,
                artifactData: args.artifactData,
                prompt: args.prompt,
                resourceType: args.resourceType,
                runbookId: args.runbookId,
                payloadType: args.payloadType,
                filter: args.filter,
                appName: args.appName.map(sanitizeName),
                appArgs: args.appArgs,
                frequency: args.frequency,
                protocol: args.protocol,
                address: args.address,
                signalName: args.signalName,
                enabled: args.enabled,
                red: args.red.map { max(0, min(255, $0)) },
                green: args.green.map { max(0, min(255, $0)) },
                blue: args.blue.map { max(0, min(255, $0)) },
                repoId: args.repoId,
                subPath: args.subPath,
                downloadUrl: args.downloadUrl,
                searchScope: args.searchScope,
                photoPrompt: args.photoPrompt
            )
        } else {
            // Still sanitize destination path and name fields even without primary path
            if let destPath = args.destinationPath {
                args = CommandArgs(
                    command: args.command,
                    path: args.path,
                    destinationPath: sanitizePath(destPath),
                    content: args.content,
                    newName: args.newName.map(sanitizeName),
                    recursive: args.recursive,
                    artifactType: args.artifactType,
                    artifactData: args.artifactData,
                    prompt: args.prompt,
                    resourceType: args.resourceType,
                    runbookId: args.runbookId,
                    payloadType: args.payloadType,
                    filter: args.filter,
                    appName: args.appName.map(sanitizeName),
                    appArgs: args.appArgs,
                    frequency: args.frequency,
                    protocol: args.protocol,
                    address: args.address,
                    signalName: args.signalName,
                    enabled: args.enabled,
                    red: args.red.map { max(0, min(255, $0)) },
                    green: args.green.map { max(0, min(255, $0)) },
                    blue: args.blue.map { max(0, min(255, $0)) },
                    repoId: args.repoId,
                    subPath: args.subPath,
                    downloadUrl: args.downloadUrl,
                    searchScope: args.searchScope,
                    photoPrompt: args.photoPrompt
                )
            }
        }

        // Sanitize CLI command content
        if command.action == .executeCli, let cliCommand = args.command {
            args = CommandArgs(
                command: sanitizeCliCommand(cliCommand),
                path: args.path,
                destinationPath: args.destinationPath,
                content: args.content,
                newName: args.newName,
                recursive: args.recursive,
                artifactType: args.artifactType,
                artifactData: args.artifactData,
                prompt: args.prompt,
                resourceType: args.resourceType,
                runbookId: args.runbookId,
                payloadType: args.payloadType,
                filter: args.filter,
                appName: args.appName,
                appArgs: args.appArgs,
                frequency: args.frequency,
                protocol: args.protocol,
                address: args.address,
                signalName: args.signalName,
                enabled: args.enabled,
                red: args.red,
                green: args.green,
                blue: args.blue,
                repoId: args.repoId,
                subPath: args.subPath,
                downloadUrl: args.downloadUrl,
                searchScope: args.searchScope,
                photoPrompt: args.photoPrompt
            )
        }

        return ExecuteCommand(
            action: command.action,
            args: args,
            justification: command.justification,
            expectedEffect: command.expectedEffect
        )
    }

    // MARK: - Path Validation

    /// Validates a Flipper path. Returns the normalized path or throws on invalid input.
    /// - The path must start with /ext/ or /int/
    /// - Must not contain ".." (path traversal)
    /// - Must not contain null bytes
    static func validatePath(_ path: String) throws -> String {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)

        // Check for null bytes
        guard !trimmed.contains("\0") else {
            throw InputValidationError.nullByteDetected
        }

        // Check for path traversal
        guard !trimmed.contains("..") else {
            throw InputValidationError.pathTraversal
        }

        // Must start with a valid Flipper prefix
        let hasValidPrefix = validPathPrefixes.contains(where: { trimmed.hasPrefix($0) })
        // Also allow bare /ext and /int (root-level listing)
        let isBareRoot = trimmed == "/ext" || trimmed == "/int"
        guard hasValidPrefix || isBareRoot else {
            throw InputValidationError.invalidPath(
                "Path must start with /ext/ or /int/. Got: \(trimmed)"
            )
        }

        // Normalize double slashes
        var normalized = trimmed
        while normalized.contains("//") {
            normalized = normalized.replacingOccurrences(of: "//", with: "/")
        }

        // Remove trailing slash unless it's a root path
        if normalized.count > 1 && normalized.hasSuffix("/") {
            normalized = String(normalized.dropLast())
        }

        return normalized
    }

    // MARK: - Content Size Validation

    /// Validates that string content does not exceed the maximum size limit.
    static func validateContentSize(_ content: String) throws {
        let size = content.utf8.count
        guard size <= maxContentSizeBytes else {
            throw InputValidationError.contentTooLarge(size)
        }
    }

    /// Validates that binary content does not exceed the maximum size limit.
    static func validateContentSize(_ content: Data) throws {
        let size = content.count
        guard size <= maxContentSizeBytes else {
            throw InputValidationError.contentTooLarge(size)
        }
    }

    // MARK: - Injection Detection

    /// Checks if text contains common injection patterns.
    /// Returns true if any suspicious patterns are found.
    static func containsInjection(_ text: String) -> Bool {
        for pattern in injectionPatterns {
            if text.contains(pattern) {
                return true
            }
        }
        return false
    }

    // MARK: - Private Helpers

    /// Sanitize a file path by removing traversal attempts and normalizing.
    private static func sanitizePath(_ path: String) -> String {
        var sanitized = path.trimmingCharacters(in: .whitespacesAndNewlines)

        // Remove null bytes
        sanitized = sanitized.replacingOccurrences(of: "\0", with: "")
        sanitized = sanitized.replacingOccurrences(of: "%00", with: "")

        // Remove path traversal sequences
        sanitized = sanitized.replacingOccurrences(of: "../", with: "")
        sanitized = sanitized.replacingOccurrences(of: "..\\", with: "")

        // Collapse repeated separators
        while sanitized.contains("//") {
            sanitized = sanitized.replacingOccurrences(of: "//", with: "/")
        }

        // Remove trailing slash (unless root)
        if sanitized.count > 1 && sanitized.hasSuffix("/") {
            sanitized = String(sanitized.dropLast())
        }

        return sanitized
    }

    /// Sanitize a file or app name (no slashes, no traversal).
    private static func sanitizeName(_ name: String) -> String {
        var sanitized = name.trimmingCharacters(in: .whitespacesAndNewlines)
        sanitized = sanitized.replacingOccurrences(of: "/", with: "")
        sanitized = sanitized.replacingOccurrences(of: "\\", with: "")
        sanitized = sanitized.replacingOccurrences(of: "\0", with: "")
        sanitized = sanitized.replacingOccurrences(of: "..", with: "")
        return sanitized
    }

    /// Sanitize a CLI command string by removing dangerous shell metacharacters.
    private static func sanitizeCliCommand(_ command: String) -> String {
        var sanitized = command.trimmingCharacters(in: .whitespacesAndNewlines)

        // Remove null bytes
        sanitized = sanitized.replacingOccurrences(of: "\0", with: "")

        // Strip shell injection patterns
        for pattern in cliInjectionPatterns {
            sanitized = sanitized.replacingOccurrences(of: pattern, with: " ")
        }

        // Remove backticks
        sanitized = sanitized.replacingOccurrences(of: "`", with: "")

        // Collapse whitespace
        while sanitized.contains("  ") {
            sanitized = sanitized.replacingOccurrences(of: "  ", with: " ")
        }

        return sanitized.trimmingCharacters(in: .whitespaces)
    }
}
