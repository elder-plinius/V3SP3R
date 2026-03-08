package com.vesper.flipper.domain.executor

import com.vesper.flipper.domain.model.PayloadType

/**
 * Validates AI-generated Flipper Zero payload content for structural correctness.
 * Catches malformed files before they hit the device.
 */
object ForgeValidator {

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val sanitizedContent: String? = null
    )

    fun validate(content: String, type: PayloadType): ValidationResult {
        if (content.isBlank()) {
            return ValidationResult(false, listOf("Payload content is empty"))
        }
        return when (type) {
            PayloadType.SUB_GHZ -> validateSubGhz(content)
            PayloadType.INFRARED -> validateInfrared(content)
            PayloadType.NFC -> validateNfc(content)
            PayloadType.RFID -> validateRfid(content)
            PayloadType.BAD_USB -> validateBadUsb(content)
            PayloadType.IBUTTON -> validateIButton(content)
            PayloadType.UNKNOWN -> ValidationResult(true, warnings = listOf("Unknown type, skipping validation"))
        }
    }

    // ─── Sub-GHz ───────────────────────────────────────────

    private fun validateSubGhz(content: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = content.lines()

        val filetype = lines.find { it.startsWith("Filetype:") }?.substringAfter(":")?.trim()
        if (filetype == null) {
            errors.add("Missing 'Filetype:' header")
        } else if ("SubGhz" !in filetype && "Sub-GHz" !in filetype) {
            errors.add("Filetype should be 'Flipper SubGhz RAW File' or 'Flipper SubGhz Key File', got: $filetype")
        }

        val freqLine = lines.find { it.startsWith("Frequency:") }
        if (freqLine == null) {
            errors.add("Missing 'Frequency:' field")
        } else {
            val freq = freqLine.substringAfter(":").trim().toLongOrNull()
            if (freq == null) {
                errors.add("Frequency is not a valid number")
            } else if (freq < 280_000_000 || freq > 930_000_000) {
                errors.add("Frequency $freq Hz is outside Flipper's supported range (280-930 MHz)")
            }
        }

        val preset = lines.find { it.startsWith("Preset:") }
        if (preset == null) {
            errors.add("Missing 'Preset:' field")
        } else {
            val presetValue = preset.substringAfter(":").trim()
            val validPresets = listOf(
                "FuriHalSubGhzPresetOok270Async",
                "FuriHalSubGhzPresetOok650Async",
                "FuriHalSubGhzPreset2FSKDev238Async",
                "FuriHalSubGhzPreset2FSKDev476Async",
                "FuriHalSubGhzPresetGFSK",
                "FuriHalSubGhzPresetMSK",
                "FuriHalSubGhzPresetCustom"
            )
            if (presetValue !in validPresets) {
                warnings.add("Preset '$presetValue' is non-standard. Valid: ${validPresets.joinToString(", ")}")
            }
        }

        val rawDataLines = lines.filter { it.startsWith("RAW_Data:") }
        val isRaw = lines.any { it.startsWith("Protocol:") && it.substringAfter(":").trim() == "RAW" }
        if (isRaw) {
            if (rawDataLines.isEmpty()) {
                errors.add("RAW protocol specified but no RAW_Data lines found")
            } else {
                val allTimings = rawDataLines.flatMap { line ->
                    line.substringAfter(":").trim().split("\\s+".toRegex())
                        .mapNotNull { it.toIntOrNull() }
                }
                if (allTimings.size < 4) {
                    warnings.add("RAW_Data has only ${allTimings.size} timing values — likely a placeholder")
                }
                if (allTimings.all { it > 0 } || allTimings.all { it < 0 }) {
                    errors.add("RAW_Data must alternate between positive (high) and negative (low) timing values")
                }
            }
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    // ─── Infrared ──────────────────────────────────────────

    private fun validateInfrared(content: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = content.lines()

        val filetype = lines.find { it.startsWith("Filetype:") }?.substringAfter(":")?.trim()
        if (filetype == null) {
            errors.add("Missing 'Filetype:' header")
        } else if (filetype != "IR signals file") {
            errors.add("Filetype should be 'IR signals file', got: $filetype")
        }

        val signalBlocks = mutableListOf<List<String>>()
        var currentBlock = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("name:")) {
                if (currentBlock.isNotEmpty()) signalBlocks.add(currentBlock)
                currentBlock = mutableListOf(line)
            } else if (currentBlock.isNotEmpty()) {
                currentBlock.add(line)
            }
        }
        if (currentBlock.isNotEmpty()) signalBlocks.add(currentBlock)

        if (signalBlocks.isEmpty()) {
            errors.add("No signal blocks found (each signal needs a 'name:' line)")
        }

        for ((idx, block) in signalBlocks.withIndex()) {
            val blockText = block.joinToString("\n")
            val signalName = block.firstOrNull()?.substringAfter(":")?.trim() ?: "signal_$idx"
            val typeLine = block.find { it.startsWith("type:") }?.substringAfter(":")?.trim()

            if (typeLine == null) {
                errors.add("Signal '$signalName': missing 'type:' (parsed or raw)")
            } else if (typeLine == "parsed") {
                if (block.none { it.startsWith("protocol:") }) {
                    errors.add("Signal '$signalName': parsed type requires 'protocol:' field")
                }
                if (block.none { it.startsWith("address:") }) {
                    errors.add("Signal '$signalName': parsed type requires 'address:' field")
                }
                if (block.none { it.startsWith("command:") }) {
                    errors.add("Signal '$signalName': parsed type requires 'command:' field")
                }
                val protocol = block.find { it.startsWith("protocol:") }?.substringAfter(":")?.trim()
                val knownProtocols = listOf(
                    "NEC", "NECext", "NEC42", "NEC42ext",
                    "Samsung32", "RC5", "RC5X", "RC6",
                    "SIRC", "SIRC15", "SIRC20", "Kaseikyo",
                    "RCA", "Pioneer", "BOSE", "Panasonic"
                )
                if (protocol != null && protocol !in knownProtocols) {
                    warnings.add("Signal '$signalName': protocol '$protocol' is uncommon — may not be recognized by all devices")
                }
            } else if (typeLine == "raw") {
                if (block.none { it.startsWith("frequency:") }) {
                    errors.add("Signal '$signalName': raw type requires 'frequency:' field")
                }
                if (block.none { it.startsWith("data:") }) {
                    errors.add("Signal '$signalName': raw type requires 'data:' field")
                }
            }
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    // ─── NFC ───────────────────────────────────────────────

    private fun validateNfc(content: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = content.lines()

        val filetype = lines.find { it.startsWith("Filetype:") }?.substringAfter(":")?.trim()
        if (filetype == null) {
            errors.add("Missing 'Filetype:' header")
        } else if ("NFC" !in filetype) {
            errors.add("Filetype should contain 'NFC', got: $filetype")
        }

        val deviceType = lines.find { it.startsWith("Device type:") }?.substringAfter(":")?.trim()
        if (deviceType == null) {
            errors.add("Missing 'Device type:' field")
        }

        val uidLine = lines.find { it.startsWith("UID:") }
        if (uidLine == null) {
            errors.add("Missing 'UID:' field")
        } else {
            val uid = uidLine.substringAfter(":").trim()
            val uidBytes = uid.split(" ").filter { it.isNotBlank() }
            if (deviceType != null) {
                val expectedLen = when {
                    deviceType.contains("NTAG", true) -> 7
                    deviceType.contains("MIFARE Classic", true) -> 4
                    deviceType.contains("Ultralight", true) -> 7
                    else -> null
                }
                if (expectedLen != null && uidBytes.size != expectedLen) {
                    errors.add("UID has ${uidBytes.size} bytes but $deviceType expects $expectedLen bytes")
                }
            }
            val invalidBytes = uidBytes.filter { byte ->
                byte.length != 2 || byte.any { c -> c !in "0123456789ABCDEFabcdef" }
            }
            if (invalidBytes.isNotEmpty()) {
                errors.add("UID contains invalid hex bytes: ${invalidBytes.joinToString(", ")}")
            }
        }

        // Check for ATQA and SAK (required for most NFC types)
        if (lines.none { it.startsWith("ATQA:") }) {
            warnings.add("Missing 'ATQA:' field — some emulations may not work")
        }
        if (lines.none { it.startsWith("SAK:") }) {
            warnings.add("Missing 'SAK:' field — some emulations may not work")
        }

        // Check for page data on NTAG types
        if (deviceType != null && deviceType.contains("NTAG", true)) {
            val pageLines = lines.filter { it.startsWith("Page ") }
            if (pageLines.isEmpty()) {
                warnings.add("No page data found — NTAG emulation will produce a blank tag")
            } else {
                val expectedPages = when {
                    deviceType.contains("213") -> 45
                    deviceType.contains("215") -> 135
                    deviceType.contains("216") -> 231
                    else -> null
                }
                if (expectedPages != null && pageLines.size < expectedPages) {
                    warnings.add("Only ${pageLines.size}/$expectedPages pages defined — tag may be incomplete")
                }
            }
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    // ─── RFID ──────────────────────────────────────────────

    private fun validateRfid(content: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = content.lines()

        val filetype = lines.find { it.startsWith("Filetype:") }?.substringAfter(":")?.trim()
        if (filetype == null) {
            errors.add("Missing 'Filetype:' header")
        }

        val keyType = lines.find { it.startsWith("Key type:") }?.substringAfter(":")?.trim()
        if (keyType == null) {
            errors.add("Missing 'Key type:' field")
        } else {
            val knownTypes = listOf("EM4100", "H10301", "HIDProx", "Indala26", "IOProxXSF", "AWID", "FDX-B", "Paradox", "PAC/Stanley", "Viking", "Jablotron", "Gallagher", "Keri")
            if (keyType !in knownTypes) {
                warnings.add("Key type '$keyType' is uncommon. Known types: ${knownTypes.take(5).joinToString(", ")}...")
            }
        }

        val dataLine = lines.find { it.startsWith("Data:") }
        if (dataLine == null) {
            errors.add("Missing 'Data:' field")
        } else {
            val data = dataLine.substringAfter(":").trim()
            val dataBytes = data.split(" ").filter { it.isNotBlank() }
            if (dataBytes.isEmpty()) {
                errors.add("Data field is empty")
            }
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    // ─── BadUSB ────────────────────────────────────────────

    private fun validateBadUsb(content: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = content.lines().filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            return ValidationResult(false, listOf("BadUSB script is empty"))
        }

        val validCommands = setOf(
            "REM", "DELAY", "STRING", "STRINGLN", "GUI", "WINDOWS", "CTRL", "CONTROL",
            "ALT", "SHIFT", "ENTER", "ESCAPE", "ESC", "TAB", "SPACE", "BACKSPACE",
            "DELETE", "INSERT", "HOME", "END", "PAGEUP", "PAGEDOWN",
            "UP", "DOWN", "LEFT", "RIGHT", "UPARROW", "DOWNARROW", "LEFTARROW", "RIGHTARROW",
            "CAPSLOCK", "NUMLOCK", "SCROLLLOCK", "PRINTSCREEN", "PAUSE", "BREAK",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
            "MENU", "APP", "POWER", "REPEAT", "SYSRQ",
            "ALTCHAR", "ALTSTRING", "ALTCODE",
            "WAIT_FOR_BUTTON_PRESS", "ID", "DEFAULT_DELAY", "DEFAULTDELAY"
        )

        var hasActionCommand = false

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val firstWord = trimmed.split("\\s+".toRegex(), limit = 2).firstOrNull()?.uppercase() ?: continue

            if (firstWord in validCommands) {
                if (firstWord !in setOf("REM", "DELAY", "DEFAULT_DELAY", "DEFAULTDELAY")) {
                    hasActionCommand = true
                }
            } else {
                // Check if it's a combo like CTRL-ALT or CTRL SHIFT
                val parts = firstWord.split("-", "+")
                val allKnown = parts.all { it in validCommands || it.length == 1 }
                if (!allKnown) {
                    warnings.add("Line ${idx + 1}: '$firstWord' is not a recognized DuckyScript command")
                }
            }
        }

        if (!hasActionCommand) {
            warnings.add("Script has no action commands (only REM/DELAY) — it won't do anything")
        }

        // Check for suspiciously missing delays
        val hasDelay = lines.any { it.trim().uppercase().startsWith("DELAY") || it.trim().uppercase().startsWith("DEFAULT_DELAY") }
        if (!hasDelay && lines.size > 3) {
            warnings.add("No DELAY commands found — script may execute too fast for the target OS to keep up")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    // ─── iButton ───────────────────────────────────────────

    private fun validateIButton(content: String): ValidationResult {
        val errors = mutableListOf<String>()
        val lines = content.lines()

        val filetype = lines.find { it.startsWith("Filetype:") }?.substringAfter(":")?.trim()
        if (filetype == null) {
            errors.add("Missing 'Filetype:' header")
        }

        val keyType = lines.find { it.startsWith("Key type:") }?.substringAfter(":")?.trim()
        if (keyType == null) {
            errors.add("Missing 'Key type:' field")
        } else {
            val knownTypes = listOf("Cyfral", "Dallas", "Metakom")
            if (keyType !in knownTypes) {
                errors.add("Key type '$keyType' not recognized. Valid: ${knownTypes.joinToString(", ")}")
            }
        }

        val dataLine = lines.find { it.startsWith("Data:") }
        if (dataLine == null) {
            errors.add("Missing 'Data:' field")
        }

        return ValidationResult(errors.isEmpty(), errors)
    }
}
