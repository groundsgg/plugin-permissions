package gg.grounds.permissions

import java.time.Clock
import java.util.UUID

/**
 * Re-fetches snapshots for online players once the server-supplied `refreshAfter` has passed, and
 * drops snapshots that can no longer be used.
 *
 * Without this, a snapshot is only ever written at login: it expires ten minutes later and every
 * permission check for that player then fails closed, a grant never reaches an online player, and
 * the map grows for the lifetime of the pod.
 */
class PermissionSnapshotRefreshSweep(
    private val snapshots: InMemoryPermissionSnapshots,
    private val onlinePlayerIds: () -> Set<UUID>,
    private val fetchSnapshot: (UUID) -> PermissionSnapshot?,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun run() {
        val now = clock.instant()
        val online = onlinePlayerIds()
        val current = snapshots.all()
        val refreshed = mutableMapOf<UUID, PermissionSnapshot>()

        for (id in online) {
            val existing = current[id]
            if (existing != null && existing.refreshAfter.isAfter(now)) {
                continue
            }
            // A failed fetch keeps whatever we already had: it stays valid until it expires, and
            // the next sweep retries. Only the login path may deny a player.
            fetchSnapshot(id)?.let { refreshed[id] = it }
        }

        // Offline and expired is the only combination that is provably dead: the outage fallback
        // reads an offline player's snapshot as long as it has not expired. Merging rather than
        // replacing keeps snapshots written by a login that raced this sweep.
        snapshots.merge(refreshed) { id, snapshot ->
            id !in online && !snapshot.expiresAt.isAfter(now)
        }
    }
}
