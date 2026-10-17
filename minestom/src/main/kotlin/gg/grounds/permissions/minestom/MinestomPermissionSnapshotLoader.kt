package gg.grounds.permissions.minestom

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.client.SnapshotUnavailableException
import java.util.UUID
import org.slf4j.Logger

class MinestomPermissionSnapshotLoader
internal constructor(
    private val logger: Logger,
    private val snapshots: InMemoryPermissionSnapshots,
    private val fetchForLogin: (UUID) -> LoginSnapshot,
) {
    constructor(
        logger: Logger,
        snapshots: InMemoryPermissionSnapshots,
        client: PermissionRuntimeClient,
        context: PermissionSnapshotContext,
    ) : this(
        logger,
        snapshots,
        { playerId -> LoginSnapshot(client.fetchSnapshot(playerId, context), PrefetchOutcome.NONE) },
    )

    fun loadSnapshot(playerId: UUID): PermissionLoginResult {
        return try {
            val (snapshot, prefetch) = fetchForLogin(playerId)
            snapshots.put(snapshot)
            logger.info(
                "Permission snapshot fetched successfully (playerId={}, policyVersion={}, prefetch={})",
                playerId,
                snapshot.policyVersion,
                prefetch.name.lowercase(),
            )
            PermissionLoginResult.allowed(snapshot)
        } catch (exception: SnapshotUnavailableException) {
            logger.warn(
                "Permission snapshot unavailable without valid cache (playerId={}, reason={}, requestId={})",
                playerId,
                exception.reason.name.lowercase(),
                exception.requestId ?: "none",
            )
            PermissionLoginResult.denied()
        } catch (exception: RuntimeException) {
            logger.error(
                "Permission snapshot load failed (playerId={}, exceptionType={})",
                playerId,
                exception::class.java.name,
            )
            PermissionLoginResult.denied()
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
