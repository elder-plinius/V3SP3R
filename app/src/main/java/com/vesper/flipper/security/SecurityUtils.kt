package com.vesper.flipper.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Security utilities for path validation, encryption, and input sanitization.
 */
@Singleton
class SecurityUtils @Inject constructor() {

    companion object {
        // Maximum path length to prevent buffer attacks
        private const val MAX_PATH_LENGTH = 512

        // Maximum content size (10MB)
        const val MAX_CONTENT_SIZE = 10 * 1024 * 1024

        // Valid path prefixes for Flipper
        private val ALLOWED_PATH_PREFIXES = setOf("/ext/", "/int/")

        // Dangerous path segments
        private val DANGEROUS_SEGMENTS = setOf("..", "...", "~", "\u0000")
    }

    /**
     * Validate and sanitize a Flipper path.
     * Prevents path traversal attacks.
     *
     * @throws SecurityException if path is invalid or malicious
     */
    fun validatePath(path: String): String {
        // Check for null bytes (null byte injection)
        if (path.contains('\u0000')) {
            throw SecurityException("Path contains null bytes")
        }

        // Check length
        if (path.length > MAX_PATH_LENGTH) {
            throw SecurityException("Path exceeds maximum length")
        }

        // Check for empty path
        if (path.isBlank()) {
            throw SecurityException("Path cannot be empty")
        }

        // Normalize the path
        val normalized = normalizePath(path)

        // Check for path traversal attempts
        if (containsTraversal(normalized)) {
            throw SecurityException("Path traversal detected")
        }

        // Validate path starts with allowed prefix
        if (!ALLOWED_PATH_PREFIXES.any { normalized.startsWith(it) } && normalized != "/ext" && normalized != "/int") {
            throw SecurityException("Path must start with /ext/ or /int/")
        }

        return normalized
    }

    /**
     * Normalize a path by removing redundant separators and resolving ./ segments
     */
    fun normalizePath(path: String): String {
        var result = path
            .replace("\\", "/")     // Normalize separators
            .replace(Regex("/+"), "/") // Remove duplicate slashes
            .trimEnd('/')           // Remove trailing slash

        // Handle . segments (current directory)
        result = result.replace("/./", "/")
        if (result.endsWith("/.")) {
            result = result.dropLast(2)
        }

        return result.ifEmpty { "/" }
    }

    /**
     * Check if path contains traversal attempts
     */
    private fun containsTraversal(path: String): Boolean {
        val segments = path.split("/")
        var depth = 0

        for (segment in segments) {
            when {
                segment == ".." -> {
                    depth--
                    if (depth < 0) return true // Trying to escape root
                }
                segment.isNotEmpty() && segment != "." -> {
                    depth++
                }
            }
        }

        // Also check for encoded traversal
        val decodedPath = try {
            java.net.URLDecoder.decode(path, "UTF-8")
        } catch (e: Exception) {
            path
        }

        return decodedPath.contains("..") ||
               path.contains("%2e%2e", ignoreCase = true) ||
               path.contains("%252e", ignoreCase = true)
    }

    /**
     * Validate content size to prevent memory exhaustion
     */
    fun validateContentSize(content: String) {
        if (content.length > MAX_CONTENT_SIZE) {
            throw SecurityException("Content exceeds maximum size of ${MAX_CONTENT_SIZE / 1024 / 1024}MB")
        }
    }

    /**
     * Validate content size for byte arrays
     */
    fun validateContentSize(content: ByteArray) {
        if (content.size > MAX_CONTENT_SIZE) {
            throw SecurityException("Content exceeds maximum size of ${MAX_CONTENT_SIZE / 1024 / 1024}MB")
        }
    }

    /**
     * Sanitize a filename to prevent injection
     */
    fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")  // Replace dangerous chars
            .replace("..", "_")                       // Prevent traversal
            .take(255)                               // Limit length
    }

    /**
     * Check if a path is within allowed boundaries
     */
    fun isPathAllowed(path: String, allowedRoot: String): Boolean {
        val normalizedPath = normalizePath(path)
        val normalizedRoot = normalizePath(allowedRoot)

        // Use canonical comparison
        return normalizedPath.startsWith(normalizedRoot) && !containsTraversal(normalizedPath)
    }
}

/**
 * Encrypted storage for sensitive data like API keys.
 */
class EncryptedStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "vesper_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putString(key: String, value: String) {
        encryptedPrefs.edit().putString(key, value).apply()
    }

    fun getString(key: String): String? {
        return encryptedPrefs.getString(key, null)
    }

    fun remove(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }

    fun clear() {
        encryptedPrefs.edit().clear().apply()
    }
}

/**
 * Rate limiter for API calls
 */
class RateLimiter(
    private val maxRequests: Int = 30,
    private val windowMs: Long = 60_000 // 1 minute
) {
    private val requestTimestamps = mutableListOf<Long>()
    private val lock = Any()

    /**
     * Check if a request is allowed under the rate limit.
     * @return true if allowed, false if rate limited
     */
    fun tryAcquire(): Boolean {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val windowStart = now - windowMs

            // Remove expired timestamps
            requestTimestamps.removeAll { it < windowStart }

            return if (requestTimestamps.size < maxRequests) {
                requestTimestamps.add(now)
                true
            } else {
                false
            }
        }
    }

    /**
     * Get remaining requests in current window
     */
    fun remainingRequests(): Int {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val windowStart = now - windowMs
            requestTimestamps.removeAll { it < windowStart }
            return (maxRequests - requestTimestamps.size).coerceAtLeast(0)
        }
    }

    /**
     * Get time until rate limit resets (in ms)
     */
    fun timeUntilReset(): Long {
        synchronized(lock) {
            if (requestTimestamps.isEmpty()) return 0
            val oldest = requestTimestamps.minOrNull() ?: return 0
            return (oldest + windowMs - System.currentTimeMillis()).coerceAtLeast(0)
        }
    }
}

/**
 * Input validator for various data types
 */
object InputValidator {

    private val SAFE_STRING_PATTERN = Regex("^[\\w\\s.,!?@#\$%^&*()\\-+=\\[\\]{}|;:'\"<>/\\\\`~]*\$")

    /**
     * Validate that a string contains only safe characters
     */
    fun isSafeString(input: String): Boolean {
        return input.length <= 10000 && !input.contains('\u0000')
    }

    /**
     * Validate an API key format.
     * Accepts alphanumeric characters plus common delimiters used by various
     * providers (e.g. OpenRouter "sk-or-v1-…", OpenAI "sk-proj-…").
     */
    fun isValidApiKey(key: String): Boolean {
        return key.length in 10..500 &&
               key.matches(Regex("^[a-zA-Z0-9_.\\-:]+\$"))
    }

    /**
     * Sanitize user input for display
     */
    fun sanitizeForDisplay(input: String): String {
        return input
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("&", "&amp;")
            .take(10000)
    }

    /**
     * Validate JSON structure by attempting a parse.
     */
    fun isValidJson(json: String): Boolean {
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
            true
        } catch (_: Exception) {
            false
        }
    }
}
