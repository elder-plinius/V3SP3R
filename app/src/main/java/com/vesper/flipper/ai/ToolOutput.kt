package com.vesper.flipper.ai

/**
 * Byte-bounded truncation for model-facing text.
 *
 * Tool results can contain entire files. Sending them verbatim burns context
 * and can silently evict the system prompt, so every device-derived payload is
 * clamped with an explicit marker the model can reason about.
 */
object ToolOutput {

    /** Default per-field cap for a single tool result (file contents, CLI output). */
    const val MAX_FIELD_BYTES = 16_000

    /** Hard cap for a serialized tool result payload. */
    const val MAX_RESULT_BYTES = 24_000

    /** Directory listings are clamped by count as well. */
    const val MAX_ENTRIES = 300

    /**
     * Truncate [text] to at most [maxBytes] UTF-8 bytes at a character boundary.
     * Returns [text] unchanged when it already fits, so callers can apply this
     * unconditionally.
     *
     * The marker reports exact sizes so the model knows data is missing instead
     * of assuming the file really ended there.
     */
    fun truncate(text: String, maxBytes: Int = MAX_FIELD_BYTES): String {
        if (text.isEmpty()) return text
        if (maxBytes <= 0) return ""
        val totalBytes = text.toByteArray(Charsets.UTF_8).size
        if (totalBytes <= maxBytes) return text

        val marker = "\n…[truncated: kept %d of %d bytes — ask for a smaller range if more is needed]"
        val reserved = marker.format(0, totalBytes).toByteArray(Charsets.UTF_8).size
        val budget = (maxBytes - reserved).coerceAtLeast(0)

        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val size = text.substring(0, mid).toByteArray(Charsets.UTF_8).size
            if (size <= budget) low = mid else high = mid - 1
        }

        // Never split a surrogate pair — an unpaired surrogate becomes '?' on encode.
        var end = low
        if (end > 0 && end < text.length && Character.isHighSurrogate(text[end - 1])) {
            end -= 1
        }

        val kept = text.substring(0, end)
        val keptBytes = kept.toByteArray(Charsets.UTF_8).size
        return kept + marker.format(keptBytes, totalBytes)
    }

    /** True when [text] would be truncated by [truncate] with the given budget. */
    fun wouldTruncate(text: String, maxBytes: Int = MAX_FIELD_BYTES): Boolean =
        text.toByteArray(Charsets.UTF_8).size > maxBytes
}
