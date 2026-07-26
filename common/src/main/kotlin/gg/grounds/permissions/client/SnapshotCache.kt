package gg.grounds.permissions.client

import gg.grounds.permissions.PermissionSnapshot
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SnapshotCache {
    private val snapshots = ConcurrentHashMap<UUID, PermissionSnapshot>()

    fun put(snapshot: PermissionSnapshot) {
        snapshots[snapshot.playerId] = snapshot.cacheCopy()
    }

    fun valid(playerId: UUID, now: Instant): PermissionSnapshot? {
        val snapshot = snapshots[playerId] ?: return null
        if (!snapshot.expiresAt.isAfter(now)) {
            snapshots.remove(playerId, snapshot)
            return null
        }
        return snapshot.cacheCopy()
    }
}

private fun PermissionSnapshot.cacheCopy(): PermissionSnapshot =
    copy(
        allowPatterns = allowPatterns.toList(),
        denyPatterns = denyPatterns.toList(),
        roleKeys = roleKeys.toSet(),
        roleMetadata = roleMetadata.toList(),
    )
