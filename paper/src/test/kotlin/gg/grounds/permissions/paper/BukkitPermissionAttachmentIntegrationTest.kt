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
import org.bukkit.permissions.PermissibleBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class BukkitPermissionAttachmentIntegrationTest {
    @AfterEach fun tearDown() = MockBukkit.unmock()

    @Test
    fun `platform injection makes an unregistered dynamic node available through the permissible seam`() {
        val server = MockBukkit.mock()
        val plugin = MockBukkit.load(GroundsPermissionsPlugin::class.java)
        val player = server.addPlayer()
        val holder = TestPlayer(PermissibleBase(player))
        val platform = BukkitPaperPermissionPlatform(plugin, PaperPermissibleInjector { holder })
        val now = Instant.parse("2026-08-23T12:00:00Z")
        val permissions =
            SnapshotPermissions(
                mapOf(
                    player.uniqueId to
                        PermissionSnapshot(
                            player.uniqueId,
                            1,
                            now,
                            now,
                            now.plusSeconds(60),
                            listOf(grant("*")),
                            emptyList(),
                            emptySet(),
                            emptyList(),
                        )
                ),
                PermissionCheckScope(),
                Clock.fixed(now, ZoneOffset.UTC),
            )

        platform.injectPermissions(BukkitPaperPermissionPlayer(player), permissions)

        val injected = PermissibleFieldAccess.locate(holder.javaClass).read(holder)
        assertTrue(injected.hasPermission("unregistered.dynamic.node"))
    }

    private fun grant(pattern: String) =
        PermissionGrant(
            PermissionEffect.ALLOW,
            pattern,
            PermissionScope.global(),
            PermissionGrantSource.ROLE,
        )

    private open class TestHuman(protected val perm: PermissibleBase)

    private class TestPlayer(perm: PermissibleBase) : TestHuman(perm)
}
