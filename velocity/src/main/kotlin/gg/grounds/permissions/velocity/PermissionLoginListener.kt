package gg.grounds.permissions.velocity

import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.util.UuidUtils
import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import java.time.Clock
import java.util.UUID
import net.kyori.adventure.text.Component
import org.slf4j.Logger

class PermissionLoginListener(
    private val logger: Logger,
    private val snapshots: InMemoryPermissionSnapshots,
    private val cache: SnapshotDiskCache,
    private val client: PermissionSnapshotClient,
    private val context: PermissionSnapshotContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Subscribe
    fun onPreLogin(event: PreLoginEvent): EventTask {
        val username = event.username
        val playerId = event.uniqueId ?: UuidUtils.generateOfflinePlayerUuid(username)

        return EventTask.async {
            val result = loadSnapshot(playerId, username)
            if (!result.allowed) {
                event.result =
                    PreLoginEvent.PreLoginComponentResult.denied(Component.text(result.message))
            }
        }
    }

    internal fun loadSnapshot(playerId: UUID, username: String): PermissionLoginResult {
        when (val fetch = client.fetchSnapshot(playerId, context)) {
            is PermissionSnapshotFetchResult.Success -> {
                activateSnapshot(fetch.snapshot)
                logger.info(
                    "Fetched permission snapshot successfully (playerId={}, username={}, policyVersion={})",
                    playerId,
                    username,
                    fetch.snapshot.policyVersion,
                )
                return PermissionLoginResult.allowed(fetch.snapshot)
            }
            is PermissionSnapshotFetchResult.Unavailable -> {
                val cached = cache.read(playerId, clock.instant())
                if (cached != null) {
                    activateSnapshot(cached)
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

    private fun activateSnapshot(snapshot: PermissionSnapshot) {
        snapshots.put(snapshot)
        cache.write(snapshot)
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
