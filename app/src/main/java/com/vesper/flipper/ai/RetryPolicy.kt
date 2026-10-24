package com.vesper.flipper.ai

import kotlin.random.Random

/**
 * Pure retry decision + backoff math for OpenRouter calls.
 *
 * Kept free of Android/OkHttp types so it can be unit-tested without a device
 * and reused by any HTTP call site.
 */
object RetryPolicy {

    /** Total attempts, including the first one. */
    const val MAX_ATTEMPTS = 3

    /** Base delay for the first retry. */
    const val INITIAL_BACKOFF_MS = 700L

    /** Hard ceiling for a single backoff sleep. */
    const val MAX_BACKOFF_MS = 8_000L

    /** Hard ceiling for a server-supplied Retry-After value. */
    const val MAX_RETRY_AFTER_MS = 30_000L

    /** 408 Request Timeout, 429 Too Many Requests and 5xx are transient. */
    fun isRetryableStatus(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /** 4xx client errors (other than 408/429) are terminal — retrying cannot help. */
    fun isNonRetryableStatus(code: Int): Boolean = code in 400..499 && !isRetryableStatus(code)

    /**
     * Exponential backoff with "decorrelated jitter": the sleep is drawn from
     * [0.5x, 1.0x] of the capped exponential value, so a fleet of retries does
     * not synchronise on the same instant.
     *
     * @param attempt zero-based retry index (0 = first retry after the initial failure).
     */
    fun backoffDelayMs(
        attempt: Int,
        random: Random = Random.Default
    ): Long {
        val safeAttempt = attempt.coerceIn(0, MAX_ATTEMPTS)
        val exponential = INITIAL_BACKOFF_MS shl safeAttempt
        val capped = exponential.coerceAtMost(MAX_BACKOFF_MS)
        val jitterFactor = 0.5 + (random.nextDouble() * 0.5)
        return (capped * jitterFactor).toLong().coerceIn(1L, MAX_BACKOFF_MS)
    }

    /**
     * Parse a Retry-After header. Accepts delta-seconds (the form OpenRouter
     * sends) and HTTP-date. Returns null for absent/invalid/negative values.
     */
    fun parseRetryAfterMs(
        header: String?,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Long? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null

        raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return (seconds * 1000L).coerceAtMost(MAX_RETRY_AFTER_MS)
        }

        return try {
            val parsed = java.time.ZonedDateTime.parse(
                raw,
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant().toEpochMilli()
            val delta = parsed - nowEpochMs
            if (delta <= 0) null else delta.coerceAtMost(MAX_RETRY_AFTER_MS)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decide how long to wait before retrying.
     *
     * A server Retry-After always wins, clamped to [MAX_RETRY_AFTER_MS]; it is
     * never allowed to hang a user-facing request for minutes.
     */
    fun delayForRetry(
        attempt: Int,
        retryAfterHeader: String? = null,
        random: Random = Random.Default
    ): Long {
        parseRetryAfterMs(retryAfterHeader)?.let { return it.coerceAtLeast(INITIAL_BACKOFF_MS / 2) }
        return backoffDelayMs(attempt, random)
    }
}
