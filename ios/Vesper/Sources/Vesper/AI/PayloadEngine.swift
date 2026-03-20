// PayloadEngine.swift
// Vesper - AI-powered Flipper Zero controller
// AI payload generation and validation for Flipper Zero file formats

import Foundation

// MARK: - Types

struct GeneratedPayload: Sendable {
    let type: String
    let content: String
    let filename: String
    let description: String
}

struct PayloadValidation: Sendable {
    let isValid: Bool
    let errors: [String]
    let warnings: [String]
}

// MARK: - Payload Engine

/// Generates and validates Flipper Zero payloads using AI.
/// Supports Sub-GHz, IR, NFC, RFID, iButton, and BadUSB file formats.
final class PayloadEngine {

    private let openRouterClient: OpenRouterClient
    private let settingsStore: SettingsStore

    init(openRouterClient: OpenRouterClient, settingsStore: SettingsStore) {
        self.openRouterClient = openRouterClient
        self.settingsStore = settingsStore
    }

    // MARK: - Supported Types

    enum PayloadType: String, CaseIterable {
        case subghz = "subghz"
        case ir = "ir"
        case nfc = "nfc"
        case rfid = "rfid"
        case ibutton = "ibutton"
        case badusb = "badusb"

        var fileExtension: String {
            switch self {
            case .subghz: return ".sub"
            case .ir: return ".ir"
            case .nfc: return ".nfc"
            case .rfid: return ".rfid"
            case .ibutton: return ".ibtn"
            case .badusb: return ".txt"
            }
        }

        var flipperDirectory: String {
            switch self {
            case .subghz: return "/ext/subghz/"
            case .ir: return "/ext/infrared/"
            case .nfc: return "/ext/nfc/"
            case .rfid: return "/ext/lfrfid/"
            case .ibutton: return "/ext/ibutton/"
            case .badusb: return "/ext/badusb/"
            }
        }

        var displayName: String {
            switch self {
            case .subghz: return "Sub-GHz"
            case .ir: return "Infrared"
            case .nfc: return "NFC"
            case .rfid: return "RFID"
            case .ibutton: return "iButton"
            case .badusb: return "BadUSB"
            }
        }
    }

    // MARK: - Generation

    /// Generate a Flipper Zero payload using AI.
    /// - Parameters:
    ///   - type: The payload type (subghz, ir, nfc, rfid, ibutton, badusb).
    ///   - prompt: Natural language description of what to generate.
    /// - Returns: The generated payload with content, filename, and description.
    func generatePayload(type: String, prompt: String) async throws -> GeneratedPayload {
        guard let payloadType = PayloadType(rawValue: type.lowercased()) else {
            throw PayloadError.unsupportedType(type)
        }

        let systemPrompt = buildGenerationPrompt(for: payloadType)
        let userPrompt = """
        Generate a Flipper Zero \(payloadType.displayName) payload file for this request:
        \(prompt)

        Output ONLY the raw file content. No markdown, no explanations, no code blocks.
        Start directly with the file header (e.g., "Filetype:" for signal files, "REM" for BadUSB).
        """

        let messages: [ChatMessage] = [
            ChatMessage(role: .system, content: systemPrompt),
            ChatMessage(role: .user, content: userPrompt)
        ]

        let result = await openRouterClient.chat(
            messages: messages,
            sessionId: "payload-gen-\(UUID().uuidString)"
        )

        switch result {
        case .error(let error):
            throw PayloadError.generationFailed(error)

        case .success(let response):
            guard let content = response.content, !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw PayloadError.emptyResponse
            }

            // Clean up the response -- strip markdown code blocks if present
            let cleanContent = stripMarkdownCodeBlocks(content)

            // Generate filename from prompt
            let sanitizedName = sanitizeFilename(from: prompt)
            let filename = sanitizedName + payloadType.fileExtension

            return GeneratedPayload(
                type: payloadType.rawValue,
                content: cleanContent,
                filename: filename,
                description: "AI-generated \(payloadType.displayName) payload: \(prompt.prefix(100))"
            )
        }
    }

    // MARK: - Validation

    /// Validate a generated payload for format correctness.
    /// Checks file headers, required fields, and format-specific rules.
    func validatePayload(_ payload: GeneratedPayload) -> PayloadValidation {
        guard let payloadType = PayloadType(rawValue: payload.type.lowercased()) else {
            return PayloadValidation(
                isValid: false,
                errors: ["Unknown payload type: \(payload.type)"],
                warnings: []
            )
        }

        var errors: [String] = []
        var warnings: [String] = []
        let content = payload.content
        let lines = content.components(separatedBy: .newlines)

        switch payloadType {
        case .subghz:
            validateSubGhz(lines: lines, errors: &errors, warnings: &warnings)
        case .ir:
            validateInfrared(lines: lines, errors: &errors, warnings: &warnings)
        case .nfc:
            validateNFC(lines: lines, errors: &errors, warnings: &warnings)
        case .rfid:
            validateRFID(lines: lines, errors: &errors, warnings: &warnings)
        case .ibutton:
            validateIButton(lines: lines, errors: &errors, warnings: &warnings)
        case .badusb:
            validateBadUSB(lines: lines, errors: &errors, warnings: &warnings)
        }

        // General size check
        let byteSize = content.utf8.count
        if byteSize > 100_000 {
            warnings.append("Payload is \(byteSize) bytes. Large files may be slow to transfer over BLE.")
        }

        return PayloadValidation(
            isValid: errors.isEmpty,
            errors: errors,
            warnings: warnings
        )
    }

    // MARK: - Sub-GHz Validation

    private func validateSubGhz(lines: [String], errors: inout [String], warnings: inout [String]) {
        let nonEmpty = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard !nonEmpty.isEmpty else {
            errors.append("Empty Sub-GHz file")
            return
        }

        // Check for Filetype header
        let hasFiletype = nonEmpty.contains { $0.hasPrefix("Filetype:") }
        if !hasFiletype {
            errors.append("Missing 'Filetype:' header. Sub-GHz files must start with 'Filetype: Flipper SubGhz'.")
        }

        // Check for Version
        let hasVersion = nonEmpty.contains { $0.hasPrefix("Version:") }
        if !hasVersion {
            warnings.append("Missing 'Version:' field. Recommended: 'Version: 1'.")
        }

        // Check for Frequency
        let hasFrequency = nonEmpty.contains { $0.hasPrefix("Frequency:") }
        if !hasFrequency {
            errors.append("Missing 'Frequency:' field. Sub-GHz files require a frequency in Hz.")
        } else {
            // Validate frequency range
            if let freqLine = nonEmpty.first(where: { $0.hasPrefix("Frequency:") }),
               let freqStr = freqLine.split(separator: ":").last?.trimmingCharacters(in: .whitespaces),
               let freq = Int64(freqStr) {
                if freq < 300_000_000 || freq > 928_000_000 {
                    warnings.append("Frequency \(VesperPrompts.formatFrequency(freq)) is outside typical Flipper Sub-GHz range (300-928 MHz).")
                }
            }
        }

        // Check for Preset
        let hasPreset = nonEmpty.contains { $0.hasPrefix("Preset:") }
        if !hasPreset {
            warnings.append("Missing 'Preset:' field. Common: 'FuriHalSubGhzPresetOok650Async'.")
        }

        // Check for Protocol or RAW_Data
        let hasProtocol = nonEmpty.contains { $0.hasPrefix("Protocol:") }
        let hasRawData = nonEmpty.contains { $0.hasPrefix("RAW_Data:") }
        if !hasProtocol && !hasRawData {
            errors.append("Missing 'Protocol:' or 'RAW_Data:'. File needs signal data.")
        }
    }

    // MARK: - Infrared Validation

    private func validateInfrared(lines: [String], errors: inout [String], warnings: inout [String]) {
        let nonEmpty = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard !nonEmpty.isEmpty else {
            errors.append("Empty IR file")
            return
        }

        let hasFiletype = nonEmpty.contains { $0.hasPrefix("Filetype:") }
        if !hasFiletype {
            errors.append("Missing 'Filetype:' header. IR files must start with 'Filetype: IR signals file'.")
        }

        let hasVersion = nonEmpty.contains { $0.hasPrefix("Version:") }
        if !hasVersion {
            warnings.append("Missing 'Version:' field.")
        }

        // Check for at least one signal definition
        let hasName = nonEmpty.contains { $0.hasPrefix("name:") }
        if !hasName {
            errors.append("No signal definitions found. IR files need at least one 'name:' entry.")
        }

        // Check for signal type
        let hasType = nonEmpty.contains { $0.hasPrefix("type:") }
        if !hasType {
            errors.append("Missing 'type:' field for signal (expected 'parsed' or 'raw').")
        }

        // For parsed signals, check protocol/address/command
        let isParsed = nonEmpty.contains { $0.trimmingCharacters(in: .whitespaces) == "type: parsed" }
        if isParsed {
            let hasProtocol = nonEmpty.contains { $0.hasPrefix("protocol:") }
            let hasAddress = nonEmpty.contains { $0.hasPrefix("address:") }
            let hasCommand = nonEmpty.contains { $0.hasPrefix("command:") }
            if !hasProtocol { errors.append("Parsed IR signal missing 'protocol:' field.") }
            if !hasAddress { errors.append("Parsed IR signal missing 'address:' field.") }
            if !hasCommand { errors.append("Parsed IR signal missing 'command:' field.") }
        }

        // For raw signals, check frequency and data
        let isRaw = nonEmpty.contains { $0.trimmingCharacters(in: .whitespaces) == "type: raw" }
        if isRaw {
            let hasFrequency = nonEmpty.contains { $0.hasPrefix("frequency:") }
            let hasDutyCycle = nonEmpty.contains { $0.hasPrefix("duty_cycle:") }
            let hasData = nonEmpty.contains { $0.hasPrefix("data:") }
            if !hasFrequency { errors.append("Raw IR signal missing 'frequency:' field.") }
            if !hasDutyCycle { warnings.append("Raw IR signal missing 'duty_cycle:' field.") }
            if !hasData { errors.append("Raw IR signal missing 'data:' field.") }
        }
    }

    // MARK: - NFC Validation

    private func validateNFC(lines: [String], errors: inout [String], warnings: inout [String]) {
        let nonEmpty = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard !nonEmpty.isEmpty else {
            errors.append("Empty NFC file")
            return
        }

        let hasFiletype = nonEmpty.contains { $0.hasPrefix("Filetype:") }
        if !hasFiletype {
            errors.append("Missing 'Filetype:' header. NFC files must start with 'Filetype: Flipper NFC device'.")
        }

        let hasVersion = nonEmpty.contains { $0.hasPrefix("Version:") }
        if !hasVersion {
            warnings.append("Missing 'Version:' field.")
        }

        // Check for device type
        let hasDeviceType = nonEmpty.contains {
            $0.hasPrefix("Device type:") || $0.hasPrefix("Device Type:")
        }
        if !hasDeviceType {
            errors.append("Missing 'Device type:' field (e.g., 'NTAG215', 'Mifare Classic 1K', 'UID').")
        }

        // Check for UID
        let hasUID = nonEmpty.contains { $0.hasPrefix("UID:") }
        if !hasUID {
            errors.append("Missing 'UID:' field. NFC files require a UID.")
        }

        // Check for ATQA and SAK (common for most NFC types)
        let hasATQA = nonEmpty.contains { $0.hasPrefix("ATQA:") }
        let hasSAK = nonEmpty.contains { $0.hasPrefix("SAK:") }
        if !hasATQA { warnings.append("Missing 'ATQA:' field.") }
        if !hasSAK { warnings.append("Missing 'SAK:' field.") }
    }

    // MARK: - RFID Validation

    private func validateRFID(lines: [String], errors: inout [String], warnings: inout [String]) {
        let nonEmpty = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard !nonEmpty.isEmpty else {
            errors.append("Empty RFID file")
            return
        }

        let hasFiletype = nonEmpty.contains { $0.hasPrefix("Filetype:") }
        if !hasFiletype {
            errors.append("Missing 'Filetype:' header. RFID files must start with 'Filetype: Flipper RFID key'.")
        }

        let hasVersion = nonEmpty.contains { $0.hasPrefix("Version:") }
        if !hasVersion {
            warnings.append("Missing 'Version:' field.")
        }

        // Check for key type
        let hasKeyType = nonEmpty.contains {
            $0.hasPrefix("Key type:") || $0.hasPrefix("Key Type:")
        }
        if !hasKeyType {
            errors.append("Missing 'Key type:' field (e.g., 'EM4100', 'H10301', 'HIDProx').")
        }

        // Check for Data
        let hasData = nonEmpty.contains { $0.hasPrefix("Data:") }
        if !hasData {
            errors.append("Missing 'Data:' field. RFID files need tag data bytes.")
        }
    }

    // MARK: - iButton Validation

    private func validateIButton(lines: [String], errors: inout [String], warnings: inout [String]) {
        let nonEmpty = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard !nonEmpty.isEmpty else {
            errors.append("Empty iButton file")
            return
        }

        let hasFiletype = nonEmpty.contains { $0.hasPrefix("Filetype:") }
        if !hasFiletype {
            errors.append("Missing 'Filetype:' header. iButton files must start with 'Filetype: Flipper iButton key'.")
        }

        let hasVersion = nonEmpty.contains { $0.hasPrefix("Version:") }
        if !hasVersion {
            warnings.append("Missing 'Version:' field.")
        }

        // Check for key type
        let hasKeyType = nonEmpty.contains {
            $0.hasPrefix("Key type:") || $0.hasPrefix("Key Type:")
        }
        if !hasKeyType {
            errors.append("Missing 'Key type:' field (e.g., 'Dallas', 'Cyfral', 'Metakom').")
        }

        // Check for Data
        let hasData = nonEmpty.contains { $0.hasPrefix("Data:") }
        if !hasData {
            errors.append("Missing 'Data:' field. iButton files need key data bytes.")
        }
    }

    // MARK: - BadUSB Validation

    private func validateBadUSB(lines: [String], errors: inout [String], warnings: inout [String]) {
        guard !lines.isEmpty else {
            errors.append("Empty BadUSB script")
            return
        }

        let validCommands: Set<String> = [
            "REM", "STRING", "STRINGLN", "DELAY", "ENTER", "RETURN",
            "GUI", "WINDOWS", "COMMAND", "CTRL", "CONTROL", "ALT",
            "SHIFT", "TAB", "ESC", "ESCAPE", "UPARROW", "UP",
            "DOWNARROW", "DOWN", "LEFTARROW", "LEFT", "RIGHTARROW", "RIGHT",
            "CAPSLOCK", "DELETE", "BACKSPACE", "END", "HOME", "INSERT",
            "NUMLOCK", "PAGEUP", "PAGEDOWN", "PRINTSCREEN", "SCROLLLOCK",
            "SPACE", "PAUSE", "BREAK", "MENU", "APP",
            "F1", "F2", "F3", "F4", "F5", "F6",
            "F7", "F8", "F9", "F10", "F11", "F12",
            "REPEAT", "SYSRQ", "WAIT_FOR_BUTTON_PRESS",
            "DEFAULT_DELAY", "DEFAULTDELAY", "ID"
        ]

        var hasContent = false
        var lineNum = 0

        for line in lines {
            lineNum += 1
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { continue }

            hasContent = true

            // Extract first word (command)
            let firstWord = trimmed.split(separator: " ", maxSplits: 1).first.map(String.init) ?? trimmed

            if !validCommands.contains(firstWord.uppercased()) {
                // Check for multi-key combos like "GUI r", "CTRL ALT DELETE"
                let upperFirst = firstWord.uppercased()
                if !validCommands.contains(upperFirst) {
                    warnings.append("Line \(lineNum): Unknown command '\(firstWord)'. May cause execution failure.")
                }
            }

            // Check line length
            if trimmed.count > 250 {
                warnings.append("Line \(lineNum): Exceeds 250 character limit (\(trimmed.count) chars). May be truncated.")
            }
        }

        if !hasContent {
            errors.append("BadUSB script has no executable commands.")
        }

        // Check for missing delays after common commands
        var prevCommand = ""
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { continue }
            let cmd = trimmed.split(separator: " ", maxSplits: 1).first.map { String($0).uppercased() } ?? ""

            if cmd == "STRING" || cmd == "STRINGLN" {
                if prevCommand == "GUI" || prevCommand == "CTRL" || prevCommand == "ALT" {
                    warnings.append("STRING immediately after \(prevCommand) without DELAY may drop keystrokes.")
                    break // Only warn once
                }
            }
            prevCommand = cmd
        }
    }

    // MARK: - Helpers

    private func buildGenerationPrompt(for type: PayloadType) -> String {
        switch type {
        case .subghz:
            return """
            You are an expert Flipper Zero Sub-GHz signal engineer. Generate valid .sub files with correct headers, frequency, preset, protocol, and signal data. Always include:
            - Filetype: Flipper SubGhz RAW File (or Key File for known protocols)
            - Version: 1
            - Frequency in Hz (e.g., 433920000)
            - Preset (e.g., FuriHalSubGhzPresetOok650Async)
            - Protocol and signal data
            Output raw file content only.
            """
        case .ir:
            return """
            You are an expert Flipper Zero infrared signal engineer. Generate valid .ir files with correct headers and signal definitions. Always include:
            - Filetype: IR signals file
            - Version: 1
            - Signal entries with name, type (parsed or raw), and appropriate data fields
            - For parsed: protocol, address, command
            - For raw: frequency, duty_cycle, data
            Output raw file content only.
            """
        case .nfc:
            return """
            You are an expert Flipper Zero NFC engineer. Generate valid .nfc files with correct headers and card data. Always include:
            - Filetype: Flipper NFC device
            - Version: 4
            - Device type, UID, ATQA, SAK
            - Appropriate data blocks for the card type
            Output raw file content only.
            """
        case .rfid:
            return """
            You are an expert Flipper Zero 125kHz RFID engineer. Generate valid .rfid files with correct headers and tag data. Always include:
            - Filetype: Flipper RFID key
            - Version: 1
            - Key type (e.g., EM4100, H10301, HIDProx)
            - Data bytes in hex format
            Output raw file content only.
            """
        case .ibutton:
            return """
            You are an expert Flipper Zero iButton engineer. Generate valid .ibtn files with correct headers and key data. Always include:
            - Filetype: Flipper iButton key
            - Version: 1
            - Key type (Dallas, Cyfral, or Metakom)
            - Data bytes in hex format
            Output raw file content only.
            """
        case .badusb:
            return """
            You are an expert BadUSB/DuckyScript payload developer for Flipper Zero. Your scripts are used for authorized penetration testing and security research.
            Generate valid DuckyScript with:
            - REM comments explaining the script
            - Proper DELAY after every action for reliability
            - Platform-appropriate keyboard shortcuts
            - Clean, well-structured code under 50 lines unless complexity requires more
            Output raw DuckyScript only -- no markdown, no explanations.
            """
        }
    }

    private func stripMarkdownCodeBlocks(_ text: String) -> String {
        var result = text.trimmingCharacters(in: .whitespacesAndNewlines)

        // Remove opening code fence (```language or ```)
        if result.hasPrefix("```") {
            if let firstNewline = result.firstIndex(of: "\n") {
                result = String(result[result.index(after: firstNewline)...])
            }
        }

        // Remove closing code fence
        if result.hasSuffix("```") {
            result = String(result.dropLast(3))
        }

        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func sanitizeFilename(from prompt: String) -> String {
        // Take first few meaningful words from prompt
        let words = prompt
            .components(separatedBy: .whitespaces)
            .filter { !$0.isEmpty }
            .prefix(4)

        var name = words.joined(separator: "_")

        // Remove non-alphanumeric characters (except underscore and hyphen)
        name = name.unicodeScalars
            .filter { CharacterSet.alphanumerics.contains($0) || $0 == "_" || $0 == "-" }
            .map { String($0) }
            .joined()

        // Ensure reasonable length
        if name.count > 40 {
            name = String(name.prefix(40))
        }

        if name.isEmpty {
            name = "vesper_payload"
        }

        return name
    }
}

// MARK: - Errors

enum PayloadError: LocalizedError {
    case unsupportedType(String)
    case generationFailed(String)
    case emptyResponse

    var errorDescription: String? {
        switch self {
        case .unsupportedType(let type):
            return "Unsupported payload type: '\(type)'. Supported: subghz, ir, nfc, rfid, ibutton, badusb."
        case .generationFailed(let reason):
            return "Payload generation failed: \(reason)"
        case .emptyResponse:
            return "AI returned an empty response. Try rephrasing your request."
        }
    }
}
