package com.vesper.flipper.domain.executor

import com.vesper.flipper.ai.OpenRouterClient
import com.vesper.flipper.domain.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared AI-powered forge engine used by both the Alchemy Lab UI and the agent tool path.
 * Generates real, validated Flipper Zero payloads from natural language prompts.
 */
@Singleton
class ForgeEngine @Inject constructor(
    private val openRouterClient: OpenRouterClient
) {

    /**
     * Generate a fully-formed ForgeBlueprint from a user prompt.
     * Uses AI to create real payload content, then validates it.
     */
    suspend fun forge(prompt: String, payloadTypeHint: String? = null): ForgeResult {
        val resolvedType = payloadTypeHint?.uppercase()?.let {
            runCatching { PayloadType.valueOf(it) }.getOrNull()
        } ?: LootClassifier.detectPayloadType(prompt, "")

        val forgePrompt = buildForgePrompt(prompt, resolvedType)

        val aiResponse = try {
            openRouterClient.chatSimple(forgePrompt)
        } catch (e: Exception) {
            return ForgeResult(
                blueprint = generateFallbackBlueprint(prompt, resolvedType),
                validation = ForgeValidator.ValidationResult(
                    valid = true,
                    warnings = listOf("AI unavailable — used template fallback: ${e.message}")
                ),
                usedFallback = true
            )
        }

        if (aiResponse == null) {
            return ForgeResult(
                blueprint = generateFallbackBlueprint(prompt, resolvedType),
                validation = ForgeValidator.ValidationResult(
                    valid = true,
                    warnings = listOf("AI returned empty response — used template fallback")
                ),
                usedFallback = true
            )
        }

        val blueprint = parseAIBlueprint(prompt, aiResponse, resolvedType)
        val validation = ForgeValidator.validate(blueprint.generatedCode, blueprint.payloadType)

        return ForgeResult(
            blueprint = blueprint,
            validation = validation,
            usedFallback = false
        )
    }

    /**
     * Generate a fallback blueprint without AI (offline / error case).
     */
    fun fallback(prompt: String, payloadTypeHint: String? = null): ForgeResult {
        val resolvedType = payloadTypeHint?.uppercase()?.let {
            runCatching { PayloadType.valueOf(it) }.getOrNull()
        } ?: LootClassifier.detectPayloadType(prompt, "")

        val blueprint = generateFallbackBlueprint(prompt, resolvedType)
        val validation = ForgeValidator.validate(blueprint.generatedCode, blueprint.payloadType)
        return ForgeResult(blueprint = blueprint, validation = validation, usedFallback = true)
    }

    // ═══════════════════════════════════════════════════════
    // PROMPT CONSTRUCTION — Format-specific few-shot examples
    // ═══════════════════════════════════════════════════════

    private fun buildForgePrompt(userRequest: String, type: PayloadType): String {
        val formatGuide = getFormatGuide(type)
        return """You are V3SP3R's Alchemy Lab AI forge. Generate a REAL, WORKING Flipper Zero payload file.

USER REQUEST: "$userRequest"
DETECTED TYPE: ${type.name}

$formatGuide

RESPOND IN THIS EXACT FORMAT (no markdown fences, no extra commentary):

TYPE: ${type.name}
TITLE: [short descriptive title, 3-6 words]
FILENAME: [filename with correct extension]
RARITY: [COMMON, UNCOMMON, RARE, EPIC, or LEGENDARY based on complexity]
DESCRIPTION: [1-2 sentence description of what this payload does]
SECTION:label=Key Field|value=the_value|editable=true|type=TEXT
[add 2-5 SECTION lines for the most important editable parameters]
---PAYLOAD---
[EXACT file content — must be valid Flipper Zero format, ready to deploy]
---END---

CRITICAL RULES:
1. The payload between ---PAYLOAD--- and ---END--- must be COMPLETE and VALID
2. Do NOT use placeholder values like "TODO" or "insert data here"
3. Generate realistic, functional content appropriate to the request
4. Follow the exact file format specification for ${type.displayName}
5. Include all required fields — missing fields will cause the Flipper to reject the file"""
    }

    private fun getFormatGuide(type: PayloadType): String = when (type) {
        PayloadType.SUB_GHZ -> """
## SUB-GHZ FILE FORMAT REFERENCE

### Option A: RAW capture format (.sub)
```
Filetype: Flipper SubGhz RAW File
Version: 1
Frequency: 433920000
Preset: FuriHalSubGhzPresetOok650Async
Protocol: RAW
RAW_Data: 500 -500 500 -500 1000 -500 500 -1000 500 -500 1000 -1000
RAW_Data: 500 -10000 500 -500 500 -500 1000 -500 500 -1000 500 -500
```

### Option B: Decoded protocol format (.sub)
```
Filetype: Flipper SubGhz Key File
Version: 1
Frequency: 433920000
Preset: FuriHalSubGhzPresetOok650Async
Protocol: Princeton
Bit: 24
Key: 00 00 00 00 00 AB CD EF
TE: 400
```

### Key constraints:
- Frequency range: 300000000–928000000 Hz (common: 300/315/390/433920000/868350000)
- Valid presets: FuriHalSubGhzPresetOok270Async, FuriHalSubGhzPresetOok650Async, FuriHalSubGhzPreset2FSKDev238Async, FuriHalSubGhzPreset2FSKDev476Async
- RAW_Data: positive = signal high (microseconds), negative = signal low. Must alternate.
- For RAW: generate at least 20-50 realistic timing values per RAW_Data line
- Common protocols: Princeton (24-bit), CAME (12/24-bit), GateTX, Holtek, Linear, Nice FLO
- TE (Timing Element): base pulse width in microseconds (200-1000 typical)

### Real-world examples:
- Garage door (Princeton): Freq 433920000, Bit 24, TE 400, Key varies
- Car keyfob (CAME): Freq 433920000, Bit 12, TE 320
- Gate remote (Nice FLO): Freq 433920000, Bit 12, TE 700
- Doorbell: Freq 315000000, OOK, Princeton, TE 350"""

        PayloadType.INFRARED -> """
## INFRARED FILE FORMAT REFERENCE

### Parsed signal format (preferred — uses known protocol decoders):
```
Filetype: IR signals file
Version: 1
#
name: Power
type: parsed
protocol: NEC
address: 04 00 00 00
command: 08 00 00 00
#
name: Vol_up
type: parsed
protocol: NEC
address: 04 00 00 00
command: 02 00 00 00
#
name: Vol_dn
type: parsed
protocol: NEC
address: 04 00 00 00
command: 03 00 00 00
```

### Raw signal format (for protocols Flipper can't decode):
```
name: Power
type: raw
frequency: 38000
duty_cycle: 0.330000
data: 9024 4512 564 564 564 1692 564 564 564 564 ...
```

### Key constraints:
- Each signal block starts with 'name:' — use descriptive button names
- Parsed: protocol + address (4 bytes hex) + command (4 bytes hex)
- Raw: frequency (usually 38000 Hz), duty_cycle (usually 0.33), data (microsecond timings)
- Known protocols: NEC, NECext, Samsung32, RC5, RC6, SIRC, SIRC15, SIRC20, Kaseikyo, Pioneer
- Generate a COMPLETE remote with 5-15 buttons (Power, Vol_up, Vol_dn, Ch_up, Ch_dn, Mute, Input, etc.)

### Common device databases:
- Samsung TV: protocol Samsung32, address 07 07 00 00
- LG TV: protocol NEC, address 04 00 00 00
- Sony TV: protocol SIRC, address 01 00 00 00
- Vizio TV: protocol NEC, address 04 00 00 00
- Apple TV: protocol NEC42ext, address 87 EE 00 00
- AC units: typically raw signals at 38000 Hz"""

        PayloadType.NFC -> """
## NFC FILE FORMAT REFERENCE

### NTAG/Ultralight format:
```
Filetype: Flipper NFC device
Version: 4
Device type: NTAG215
UID: 04 A1 B2 C3 D4 E5 80
ATQA: 44 00
SAK: 00
Signature: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Mifare version: 00 04 04 02 01 00 11 03
Counter 0: 0
Tearing 0: 00
Counter 1: 0
Tearing 1: 00
Counter 2: 0
Tearing 2: 00
Pages total: 135
Page 0: 04 A1 B2 C3
Page 1: D4 E5 80 00
Page 2: 00 00 00 00
Page 3: E1 10 3E 00
Page 4: 03 00 FE 00
```

### MIFARE Classic format:
```
Filetype: Flipper NFC device
Version: 4
Device type: MIFARE Classic 1k
UID: A1 B2 C3 D4
ATQA: 04 00
SAK: 08
Block 0: A1 B2 C3 D4 00 08 04 00 00 00 00 00 00 00 00 00
Block 1: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 2: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 3: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF
```

### Key constraints:
- UID: 4 bytes for MIFARE Classic, 7 bytes for NTAG/Ultralight
- ATQA: 2 bytes (44 00 for NTAG, 04 00 for MIFARE Classic 1k)
- SAK: 1 byte (00 for NTAG, 08 for MIFARE Classic 1k, 18 for 4k)
- NTAG215: 135 pages (4 bytes each). Page 3 = capability container. Page 4+ = NDEF data.
- NTAG213: 45 pages. NTAG216: 231 pages.
- For NDEF URL: Page 4 starts with 03 [len] D1 01, then URI record
- Generate ALL pages for a complete tag (at minimum pages 0-5 with valid structure)"""

        PayloadType.RFID -> """
## RFID (125kHz) FILE FORMAT REFERENCE

```
Filetype: Flipper RFID key
Version: 1
Key type: EM4100
Data: 01 23 45 67 89
```

### Key constraints:
- Key type: EM4100 (most common), H10301 (HID), HIDProx, Indala26, IOProxXSF, AWID, FDX-B, Viking, Paradox, PAC/Stanley, Keri, Gallagher, Jablotron
- EM4100: 5 bytes of data
- H10301/HIDProx: 3 bytes (facility code + card number encoded)
- Generate realistic-looking data values"""

        PayloadType.BAD_USB -> """
## BADUSB (DUCKYSCRIPT) FORMAT REFERENCE

```
REM Title: Example script
REM Author: V3SP3R
REM Target: Windows 10/11
DEFAULT_DELAY 100
DELAY 1000
GUI r
DELAY 500
STRING notepad
ENTER
DELAY 800
STRING Hello from V3SP3R!
ENTER
```

### Key constraints:
- Commands: REM, DELAY, DEFAULT_DELAY, STRING, STRINGLN, GUI/WINDOWS, CTRL/CONTROL, ALT, SHIFT, ENTER, ESCAPE/ESC, TAB, SPACE, BACKSPACE, DELETE, arrows (UP/DOWN/LEFT/RIGHT), F1-F12
- GUI r = Win+R (Run dialog), GUI d = Show desktop, GUI l = Lock
- CTRL c/v/x/z/a/s = Copy/Paste/Cut/Undo/SelectAll/Save
- ALT F4 = Close window, ALT TAB = Switch window
- STRING types characters, STRINGLN types + presses Enter
- DEFAULT_DELAY adds delay between every command
- DELAY is in milliseconds
- ALWAYS include adequate DELAYs (500-2000ms) after GUI/app launches
- For multi-OS: start with REM to document target OS
- REPEAT N repeats the previous command N times

### Common patterns:
- Open terminal: GUI r → STRING cmd/powershell → ENTER (Windows), GUI SPACE → STRING terminal → ENTER (macOS)
- Open browser: GUI r → STRING https://url → ENTER
- Open notepad: GUI r → STRING notepad → ENTER
- Lock workstation: GUI l"""

        PayloadType.IBUTTON -> """
## iBUTTON FILE FORMAT REFERENCE

```
Filetype: Flipper iButton key
Version: 1
Key type: Dallas
Data: 01 23 45 67 89 AB CD EF
```

### Key constraints:
- Key type: Dallas (DS1990A, 8 bytes), Cyfral (2 bytes), Metakom (4 bytes)
- Dallas: 8 bytes, first byte is family code (01), last byte is CRC
- Cyfral: 2 bytes
- Metakom: 4 bytes
- Dallas is by far the most common"""

        PayloadType.UNKNOWN -> """
Generate the content in whatever format seems most appropriate for the request.
Include proper Flipper Zero file headers."""
    }

    // ═══════════════════════════════════════════════════════
    // AI RESPONSE PARSING
    // ═══════════════════════════════════════════════════════

    private fun parseAIBlueprint(
        userInput: String,
        aiResponse: String,
        fallbackType: PayloadType
    ): ForgeBlueprint {
        val lines = aiResponse.lines()

        var type = fallbackType
        var title = "Crafted Payload"
        var filename = "forge_output${fallbackType.extension}"
        var rarity = LootRarity.COMMON
        var description = "AI-crafted payload from: $userInput"
        val sections = mutableListOf<BlueprintSection>()
        var payloadContent = ""

        var inPayload = false
        val payloadLines = mutableListOf<String>()

        for (line in lines) {
            when {
                inPayload -> {
                    if (line.trim() == "---END---") {
                        inPayload = false
                        payloadContent = payloadLines.joinToString("\n")
                    } else {
                        payloadLines.add(line)
                    }
                }
                line.trim() == "---PAYLOAD---" -> inPayload = true
                line.startsWith("TYPE:") -> {
                    val typeStr = line.substringAfter(":").trim()
                    type = try { PayloadType.valueOf(typeStr) } catch (_: Exception) { fallbackType }
                }
                line.startsWith("TITLE:") -> title = line.substringAfter(":").trim()
                line.startsWith("FILENAME:") -> filename = line.substringAfter(":").trim()
                line.startsWith("RARITY:") -> {
                    val rarityStr = line.substringAfter(":").trim()
                    rarity = try { LootRarity.valueOf(rarityStr) } catch (_: Exception) { LootRarity.COMMON }
                }
                line.startsWith("DESCRIPTION:") -> description = line.substringAfter(":").trim()
                line.startsWith("SECTION:") -> {
                    parseSection(line)?.let { sections.add(it) }
                }
            }
        }

        // If payload delimiters weren't found, try to extract file content directly
        if (payloadContent.isBlank()) {
            payloadContent = extractPayloadFromFreeform(aiResponse, type)
        }

        // Ensure filename has correct extension
        if (!filename.endsWith(type.extension) && type.extension.isNotEmpty()) {
            filename = filename.substringBeforeLast(".") + type.extension
        }

        val flipperPath = "${type.flipperDir}/$filename"

        return ForgeBlueprint(
            title = title,
            description = description,
            payloadType = type,
            sections = sections.ifEmpty { inferSections(payloadContent, type) },
            generatedCode = payloadContent,
            flipperPath = flipperPath,
            rarity = rarity
        )
    }

    private fun parseSection(line: String): BlueprintSection? {
        return try {
            val sectionData = line.substringAfter("SECTION:")
            val params = sectionData.split("|").associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else return null
            }
            BlueprintSection(
                label = params["label"] ?: return null,
                value = params["value"] ?: "",
                editable = params["editable"]?.toBooleanStrictOrNull() ?: true,
                fieldType = try {
                    BlueprintFieldType.valueOf(params["type"] ?: "TEXT")
                } catch (_: Exception) { BlueprintFieldType.TEXT }
            )
        } catch (_: Exception) { null }
    }

    /**
     * If the AI didn't use our delimiter format, try to find Flipper file content
     * by looking for known headers like "Filetype:" or "REM".
     */
    private fun extractPayloadFromFreeform(response: String, type: PayloadType): String {
        val lines = response.lines()

        // Look for content between markdown code fences
        val fenceStart = lines.indexOfFirst { it.trim().startsWith("```") }
        val fenceEnd = if (fenceStart >= 0) {
            lines.drop(fenceStart + 1).indexOfFirst { it.trim().startsWith("```") }
                .let { if (it >= 0) it + fenceStart + 1 else -1 }
        } else -1

        if (fenceStart >= 0 && fenceEnd > fenceStart) {
            return lines.subList(fenceStart + 1, fenceEnd).joinToString("\n")
        }

        // Look for Flipper file headers
        val headerPatterns = listOf("Filetype:", "REM ", "DELAY ", "STRING ", "GUI ")
        val firstHeaderIdx = lines.indexOfFirst { line ->
            headerPatterns.any { line.trimStart().startsWith(it) }
        }
        if (firstHeaderIdx >= 0) {
            return lines.drop(firstHeaderIdx).joinToString("\n")
        }

        return response
    }

    /**
     * Infer editable sections from the payload content when the AI didn't provide them.
     */
    private fun inferSections(content: String, type: PayloadType): List<BlueprintSection> {
        val sections = mutableListOf<BlueprintSection>()
        val lines = content.lines()

        when (type) {
            PayloadType.SUB_GHZ -> {
                lines.find { it.startsWith("Frequency:") }?.let {
                    sections.add(BlueprintSection("Frequency", it.substringAfter(":").trim(), fieldType = BlueprintFieldType.FREQUENCY))
                }
                lines.find { it.startsWith("Preset:") }?.let {
                    sections.add(BlueprintSection("Preset", it.substringAfter(":").trim()))
                }
                lines.find { it.startsWith("Protocol:") }?.let {
                    sections.add(BlueprintSection("Protocol", it.substringAfter(":").trim()))
                }
            }
            PayloadType.INFRARED -> {
                lines.find { it.startsWith("protocol:") }?.let {
                    sections.add(BlueprintSection("Protocol", it.substringAfter(":").trim()))
                }
                val signalCount = lines.count { it.startsWith("name:") }
                sections.add(BlueprintSection("Signals", "$signalCount buttons", editable = false))
            }
            PayloadType.NFC -> {
                lines.find { it.startsWith("Device type:") }?.let {
                    sections.add(BlueprintSection("Device Type", it.substringAfter(":").trim()))
                }
                lines.find { it.startsWith("UID:") }?.let {
                    sections.add(BlueprintSection("UID", it.substringAfter(":").trim(), fieldType = BlueprintFieldType.HEX))
                }
            }
            PayloadType.BAD_USB -> {
                val lineCount = lines.count { it.isNotBlank() && !it.startsWith("REM") }
                sections.add(BlueprintSection("Commands", "$lineCount lines", editable = false))
                val hasGui = lines.any { it.trim().startsWith("GUI") || it.trim().startsWith("WINDOWS") }
                if (hasGui) sections.add(BlueprintSection("Uses GUI Keys", "Yes", editable = false))
            }
            PayloadType.RFID -> {
                lines.find { it.startsWith("Key type:") }?.let {
                    sections.add(BlueprintSection("Key Type", it.substringAfter(":").trim()))
                }
                lines.find { it.startsWith("Data:") }?.let {
                    sections.add(BlueprintSection("Data", it.substringAfter(":").trim(), fieldType = BlueprintFieldType.HEX))
                }
            }
            PayloadType.IBUTTON -> {
                lines.find { it.startsWith("Key type:") }?.let {
                    sections.add(BlueprintSection("Key Type", it.substringAfter(":").trim()))
                }
                lines.find { it.startsWith("Data:") }?.let {
                    sections.add(BlueprintSection("Data", it.substringAfter(":").trim(), fieldType = BlueprintFieldType.HEX))
                }
            }
            else -> {
                sections.add(BlueprintSection("Content", content.take(50) + "...", editable = false))
            }
        }

        return sections
    }

    // ═══════════════════════════════════════════════════════
    // FALLBACK TEMPLATES — Used when AI is unavailable
    // ═══════════════════════════════════════════════════════

    private fun generateFallbackBlueprint(prompt: String, type: PayloadType): ForgeBlueprint {
        val sanitized = sanitizeFilename(prompt)
        return when (type) {
            PayloadType.SUB_GHZ -> {
                val freq = when {
                    prompt.contains("315", true) -> 315000000L
                    prompt.contains("868", true) -> 868350000L
                    prompt.contains("390", true) -> 390000000L
                    prompt.contains("300", true) -> 300000000L
                    else -> 433920000L
                }
                ForgeBlueprint(
                    title = extractTitle(prompt, "Sub-GHz Signal"),
                    description = prompt,
                    payloadType = type,
                    sections = listOf(
                        BlueprintSection("Frequency", freq.toString(), fieldType = BlueprintFieldType.FREQUENCY),
                        BlueprintSection("Preset", "FuriHalSubGhzPresetOok650Async"),
                        BlueprintSection("Protocol", "Princeton")
                    ),
                    generatedCode = buildString {
                        appendLine("Filetype: Flipper SubGhz Key File")
                        appendLine("Version: 1")
                        appendLine("Frequency: $freq")
                        appendLine("Preset: FuriHalSubGhzPresetOok650Async")
                        appendLine("Protocol: Princeton")
                        appendLine("Bit: 24")
                        appendLine("Key: 00 00 00 00 00 AB CD EF")
                        appendLine("TE: 400")
                    },
                    flipperPath = "/ext/subghz/${sanitized}.sub",
                    rarity = LootRarity.RARE
                )
            }
            PayloadType.INFRARED -> ForgeBlueprint(
                title = extractTitle(prompt, "IR Remote"),
                description = prompt,
                payloadType = type,
                sections = listOf(
                    BlueprintSection("Protocol", "NEC"),
                    BlueprintSection("Signals", "6 buttons", editable = false)
                ),
                generatedCode = buildString {
                    appendLine("Filetype: IR signals file")
                    appendLine("Version: 1")
                    appendLine("#")
                    appendLine("name: Power")
                    appendLine("type: parsed")
                    appendLine("protocol: NEC")
                    appendLine("address: 04 00 00 00")
                    appendLine("command: 08 00 00 00")
                    appendLine("#")
                    appendLine("name: Vol_up")
                    appendLine("type: parsed")
                    appendLine("protocol: NEC")
                    appendLine("address: 04 00 00 00")
                    appendLine("command: 02 00 00 00")
                    appendLine("#")
                    appendLine("name: Vol_dn")
                    appendLine("type: parsed")
                    appendLine("protocol: NEC")
                    appendLine("address: 04 00 00 00")
                    appendLine("command: 03 00 00 00")
                    appendLine("#")
                    appendLine("name: Ch_up")
                    appendLine("type: parsed")
                    appendLine("protocol: NEC")
                    appendLine("address: 04 00 00 00")
                    appendLine("command: 00 00 00 00")
                    appendLine("#")
                    appendLine("name: Ch_dn")
                    appendLine("type: parsed")
                    appendLine("protocol: NEC")
                    appendLine("address: 04 00 00 00")
                    appendLine("command: 01 00 00 00")
                    appendLine("#")
                    appendLine("name: Mute")
                    appendLine("type: parsed")
                    appendLine("protocol: NEC")
                    appendLine("address: 04 00 00 00")
                    appendLine("command: 09 00 00 00")
                },
                flipperPath = "/ext/infrared/${sanitized}.ir",
                rarity = LootRarity.UNCOMMON
            )
            PayloadType.BAD_USB -> ForgeBlueprint(
                title = extractTitle(prompt, "BadUSB Script"),
                description = prompt,
                payloadType = type,
                sections = listOf(
                    BlueprintSection("Target OS", "Windows", fieldType = BlueprintFieldType.DROPDOWN),
                    BlueprintSection("Delay", "500", fieldType = BlueprintFieldType.NUMBER)
                ),
                generatedCode = buildString {
                    appendLine("REM V3SP3R Alchemy Lab - BadUSB Script")
                    appendLine("REM Request: $prompt")
                    appendLine("REM Target: Windows 10/11")
                    appendLine("DEFAULT_DELAY 100")
                    appendLine("DELAY 1000")
                    appendLine("GUI r")
                    appendLine("DELAY 500")
                    appendLine("STRING notepad")
                    appendLine("ENTER")
                    appendLine("DELAY 800")
                    appendLine("STRING Forged by V3SP3R Alchemy Lab")
                    appendLine("ENTER")
                },
                flipperPath = "/ext/badusb/${sanitized}.txt",
                rarity = LootRarity.EPIC
            )
            PayloadType.NFC -> ForgeBlueprint(
                title = extractTitle(prompt, "NFC Tag"),
                description = prompt,
                payloadType = type,
                sections = listOf(
                    BlueprintSection("Device Type", "NTAG215"),
                    BlueprintSection("UID", "04 A1 B2 C3 D4 E5 80", fieldType = BlueprintFieldType.HEX)
                ),
                generatedCode = buildString {
                    appendLine("Filetype: Flipper NFC device")
                    appendLine("Version: 4")
                    appendLine("Device type: NTAG215")
                    appendLine("UID: 04 A1 B2 C3 D4 E5 80")
                    appendLine("ATQA: 44 00")
                    appendLine("SAK: 00")
                    appendLine("Signature: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
                    appendLine("Mifare version: 00 04 04 02 01 00 11 03")
                    appendLine("Counter 0: 0")
                    appendLine("Tearing 0: 00")
                    appendLine("Counter 1: 0")
                    appendLine("Tearing 1: 00")
                    appendLine("Counter 2: 0")
                    appendLine("Tearing 2: 00")
                    appendLine("Pages total: 135")
                    appendLine("Page 0: 04 A1 B2 C3")
                    appendLine("Page 1: D4 E5 80 00")
                    appendLine("Page 2: 00 00 00 00")
                    appendLine("Page 3: E1 10 3E 00")
                    appendLine("Page 4: 03 00 FE 00")
                },
                flipperPath = "/ext/nfc/${sanitized}.nfc",
                rarity = LootRarity.UNCOMMON
            )
            PayloadType.RFID -> ForgeBlueprint(
                title = extractTitle(prompt, "RFID Key"),
                description = prompt,
                payloadType = type,
                sections = listOf(
                    BlueprintSection("Key Type", "EM4100"),
                    BlueprintSection("Data", "01 23 45 67 89", fieldType = BlueprintFieldType.HEX)
                ),
                generatedCode = buildString {
                    appendLine("Filetype: Flipper RFID key")
                    appendLine("Version: 1")
                    appendLine("Key type: EM4100")
                    appendLine("Data: 01 23 45 67 89")
                },
                flipperPath = "/ext/lfrfid/${sanitized}.rfid",
                rarity = LootRarity.UNCOMMON
            )
            PayloadType.IBUTTON -> ForgeBlueprint(
                title = extractTitle(prompt, "iButton Key"),
                description = prompt,
                payloadType = type,
                sections = listOf(
                    BlueprintSection("Key Type", "Dallas"),
                    BlueprintSection("Data", "01 23 45 67 89 AB CD EF", fieldType = BlueprintFieldType.HEX)
                ),
                generatedCode = buildString {
                    appendLine("Filetype: Flipper iButton key")
                    appendLine("Version: 1")
                    appendLine("Key type: Dallas")
                    appendLine("Data: 01 23 45 67 89 AB CD EF")
                },
                flipperPath = "/ext/ibutton/${sanitized}.ibtn",
                rarity = LootRarity.COMMON
            )
            PayloadType.UNKNOWN -> ForgeBlueprint(
                title = extractTitle(prompt, "Payload"),
                description = prompt,
                payloadType = type,
                sections = listOf(BlueprintSection("Content", prompt)),
                generatedCode = "# Forged by V3SP3R Alchemy Lab\n# $prompt",
                flipperPath = "/ext/${sanitized}.txt",
                rarity = LootRarity.COMMON
            )
        }
    }

    // ═══════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════

    private fun extractTitle(prompt: String, fallback: String): String {
        val words = prompt.split("\\s+".toRegex())
            .filter { it.length > 2 }
            .take(5)
        return if (words.isNotEmpty()) {
            words.joinToString(" ")
                .replaceFirstChar { it.uppercase() }
                .take(50)
        } else fallback
    }

    private fun sanitizeFilename(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(40)
            .ifEmpty { "vesper_forge" }
    }
}

/**
 * Result from the forge engine, including the blueprint and validation info.
 */
data class ForgeResult(
    val blueprint: ForgeBlueprint,
    val validation: ForgeValidator.ValidationResult,
    val usedFallback: Boolean = false
)
