package gg.grounds.permissions.minestom

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.client.SnapshotUnavailableException
import java.util.UUID
import org.slf4j.Logger

class MinestomPermissionSnapshotLoader(
    private val logger: Logger,
    private val snapshots: InMemoryPermissionSnapshots,
    private val client: PermissionRuntimeClient,
    private val context: PermissionSnapshotContext,
) {
    fun loadSnapshot(playerId: UUID): PermissionLoginResult {
        return try {
            val snapshot = client.fetchSnapshot(playerId, context)
            snapshots.put(snapshot)
            logger.info(
                "Permission snapshot fetched successfully (playerId={}, policyVersion={})",
                playerId,
                snapshot.policyVersion,
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
