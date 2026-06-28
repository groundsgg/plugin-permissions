package gg.grounds.permissions.minestom

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import java.time.Clock
import java.util.UUID
import org.slf4j.Logger

class MinestomPermissionSnapshotLoader(
    private val logger: Logger,
    private val snapshots: InMemoryPermissionSnapshots,
    private val client: PermissionSnapshotClient,
    private val context: PermissionSnapshotContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun loadSnapshot(playerId: UUID, username: String): PermissionLoginResult {
        when (val fetch = client.fetchSnapshot(playerId, context)) {
            is PermissionSnapshotFetchResult.Success -> {
                snapshots.put(fetch.snapshot)
                logger.info(
                    "Fetched permission snapshot successfully (playerId={}, username={}, policyVersion={})",
                    playerId,
                    username,
                    fetch.snapshot.policyVersion,
                )
                return PermissionLoginResult.allowed(fetch.snapshot)
            }
            is PermissionSnapshotFetchResult.Unavailable -> {
                val cached = snapshots.get(playerId)
                if (cached != null && cached.expiresAt.isAfter(clock.instant())) {
                    logger.warn(
                        "Loaded cached permission snapshot after service failure (playerId={}, username={}, reason={})",
                        playerId,
                        username,
                        fetch.reason,
                    )
                    return PermissionLoginResult.allowed(cached)
                }

                logger.warn(
                    "Permission snapshot unavailable without valid cache (playerId={}, username={}, reason={})",
                    playerId,
                    username,
                    fetch.reason,
                )
                return PermissionLoginResult.denied()
            }
        }
    }
}

data class PermissionLoginResult(
    val allowed: Boolean,
    val snapshot: PermissionSnapshot?,
    val message: String,
) {
    companion object {
        fun allowed(snapshot: PermissionSnapshot): PermissionLoginResult =
            PermissionLoginResult(allowed = true, snapshot = snapshot, message = "")

        fun denied(): PermissionLoginResult =
            PermissionLoginResult(
                allowed = false,
                snapshot = null,
                message = "Permissions are currently unavailable. Please try again later.",
            )
    }
}
