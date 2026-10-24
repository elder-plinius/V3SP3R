package com.vesper.flipper.domain.service

import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.Permission
import com.vesper.flipper.domain.model.PermissionRequest
import com.vesper.flipper.domain.model.ProtectedPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages path-based permissions for file operations.
 * Permissions are scoped, time-limited, and explicitly granted.
 *
 * Protected paths (/int, /ext/update, key material) are separate from regular
 * permissions: they are locked by default and only the user can unlock them
 * from Settings. Unlock state is exposed as [unlockedPaths] so the Settings
 * screen can render it without polling.
 */
@Singleton
class PermissionService @Inject constructor() {

    private val permissions = ConcurrentHashMap<String, Permission>()

    /** Canonical protected root -> epoch millis expiry. */
    private val unlockedProtectedPaths = ConcurrentHashMap<String, Long>()
    private val pendingRequests = ConcurrentHashMap<String, PermissionRequest>()

    private val _activePermissions = MutableStateFlow<List<Permission>>(emptyList())
    val activePermissions: StateFlow<List<Permission>> = _activePermissions.asStateFlow()

    private val _pendingRequest = MutableStateFlow<PermissionRequest?>(null)
    val pendingRequest: StateFlow<PermissionRequest?> = _pendingRequest.asStateFlow()

    /**
     * Canonical protected paths that are currently unlocked, in unlock order.
     * Empty by default; updates whenever the user unlocks/locks a path or an
     * unlock expires.
     */
    private val _unlockedPaths = MutableStateFlow<List<String>>(emptyList())
    val unlockedPaths: StateFlow<List<String>> = _unlockedPaths.asStateFlow()

    /**
     * Check if an action on a path is permitted
     */
    fun hasPermission(path: String, action: CommandAction): Boolean {
        cleanupExpired()
        return permissions.values.any { it.matches(path, action) }
    }

    /**
     * Grant a permission
     */
    fun grantPermission(permission: Permission) {
        permissions[permission.id] = permission
        updateActivePermissions()
    }

    /**
     * Grant permission for a specific path and actions
     */
    fun grantPathPermission(
        path: String,
        actions: Set<CommandAction>,
        duration: Long = Permission.DURATION_15_MINUTES
    ): Permission {
        val permission = Permission.createForPath(path, actions, duration)
        grantPermission(permission)
        return permission
    }

    /**
     * Grant project scope permission
     */
    fun grantProjectScope(projectPath: String): Permission {
        val permission = Permission.createProjectScope(projectPath)
        grantPermission(permission)
        return permission
    }

    /**
     * Revoke a permission by ID
     */
    fun revokePermission(permissionId: String): Boolean {
        val removed = permissions.remove(permissionId) != null
        if (removed) updateActivePermissions()
        return removed
    }

    /**
     * Revoke all permissions for a path pattern
     */
    fun revokePathPermissions(pathPattern: String) {
        permissions.entries.removeIf { it.value.pathPattern == pathPattern }
        updateActivePermissions()
    }

    /**
     * Revoke all permissions and relock every protected path.
     */
    fun revokeAll() {
        permissions.clear()
        unlockedProtectedPaths.clear()
        updateActivePermissions()
        publishUnlockedPaths()
    }

    /**
     * Check if a protected path has been unlocked in settings.
     *
     * Matching is root-aware: unlocking `/int` also unlocks `/int/settings.txt`,
     * because users unlock a protected *area*, not every individual file.
     */
    fun isProtectedPathUnlocked(path: String): Boolean {
        val canonicalPath = ProtectedPaths.canonical(path)
        val now = System.currentTimeMillis()

        var unlocked = false
        var expired = false
        for ((root, expiry) in unlockedProtectedPaths) {
            if (expiry < now) {
                expired = true
                unlockedProtectedPaths.remove(root, expiry)
                continue
            }
            if (ProtectedPaths.isWithin(canonicalPath, root)) {
                unlocked = true
            }
        }
        // Keep the published snapshot honest after lazy expiry removal.
        if (expired) publishUnlockedPaths()
        return unlocked
    }

    /** Suspend-friendly alias for UI call sites that already run in a coroutine. */
    suspend fun isUnlocked(path: String): Boolean = isProtectedPathUnlocked(path)

    /**
     * Unlock a protected path (called from Settings).
     * @param path protected root or any path inside it.
     * @param duration lifetime of the unlock; [Permission.DURATION_1_HOUR] by default.
     */
    fun unlockProtectedPath(path: String, duration: Long = Permission.DURATION_1_HOUR) {
        val canonicalPath = ProtectedPaths.canonical(path)
        if (!ProtectedPaths.isProtected(canonicalPath) && canonicalPath != ProtectedPaths.INTERNAL_ROOT) {
            return
        }
        unlockedProtectedPaths[canonicalPath] = System.currentTimeMillis() + duration
        publishUnlockedPaths()
    }

    /**
     * Lock a protected path.
     * @param path protected root to relock; descendants unlocked separately are kept.
     */
    fun lockProtectedPath(path: String) {
        unlockedProtectedPaths.remove(ProtectedPaths.canonical(path))
        publishUnlockedPaths()
    }

    /** Lock every protected path at once (panic switch). */
    fun lockAllProtectedPaths() {
        unlockedProtectedPaths.clear()
        publishUnlockedPaths()
    }

    /** Remaining unlock time in milliseconds for [path], or null when locked. */
    fun remainingUnlockMs(path: String): Long? {
        if (!isProtectedPathUnlocked(path)) return null
        val canonicalPath = ProtectedPaths.canonical(path)
        val now = System.currentTimeMillis()
        val best = unlockedProtectedPaths
            .filterKeys { ProtectedPaths.isWithin(canonicalPath, it) }
            .values
            .maxOrNull()
            ?: return null
        return (best - now).coerceAtLeast(0)
    }

    /**
     * Get all active (non-expired) permissions
     */
    fun getActivePermissions(): List<Permission> {
        cleanupExpired()
        return permissions.values.filter { it.isValid() }.toList()
    }

    /**
     * Request permission (queues for user approval)
     */
    fun requestPermission(request: PermissionRequest) {
        pendingRequests[request.id] = request
        _pendingRequest.value = request
    }

    /**
     * Approve a pending permission request
     */
    fun approveRequest(requestId: String, duration: Long = Permission.DURATION_15_MINUTES): Permission? {
        val request = pendingRequests.remove(requestId) ?: return null
        if (_pendingRequest.value?.id == requestId) {
            _pendingRequest.value = null
        }

        val permission = Permission.createForPath(
            request.path,
            setOf(request.action),
            duration
        )
        grantPermission(permission)
        return permission
    }

    /**
     * Deny a pending permission request
     */
    fun denyRequest(requestId: String) {
        pendingRequests.remove(requestId)
        if (_pendingRequest.value?.id == requestId) {
            _pendingRequest.value = null
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        permissions.entries.removeIf { !it.value.isValid() }

        var removedUnlock = false
        for ((root, expiry) in unlockedProtectedPaths) {
            if (expiry < now) {
                unlockedProtectedPaths.remove(root, expiry)
                removedUnlock = true
            }
        }
        if (removedUnlock) publishUnlockedPaths()
    }

    private fun publishUnlockedPaths() {
        val now = System.currentTimeMillis()
        val snapshot = unlockedProtectedPaths
            .filterValues { it > now }
            .keys
            .sorted()
        _unlockedPaths.value = snapshot
    }

    private fun updateActivePermissions() {
        _activePermissions.value = getActivePermissions()
    }
}
