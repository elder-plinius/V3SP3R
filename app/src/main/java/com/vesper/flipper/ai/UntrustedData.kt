package com.vesper.flipper.ai

/**
 * Delimits device-sourced text on its way into a model request.
 *
 * Everything a Flipper returns — file contents, directory names, device
 * metadata, app names, remote catalog entries — is attacker-influenced data:
 * the user may own the hardware, and the device may hold files written by
 * someone else. Wrapping it in stable markers lets the system prompt state
 * unambiguously that the enclosed text is data, never instructions.
 */
object UntrustedData {

    const val BEGIN = "<<<UNTRUSTED_DEVICE_DATA>>>"
    const val END = "<<<END_UNTRUSTED_DEVICE_DATA>>>"

    private const val MAX_LABEL_LENGTH = 40

    /**
     * Wrap a tool result payload so the model can see exactly where device data
     * starts and ends. Marker spoofing inside the payload is neutralised first.
     */
    fun wrapToolResult(content: String, toolName: String? = null): String {
        val label = sanitizeLabel(toolName)
        return buildString(content.length + BEGIN.length + END.length + 32) {
            append(BEGIN)
            if (label != null) append(" tool=").append(label)
            append('\n')
            append(neutralize(content))
            append('\n')
            append(END)
        }
    }

    /**
     * Wrap a short device-derived field (file name, device name, app name).
     * Intended for values interpolated into larger strings.
     */
    fun wrapField(kind: String, value: String): String {
        val label = sanitizeLabel(kind) ?: "value"
        return "[$label: ${neutralize(value)}]"
    }

    /**
     * Defuse attempts to forge the delimiters from inside the payload.
     * Everything else is preserved byte-for-byte.
     */
    fun neutralize(content: String): String {
        if (!content.contains("<<<")) return content
        return content
            .replace(BEGIN, "[begin-marker-removed]")
            .replace(END, "[end-marker-removed]")
            .replace("<<<UNTRUSTED", "[marker-prefix-removed]")
            .replace("<<<END_UNTRUSTED", "[marker-prefix-removed]")
    }

    private fun sanitizeLabel(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed
            .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
            .take(MAX_LABEL_LENGTH)
            .takeIf { it.isNotEmpty() }
    }
}
