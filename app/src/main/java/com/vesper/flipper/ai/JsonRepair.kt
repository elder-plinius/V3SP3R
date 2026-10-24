package com.vesper.flipper.ai

import kotlinx.serialization.json.Json

/**
 * Repairs the common JSON malformations produced by LLMs before we hand the
 * payload to kotlinx.serialization.
 *
 * Handles trailing commas, unterminated strings, unclosed braces/brackets and
 * markdown code fences. All transformations are string-context aware so a path
 * containing `",}"` inside a JSON string is not corrupted.
 *
 * Pure Kotlin (kotlinx.serialization only) so it is unit-testable on the JVM.
 */
object JsonRepair {

    /** Inputs above this size are returned untouched to avoid resource exhaustion. */
    const val MAX_INPUT_LENGTH = 100_000

    /** Maximum number of unclosed brackets we are willing to synthesise. */
    const val MAX_BRACKET_DEPTH = 10

    private val parser = Json { ignoreUnknownKeys = true }

    fun repair(input: String): String {
        if (input.length > MAX_INPUT_LENGTH) return input

        var s = input.trim()

        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            s = if (firstNewline > 0) s.substring(firstNewline + 1) else s.removePrefix("```")
            if (s.endsWith("```")) {
                s = s.dropLast(3).trimEnd()
            }
        }

        // Single-pass walk: track string context, trailing commas, and bracket depth.
        var inString = false
        var escaped = false
        val stack = mutableListOf<Char>()
        val sb = StringBuilder(s.length + 16)
        // Buffer to hold a comma + optional whitespace so we can drop it
        // if the next non-whitespace char is } or ].
        var pendingComma: StringBuilder? = null

        for (ch in s) {
            if (inString) {
                pendingComma?.let { sb.append(it); pendingComma = null }
                sb.append(ch)
                if (escaped) {
                    escaped = false
                } else when (ch) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else {
                when (ch) {
                    ',' -> {
                        // Buffer the comma — flush it only if not followed by } or ]
                        if (pendingComma == null) pendingComma = StringBuilder()
                        pendingComma!!.append(ch)
                    }
                    '}', ']' -> {
                        // Drop any buffered trailing comma before a closer
                        pendingComma = null
                        sb.append(ch)
                        if (ch == '}' && stack.lastOrNull() == '{') stack.removeLast()
                        else if (ch == ']' && stack.lastOrNull() == '[') stack.removeLast()
                    }
                    else -> {
                        if (ch.isWhitespace() && pendingComma != null) {
                            // Accumulate whitespace into the pending comma buffer
                            // so ",  }" correctly drops the comma + whitespace.
                            pendingComma!!.append(ch)
                        } else {
                            // Non-whitespace after comma — flush buffer, it's not trailing
                            pendingComma?.let { sb.append(it); pendingComma = null }
                            sb.append(ch)
                            when (ch) {
                                '"' -> inString = true
                                '{' -> stack.add('{')
                                '[' -> stack.add('[')
                            }
                        }
                    }
                }
            }
        }

        // Flush any trailing buffered comma (will be stripped if we close brackets)
        pendingComma?.let { sb.append(it) }

        // If JSON already parses cleanly after comma repair, return early.
        val commaRepaired = sb.toString()
        runCatching { parser.parseToJsonElement(commaRepaired) }.onSuccess { return commaRepaired }

        // Close an unterminated string
        if (inString) sb.append('"')

        // Remove a dangling comma/whitespace at the end before closing brackets
        while (sb.isNotEmpty() && (sb.last() == ',' || sb.last().isWhitespace())) {
            sb.deleteCharAt(sb.length - 1)
        }

        // Close unclosed braces/brackets in reverse order.
        // Limit to a reasonable depth to avoid synthesizing deeply nested structures
        // from adversarial input.
        val unclosedCount = stack.size.coerceAtMost(MAX_BRACKET_DEPTH)
        for (i in (stack.size - unclosedCount) until stack.size) {
            val idx = stack.size - 1 - (i - (stack.size - unclosedCount))
            when (stack[idx]) {
                '{' -> sb.append('}')
                '[' -> sb.append(']')
            }
        }

        return sb.toString()
    }
}
