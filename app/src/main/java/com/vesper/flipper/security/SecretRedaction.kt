package com.vesper.flipper.security

import java.util.Locale

/**
 * Pure-Kotlin helpers for keeping API keys out of logs, error strings and UI.
 *
 * Used at every boundary where an exception message, HTTP error body or model
 * error could echo credential material.
 */
object SecretRedaction {

    private const val REDACTED = "[REDACTED]"

    /**
     * Patterns are ordered most-specific first so `sk-or-v1-...` is not
     * half-matched by the generic `sk-...` rule.
     */
    private val PATTERNS = listOf(
        Regex("""\bsk-or-v1-[A-Za-z0-9._-]{6,}"""),
        Regex("""\bsk-[A-Za-z0-9._-]{16,}"""),
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{12,}"""),
        Regex("""(?i)\b(api[_-]?key|authorization)["'\s:=]+[A-Za-z0-9._~+/=-]{12,}""")
    )

    /**
     * Scrub anything that looks like a credential from [text].
     * @return the scrubbed text, or null when [text] was null.
     */
    fun redact(text: String?): String? {
        val source = text ?: return null
        var result = source
        for (pattern in PATTERNS) {
            result = pattern.replace(result, REDACTED)
        }
        return result
    }

    /**
     * Render a key for display/logging without revealing it.
     * Short or empty values collapse to a placeholder so length is not leaked.
     */
    fun mask(key: String?): String {
        if (key.isNullOrBlank()) return "<unset>"
        if (key.length <= 12) return "•••"
        return "${key.take(6)}…${key.takeLast(4)}"
    }

    /** True when [value] already looks redacted/masked (used to avoid double work). */
    fun isRedacted(value: String?): Boolean =
        value != null && value.contains(REDACTED)

    /** Lowercase helper used by comparison-based call sites. */
    fun normalizedKey(key: String?): String = key.orEmpty().trim().lowercase(Locale.ROOT)
}
