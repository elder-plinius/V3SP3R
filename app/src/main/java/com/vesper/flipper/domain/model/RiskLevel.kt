package com.vesper.flipper.domain.model

import java.util.Locale

/**
 * Risk classification for Flipper operations.
 * Android computes the real risk level, ignoring the AI's assessment.
 */
enum class RiskLevel {
    /**
     * Safe operations that execute automatically.
     * Examples: list, read
     */
    LOW,

    /**
     * Modifications within project scope.
     * Shows diff before applying.
     * Examples: write inside project scope
     */
    MEDIUM,

    /**
     * Dangerous operations requiring explicit confirmation.
     * Examples: delete, move, overwrite, mass operations
     */
    HIGH,

    /**
     * Protected paths that require settings unlock.
     * Examples: system files, firmware areas
     */
    BLOCKED
}

/**
 * Risk assessment result with detailed information
 */
data class RiskAssessment(
    val level: RiskLevel,
    val reason: String,
    val affectedPaths: List<String>,
    val requiresDiff: Boolean,
    val requiresConfirmation: Boolean,
    val blockedReason: String? = null
)

/**
 * Single source of truth for path classification.
 *
 * Every path that reaches the Flipper is normalised through [canonical] before
 * it is classified. Normalisation lowercases and resolves `.`/`..` segments so
 * case tricks (`/INT/...`) and traversal tricks (`/ext/../int/...`) cannot be
 * used to reach a protected area. Flipper storage is FAT-backed and therefore
 * case-insensitive, so case-insensitive comparison matches device behaviour.
 *
 * Callers: [com.vesper.flipper.domain.executor.RiskAssessor],
 * [com.vesper.flipper.security.SecurityUtils], [com.vesper.flipper.ai.FlipperToolExecutor].
 */
object ProtectedPaths {

    /** Internal flash: settings, region, firmware metadata. */
    const val INTERNAL_ROOT = "/int"

    /** External SD root. */
    const val EXTERNAL_ROOT = "/ext"

    /** Firmware update drop-box on the SD card. */
    const val FIRMWARE_UPDATE_ROOT = "/ext/update"

    /** Region lock file living outside /int. */
    const val EXT_REGION_FILE = "/ext/.region"

    /** Installed Flipper applications (executables). */
    const val APPS_ROOT = "/ext/apps"

    /**
     * Paths that require an explicit Settings unlock for ANY operation.
     * Kept as a public list for backwards compatibility with earlier callers.
     */
    val SYSTEM_PATHS = listOf(INTERNAL_ROOT, EXT_REGION_FILE)

    /**
     * Firmware update areas. `/int/update` is already covered by [INTERNAL_ROOT];
     * it is listed explicitly so the blocked reason can be specific.
     */
    val FIRMWARE_PATHS = listOf("$INTERNAL_ROOT/update", FIRMWARE_UPDATE_ROOT)

    /**
     * File types that hold key material and are always protected.
     * Compared case-insensitively.
     */
    val SENSITIVE_EXTENSIONS = listOf(".key", ".priv", ".secret")

    /**
     * Canonical form used for every comparison: absolute, lowercase, no empty
     * or `.` segments, `..` resolved, no trailing slash (except root).
     */
    fun canonical(path: String): String {
        val raw = path.trim().replace('\\', '/')
        if (raw.isEmpty()) return "/"
        val absolute = if (raw.startsWith("/")) raw else "/$raw"

        val segments = ArrayList<String>()
        for (segment in absolute.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(segment.lowercase(Locale.ROOT))
            }
        }
        return "/" + segments.joinToString("/")
    }

    /** True when [child] is [root] itself or lives underneath it. */
    fun isWithin(child: String, root: String): Boolean {
        val canonicalChild = canonical(child)
        val canonicalRoot = canonical(root)
        if (canonicalRoot == "/") return true
        return canonicalChild == canonicalRoot || canonicalChild.startsWith("$canonicalRoot/")
    }

    fun isSystemPath(path: String): Boolean {
        val canonicalPath = canonical(path)
        return isWithin(canonicalPath, INTERNAL_ROOT) || canonicalPath == EXT_REGION_FILE
    }

    fun isFirmwarePath(path: String): Boolean = isWithin(path, FIRMWARE_UPDATE_ROOT)

    fun isSensitiveFile(path: String): Boolean {
        val canonicalPath = canonical(path)
        return SENSITIVE_EXTENSIONS.any { canonicalPath.endsWith(it) }
    }

    /** Any path that must be unlocked in Settings before the device accepts a command. */
    fun isProtected(path: String): Boolean =
        isSystemPath(path) || isFirmwarePath(path) || isSensitiveFile(path)

    /**
     * True for the installed-app directory. Writes here install executables, so
     * they always require explicit confirmation — even with auto-approve on.
     */
    fun isExecutableArea(path: String): Boolean = isWithin(path, APPS_ROOT)

    /** True when [path] resolves inside [projectRoot] and [projectRoot] is a real project directory. */
    fun isInsideProject(path: String, projectRoot: String): Boolean {
        val canonicalRoot = canonical(projectRoot)
        if (canonicalRoot == "/" || canonicalRoot == EXTERNAL_ROOT || canonicalRoot == INTERNAL_ROOT) {
            return false
        }
        return isWithin(path, canonicalRoot)
    }

    /** Short human explanation used in blocked results and audit entries. */
    fun protectedReason(path: String): String = when {
        isSystemPath(path) -> "System path requires settings unlock"
        isFirmwarePath(path) -> "Firmware path requires settings unlock"
        isSensitiveFile(path) -> "Sensitive file type requires settings unlock"
        else -> "Protected path requires settings unlock"
    }
}
