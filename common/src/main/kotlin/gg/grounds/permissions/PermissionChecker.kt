package gg.grounds.permissions

import java.time.Clock
import java.time.Instant
import java.util.UUID

interface Permissions {
    fun hasPermission(playerId: UUID, permission: String): Boolean

    fun snapshot(playerId: UUID): PermissionSnapshot?
}

class PermissionCheckScope
private constructor(val serverType: String? = null, val server: String? = null) {
    companion object {
        fun global(): PermissionCheckScope = PermissionCheckScope()

        fun serverType(serverType: String): PermissionCheckScope =
            PermissionCheckScope(serverType = serverType)

        fun server(server: String, serverType: String): PermissionCheckScope =
            PermissionCheckScope(serverType = serverType, server = server)

        fun serverOnly(server: String): PermissionCheckScope = PermissionCheckScope(server = server)
    }
}

class SnapshotPermissions(
    private val snapshots: Map<UUID, PermissionSnapshot>,
    private val defaultScope: PermissionCheckScope = PermissionCheckScope.global(),
    private val clock: Clock = Clock.systemUTC(),
) : Permissions {
    override fun hasPermission(playerId: UUID, permission: String): Boolean {
        val snapshot = snapshots[playerId] ?: return false
        return hasPermission(snapshot, permission, defaultScope, clock.instant())
    }

    override fun snapshot(playerId: UUID): PermissionSnapshot? = snapshots[playerId]

    fun hasPermission(
        snapshot: PermissionSnapshot,
        permission: String,
        scope: PermissionCheckScope = defaultScope,
        now: Instant = clock.instant(),
    ): Boolean {
        if (!snapshot.expiresAt.isAfter(now)) {
            return false
        }

        val candidate =
            (snapshot.allowPatterns + snapshot.denyPatterns)
                .asSequence()
                .filterNot { it.isExpired(now) }
                .filter { PermissionPattern.matches(it.pattern, permission) }
                .mapNotNull { grant -> grant.toCandidate(scope) }
                .maxWithOrNull(
                    compareBy<PermissionCandidate> { it.scopeSpecificity }
                        .thenBy { it.sourceSpecificity }
                        .thenBy { it.patternSpecificity }
                        .thenBy { it.effectSpecificity }
                )

        return candidate?.grant?.effect == PermissionEffect.ALLOW
    }

    private fun PermissionGrant.toCandidate(scope: PermissionCheckScope): PermissionCandidate? {
        val scopeSpecificity = this.scope.specificityFor(scope) ?: return null
        return PermissionCandidate(
            grant = this,
            scopeSpecificity = scopeSpecificity,
            sourceSpecificity =
                when (source) {
                    PermissionGrantSource.ROLE -> 0
                    PermissionGrantSource.PLAYER -> 1
                },
            patternSpecificity = PermissionPattern.specificity(pattern),
            effectSpecificity =
                when (effect) {
                    PermissionEffect.ALLOW -> 0
                    PermissionEffect.DENY -> 1
                },
        )
    }

    private fun PermissionScope.specificityFor(scope: PermissionCheckScope): Int? =
        when (kind) {
            PermissionScopeKind.GLOBAL -> 0
            PermissionScopeKind.SERVER_TYPE -> if (value == scope.serverType) 1 else null
            PermissionScopeKind.SERVER -> if (value == scope.server) 2 else null
        }

    private fun PermissionGrant.isExpired(now: Instant): Boolean =
        expiresAt?.let { !it.isAfter(now) } ?: false
}

private object PermissionPattern {
    fun matches(pattern: String, permission: String): Boolean =
        when {
            pattern == "*" -> true
            pattern.endsWith(".*") -> {
                val prefix = pattern.removeSuffix("*")
                permission.startsWith(prefix) && permission.length > prefix.length
            }
            else -> pattern == permission
        }

    fun specificity(pattern: String): Int =
        when {
            pattern == "*" -> 0
            pattern.endsWith(".*") -> 1_000 + pattern.removeSuffix(".*").length
            else -> 2_000 + pattern.length
        }
}

private data class PermissionCandidate(
    val grant: PermissionGrant,
    val scopeSpecificity: Int,
    val sourceSpecificity: Int,
    val patternSpecificity: Int,
    val effectSpecificity: Int,
)
