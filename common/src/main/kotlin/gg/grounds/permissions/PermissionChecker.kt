package gg.grounds.permissions

import java.time.Clock
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

interface Permissions {
    fun hasPermission(playerId: UUID, permission: String): Boolean

    fun hasPermission(playerId: UUID, permission: String, scope: PermissionCheckScope): Boolean

    fun snapshot(playerId: UUID): PermissionSnapshot?
}

data class PermissionCheckScope(val serverType: String? = null, val server: String? = null) {
    companion object {
        fun global(): PermissionCheckScope = PermissionCheckScope()

        fun serverType(serverType: String): PermissionCheckScope =
            PermissionCheckScope(serverType = serverType)

        fun server(server: String, serverType: String): PermissionCheckScope =
            PermissionCheckScope(serverType = serverType, server = server)

        fun serverOnly(server: String): PermissionCheckScope = PermissionCheckScope(server = server)
    }
}

class InMemoryPermissionSnapshots(initialSnapshots: Map<UUID, PermissionSnapshot> = emptyMap()) {
    private val snapshots = AtomicReference(initialSnapshots.toImmutableSnapshots())

    fun get(playerId: UUID): PermissionSnapshot? = snapshots.get()[playerId]

    fun all(): Map<UUID, PermissionSnapshot> = snapshots.get()

    fun put(snapshot: PermissionSnapshot) {
        snapshots.updateAndGet { existing ->
            (existing + (snapshot.playerId to snapshot)).toImmutableSnapshots()
        }
    }

    fun replaceAll(replacementSnapshots: Map<UUID, PermissionSnapshot>) {
        snapshots.set(replacementSnapshots.toImmutableSnapshots())
    }

    /**
     * Applies [refreshed] and drops every entry [isStale] accepts, atomically against the live map.
     *
     * A blind [replaceAll] would discard snapshots that a concurrent login wrote while the caller
     * was computing its replacement, leaving that player with no snapshot at all.
     */
    fun merge(
        refreshed: Map<UUID, PermissionSnapshot>,
        isStale: (UUID, PermissionSnapshot) -> Boolean,
    ) {
        snapshots.updateAndGet { existing ->
            (existing + refreshed)
                .filterNot { (id, snapshot) -> isStale(id, snapshot) }
                .toImmutableSnapshots()
        }
    }
}

/**
 * [negativePermissions] are the keys the loaded plugins declared as inverted — holding one takes
 * something away instead of granting it. They are never handed out by a wildcard: an operator with
 * `ALLOW *` means "give this player everything", not "mute them".
 */
class SnapshotPermissions(
    private val snapshots: InMemoryPermissionSnapshots,
    private val defaultScope: PermissionCheckScope = PermissionCheckScope.global(),
    private val clock: Clock = Clock.systemUTC(),
    private val negativePermissions: Set<String> = emptySet(),
) : Permissions {
    constructor(
        snapshots: Map<UUID, PermissionSnapshot>,
        defaultScope: PermissionCheckScope = PermissionCheckScope.global(),
        clock: Clock = Clock.systemUTC(),
        negativePermissions: Set<String> = emptySet(),
    ) : this(InMemoryPermissionSnapshots(snapshots), defaultScope, clock, negativePermissions)

    override fun hasPermission(playerId: UUID, permission: String): Boolean {
        return hasPermission(playerId, permission, defaultScope)
    }

    override fun hasPermission(
        playerId: UUID,
        permission: String,
        scope: PermissionCheckScope,
    ): Boolean {
        val snapshot = snapshots.get(playerId) ?: return false
        return hasPermission(snapshot, permission, scope, clock.instant())
    }

    override fun snapshot(playerId: UUID): PermissionSnapshot? = snapshots.get(playerId)

    fun hasPermission(
        snapshot: PermissionSnapshot,
        permission: String,
        scope: PermissionCheckScope = defaultScope,
        now: Instant = clock.instant(),
    ): Boolean {
        if (!snapshot.expiresAt.isAfter(now)) {
            return false
        }

        val negative = permission in negativePermissions

        val candidate =
            (snapshot.allowPatterns + snapshot.denyPatterns)
                .asSequence()
                .filterNot { it.isExpired(now) }
                .filter { PermissionPattern.matches(it.pattern, permission, negative) }
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
    fun matches(pattern: String, permission: String, negative: Boolean = false): Boolean {
        if (pattern == permission) {
            return true
        }
        // A wildcard says "everything", which is never meant to include a permission that takes
        // something away. Those have to be granted by name.
        if (negative) {
            return false
        }
        return when {
            pattern == "*" -> true
            pattern.endsWith(".*") -> {
                val prefix = pattern.removeSuffix("*")
                permission.startsWith(prefix) && permission.length > prefix.length
            }
            else -> false
        }
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

private fun Map<UUID, PermissionSnapshot>.toImmutableSnapshots(): Map<UUID, PermissionSnapshot> =
    Collections.unmodifiableMap(mapValues { (_, snapshot) -> snapshot.toImmutableSnapshot() })

private fun PermissionSnapshot.toImmutableSnapshot(): PermissionSnapshot =
    copy(
        allowPatterns = allowPatterns.toImmutableList(),
        denyPatterns = denyPatterns.toImmutableList(),
        roleKeys = roleKeys.toImmutableSet(),
        roleMetadata = roleMetadata.toImmutableList(),
    )

private fun <T> Collection<T>.toImmutableList(): List<T> = Collections.unmodifiableList(toList())

private fun <T> Collection<T>.toImmutableSet(): Set<T> =
    Collections.unmodifiableSet(toCollection(LinkedHashSet()))
