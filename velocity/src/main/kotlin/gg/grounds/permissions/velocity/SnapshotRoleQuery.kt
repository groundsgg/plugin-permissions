package gg.grounds.permissions.velocity

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.RoleMetadata
import gg.grounds.proxy.api.PlayerRole
import gg.grounds.proxy.api.PlayerRoleQuery
import java.util.UUID

/**
 * Publishes the rank a player should be drawn as, out of the snapshot this plugin already holds.
 *
 * `service-permissions` has always sent `prefix`, `color` and `sortOrder` alongside the permission
 * patterns, and this plugin has always cached them. Nothing read them, so a rank was invisible
 * everywhere a player looks — chat, the tab list, `/online`. This is the one place that changes:
 * everyone else asks the registry instead of growing a permissions client of their own.
 *
 * Reads the in-memory snapshot, so it is safe on a render path that runs per message and per tab
 * refresh. A player with no snapshot — not logged in here, or the fetch failed — is `null`, and
 * callers draw the name plainly rather than waiting on anything.
 */
class SnapshotRoleQuery(private val snapshots: InMemoryPermissionSnapshots) : PlayerRoleQuery {

    override fun highestRoleOf(playerId: UUID): PlayerRole? =
        snapshots.get(playerId)?.roleMetadata?.minWithOrNull(HIGHEST_FIRST)?.toPlayerRole()

    private fun RoleMetadata.toPlayerRole(): PlayerRole =
        PlayerRole(
            key = key,
            name = name,
            prefix = prefix?.takeIf { it.isNotBlank() },
            colour = color?.takeIf { it.isNotBlank() },
            sortOrder = sortOrder,
        )

    private companion object {
        /**
         * Lowest `sortOrder` wins, ties broken on key.
         *
         * A player is normally in several roles at once — everyone carries the default `user` on
         * top of whatever else they have — and only one of them can colour a name. Sorting rather
         * than taking the first is the point: the service returns roles in no particular order, so
         * "first" was whichever one the query happened to emit.
         *
         * The tie-break on key exists so two roles that share a sortOrder still resolve the same
         * way on every proxy. A name that changes colour depending on which proxy you are on reads
         * as a bug, and a stable-but-arbitrary answer is better than an unstable one.
         */
        private val HIGHEST_FIRST: Comparator<RoleMetadata> =
            compareBy<RoleMetadata> { it.sortOrder }.thenBy { it.key }
    }
}
