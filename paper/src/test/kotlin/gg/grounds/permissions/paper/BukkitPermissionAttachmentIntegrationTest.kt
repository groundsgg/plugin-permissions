package gg.grounds.permissions.paper

import gg.grounds.permissions.PermissionCheckScope
import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.SnapshotPermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.bukkit.permissions.Permission
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class BukkitPermissionAttachmentIntegrationTest {
    @AfterEach fun tearDown() = MockBukkit.unmock()

    @Test
    fun `registered Bukkit nodes use snapshot wildcard allow and exact deny through attachments`() {
        val server = MockBukkit.mock()
        val plugin = MockBukkit.load(GroundsPermissionsPlugin::class.java)
        val player = server.addPlayer()
        server.pluginManager.addPermission(Permission("ground.test.read"))
        server.pluginManager.addPermission(Permission("ground.test.delete"))
        server.pluginManager.addPermission(Permission("ground.test.scope"))
        val now = Instant.parse("2026-08-23T12:00:00Z")
        val snapshot =
            PermissionSnapshot(
                player.uniqueId,
                1,
                now,
                now,
                now.plusSeconds(60),
                listOf(
                    grant(PermissionEffect.ALLOW, "ground.test.*"),
                    grant(
                        PermissionEffect.ALLOW,
                        "ground.test.scope",
                        PermissionScope.environment("stage"),
                    ),
                ),
                listOf(
                    grant(PermissionEffect.DENY, "ground.test.delete"),
                    grant(PermissionEffect.DENY, "ground.test.scope"),
                ),
                emptySet(),
                emptyList(),
            )
        val permissions =
            SnapshotPermissions(
                mapOf(player.uniqueId to snapshot),
                PermissionCheckScope(
                    environment = "stage",
                    serverType = "buildserver",
                    server = "buildserver",
                ),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )

        val platform = BukkitPaperPermissionPlatform(plugin)
        platform.materializePermissions(player.uniqueId, permissions)

        assertTrue(player.hasPermission("ground.test.read"))
        assertFalse(player.hasPermission("ground.test.delete"))
        assertTrue(player.hasPermission("ground.test.scope"))
        platform.removeAllMaterializedPermissions()
        assertFalse(player.hasPermission("ground.test.read"))
    }

    private fun grant(
        effect: PermissionEffect,
        pattern: String,
        scope: PermissionScope = PermissionScope.global(),
    ) = PermissionGrant(effect, pattern, scope, PermissionGrantSource.ROLE)
}
