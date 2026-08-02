package gg.grounds.permissions.velocity

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SnapshotRoleQueryTest {

    private val player: UUID = UUID.fromString("9a122510-849a-44e8-b022-743093a8b1f0")

    private fun snapshotOf(vararg roles: RoleMetadata): InMemoryPermissionSnapshots {
        val now = Instant.parse("2026-08-02T10:00:00Z")
        return InMemoryPermissionSnapshots(
            mapOf(
                player to
                    PermissionSnapshot(
                        playerId = player,
                        policyVersion = 69,
                        issuedAt = now,
                        refreshAfter = now.plusSeconds(300),
                        expiresAt = now.plusSeconds(600),
                        allowPatterns = emptyList(),
                        denyPatterns = emptyList(),
                        roleKeys = roles.map { it.key }.toSet(),
                        roleMetadata = roles.toList(),
                    )
            )
        )
    }

    @Test
    fun `a player with no snapshot has no role`() {
        val query = SnapshotRoleQuery(InMemoryPermissionSnapshots())
        assertNull(query.highestRoleOf(player))
    }

    @Test
    fun `a player with no roles has no role`() {
        assertNull(SnapshotRoleQuery(snapshotOf()).highestRoleOf(player))
    }

    /**
     * Everyone carries the default `user` on top of whatever else they hold, so picking the first
     * entry would colour an admin as a normal player whenever the service happened to list `user`
     * first.
     */
    @Test
    fun `the lowest sortOrder wins, not the first entry`() {
        val query =
            SnapshotRoleQuery(
                snapshotOf(
                    RoleMetadata("user", "User", prefix = "[User]", sortOrder = 20),
                    RoleMetadata(
                        "administrator",
                        "Administrator",
                        prefix = "[Admin]",
                        color = "#f9a49a",
                        sortOrder = 0,
                    ),
                )
            )

        val role = query.highestRoleOf(player)!!
        assertEquals("administrator", role.key)
        assertEquals("[Admin]", role.prefix)
        assertEquals("#f9a49a", role.colour)
        assertEquals(0, role.sortOrder)
    }

    @Test
    fun `a tie resolves the same way everywhere, so a name does not change colour per proxy`() {
        val query =
            SnapshotRoleQuery(
                snapshotOf(
                    RoleMetadata("moderator", "Moderator", sortOrder = 5),
                    RoleMetadata("builder", "Builder", sortOrder = 5),
                )
            )
        assertEquals("builder", query.highestRoleOf(player)!!.key)
    }

    @Test
    fun `a role without a colour is reported without one, not with an empty string`() {
        val query =
            SnapshotRoleQuery(
                snapshotOf(RoleMetadata("user", "User", prefix = "[User]", sortOrder = 20))
            )
        val role = query.highestRoleOf(player)!!
        assertNull(role.colour)
        assertEquals("[User]", role.prefix)
    }

    /** The service stores these as text; blank is what an unset field looks like after a form. */
    @Test
    fun `blank prefix and colour are treated as absent`() {
        val query =
            SnapshotRoleQuery(
                snapshotOf(RoleMetadata("user", "User", prefix = "  ", color = "", sortOrder = 20))
            )
        val role = query.highestRoleOf(player)!!
        assertNull(role.prefix)
        assertNull(role.colour)
    }
}
