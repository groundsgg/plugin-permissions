package gg.grounds.permissions.invalidation

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import org.slf4j.Logger

class PermissionSnapshotInvalidationCoordinator(
    private val snapshots: InMemoryPermissionSnapshots,
    private val isOnline: (UUID) -> Boolean,
    private val fetchSnapshot: (UUID) -> PermissionSnapshot?,
    private val executor: Executor,
    private val logger: Logger,
    private val onSnapshotRefreshed: (UUID) -> Unit = {},
) {
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    fun invalidate(playerId: UUID) {
        if (!isOnline(playerId) || !inFlight.add(playerId)) return

        try {
            executor.execute { refresh(playerId) }
        } catch (_: RuntimeException) {
            inFlight.remove(playerId)
            logger.warn(
                "Failed to refresh invalidated permission snapshot " +
                    "(playerId={}, reason=refresh_scheduling_failed)",
                playerId,
            )
        }
    }

    private fun refresh(playerId: UUID) {
        try {
            val snapshot = fetchSnapshot(playerId)
            if (snapshot == null) {
                logger.warn(
                    "Failed to refresh invalidated permission snapshot " +
                        "(playerId={}, reason=snapshot_unavailable)",
                    playerId,
                )
                return
            }
            snapshots.put(snapshot)
            onSnapshotRefreshed(playerId)
            logger.debug(
                "Refreshed invalidated permission snapshot successfully (playerId={})",
                playerId,
            )
        } catch (_: RuntimeException) {
            logger.warn(
                "Failed to refresh invalidated permission snapshot " +
                    "(playerId={}, reason=snapshot_fetch_failed)",
                playerId,
            )
        } finally {
            inFlight.remove(playerId)
        }
    }
}
