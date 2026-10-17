package gg.grounds.permissions.velocity

import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Display-only metadata for tab entries whose connection belongs to another proxy. */
internal class RemotePlayerRoles(
    private val fetchSnapshot: (UUID) -> PermissionSnapshot,
    private val executor: Executor,
    private val clock: Clock = Clock.systemUTC(),
    private val onFailure: (UUID) -> Unit = {},
) {
    private data class Entry(
        val roles: List<RoleMetadata>?,
        val refreshAt: Instant,
        val expiresAt: Instant,
        val loading: Boolean,
    )

    private val entries = LinkedHashMap<UUID, Entry>(16, 0.75f, true)

    fun roleMetadataOf(playerId: UUID): List<RoleMetadata>? =
        synchronized(entries) {
            val now = clock.instant()
            val cached = entries[playerId]
            val roles = cached?.roles?.takeIf { cached.expiresAt.isAfter(now) }
            if (cached?.loading == true || cached?.refreshAt?.isAfter(now) == true) return roles
            val request = Entry(roles, now.plusSeconds(10), cached?.expiresAt ?: now, true)
            entries[playerId] = request
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
            try {
                executor.execute { load(playerId, request) }
            } catch (_: RejectedExecutionException) {
                entries[playerId] = entries.getValue(playerId).copy(loading = false)
            }
            roles
        }

    private fun load(playerId: UUID, request: Entry) {
        val snapshot =
            try {
                fetchSnapshot(playerId).also {
                    require(it.playerId == playerId && it.expiresAt.isAfter(clock.instant()))
                }
            } catch (_: RuntimeException) {
                synchronized(entries) {
                    if (entries[playerId] === request)
                        entries[playerId] = request.copy(loading = false)
                }
                onFailure(playerId)
                return
            }
        synchronized(entries) {
            // Never insert permission grants into the authorization store. Retain only metadata,
            // and do not resurrect an entry evicted while its network request was in flight.
            if (entries[playerId] === request) {
                entries[playerId] =
                    Entry(
                        snapshot.roleMetadata.toList(),
                        minOf(clock.instant().plusSeconds(30), snapshot.expiresAt),
                        snapshot.expiresAt,
                        false,
                    )
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 512
    }
}
