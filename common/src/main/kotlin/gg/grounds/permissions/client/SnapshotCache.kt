package gg.grounds.permissions.client

import gg.grounds.permissions.PermissionSnapshot
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.locks.ReentrantLock

class SnapshotCache {
    private val snapshots = ConcurrentHashMap<UUID, PermissionSnapshot>()
    private val expirations =
        PriorityBlockingQueue<Expiration>(11, compareBy<Expiration> { it.expiresAt })
    private val evictionLock = ReentrantLock()

    fun put(snapshot: PermissionSnapshot, now: Instant) {
        evictExpired(now)
        if (!snapshot.expiresAt.isAfter(now)) {
            snapshots.remove(snapshot.playerId)
            return
        }
        val cached = snapshot.cacheCopy()
        snapshots[snapshot.playerId] = cached
        expirations.add(Expiration(snapshot.playerId, cached.expiresAt))
    }

    fun valid(playerId: UUID, now: Instant): PermissionSnapshot? {
        evictExpired(now)
        val snapshot = snapshots[playerId] ?: return null
        if (!snapshot.expiresAt.isAfter(now)) {
            snapshots.remove(playerId, snapshot)
            return null
        }
        return snapshot.cacheCopy()
    }

    fun remove(playerId: UUID) {
        snapshots.remove(playerId)
    }

    private fun evictExpired(now: Instant) {
        if (!evictionLock.tryLock()) return
        try {
            while (true) {
                if (expirations.peek()?.expiresAt?.isAfter(now) != false) return
                val expiration = expirations.poll() ?: return
                snapshots.computeIfPresent(expiration.playerId) { _, snapshot ->
                    snapshot.takeIf { it.expiresAt.isAfter(now) }
                }
            }
        } finally {
            evictionLock.unlock()
        }
    }

    private data class Expiration(val playerId: UUID, val expiresAt: Instant)
}

private fun PermissionSnapshot.cacheCopy(): PermissionSnapshot =
    copy(
        allowPatterns = allowPatterns.toList(),
        denyPatterns = denyPatterns.toList(),
        roleKeys = roleKeys.toSet(),
        roleMetadata = roleMetadata.toList(),
    )
