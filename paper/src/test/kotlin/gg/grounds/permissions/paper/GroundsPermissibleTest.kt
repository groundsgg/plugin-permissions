package gg.grounds.permissions.paper

import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.SnapshotPermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.permissions.PermissionRemovedExecutor
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class GroundsPermissibleTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock
    private lateinit var plugin: JavaPlugin
    private lateinit var player: org.bukkit.entity.Player

    @AfterEach fun tearDown() = MockBukkit.unmock()

    @Test
    fun `unregistered nodes are evaluated directly against the snapshot`() {
        val permissible = permissible(allow = listOf("*"), deny = listOf("buildsystem.delete"))

        assertTrue(permissible.hasPermission("buildsystem.import"))
        assertFalse(permissible.hasPermission("buildsystem.delete"))
        assertTrue(permissible.isPermissionSet("buildsystem.import"))
        assertFalse(server.pluginManager.permissions.any { it.name == "buildsystem.import" })
    }

    @Test
    fun `attachment overlay takes priority and deny wins an equal-specificity tie`() {
        val permissible = permissible(allow = listOf("*"))
        val allow = permissible.addAttachment(plugin, "buildsystem.import", true)
        val deny = permissible.addAttachment(plugin, "buildsystem.import", false)

        assertFalse(permissible.hasPermission("buildsystem.import"))
        permissible.removeAttachment(deny)
        assertTrue(permissible.hasPermission("buildsystem.import"))
        permissible.removeAttachment(allow)
        assertTrue(permissible.hasPermission("buildsystem.import"))
    }

    @Test
    fun `attachment prefixes are matched as overlays`() {
        val permissible = permissible(allow = listOf("*"))
        permissible.addAttachment(plugin, "buildsystem.*", false)

        assertFalse(permissible.hasPermission("buildsystem.import"))
        assertTrue(permissible.hasPermission("other.import"))
    }

    @Test
    fun `attachment removal invokes its callback once`() {
        val permissible = permissible()
        val attachment = permissible.addAttachment(plugin, "buildsystem.import", true)
        val removed = AtomicBoolean()
        attachment.setRemovalCallback(PermissionRemovedExecutor { removed.set(true) })

        permissible.removeAttachment(attachment)
        permissible.removeAttachment(attachment)

        assertTrue(removed.get())
        assertTrue(permissible.currentAttachments().isEmpty())
    }

    @Test
    fun `unsetting an attachment permission reveals the snapshot decision`() {
        val permissible = permissible(allow = listOf("*"))
        val attachment = permissible.addAttachment(plugin, "buildsystem.import", false)

        assertFalse(permissible.hasPermission("buildsystem.import"))
        attachment.unsetPermission("buildsystem.import")

        assertTrue(permissible.hasPermission("buildsystem.import"))
    }

    @Test
    fun `timed attachment is synchronously removed by the scheduler`() {
        val permissible = permissible(allow = listOf("*"))
        val attachment = permissible.addAttachment(plugin, "buildsystem.import", false, 1)

        assertTrue(attachment != null)
        assertFalse(permissible.hasPermission("buildsystem.import"))
        server.scheduler.performTicks(1)

        assertTrue(permissible.currentAttachments().isEmpty())
        assertTrue(permissible.hasPermission("buildsystem.import"))
    }

    @Test
    fun `clear permissions removes every attachment`() {
        val permissible = permissible(allow = listOf("*"))
        permissible.addAttachment(plugin, "buildsystem.import", false)
        permissible.addAttachment(plugin, "buildsystem.delete", false)

        permissible.clearPermissions()

        assertTrue(permissible.currentAttachments().isEmpty())
        assertTrue(permissible.hasPermission("buildsystem.import"))
        assertTrue(permissible.hasPermission("buildsystem.delete"))
    }

    @Test
    fun `effective permissions remain finite and expose snapshot and attachment entries`() {
        val permissible = permissible(allow = listOf("*"))
        permissible.addAttachment(plugin, "buildsystem.import", false)

        val effective = permissible.effectivePermissions.associate { it.permission to it.value }

        assertEquals(true, effective["*"])
        assertEquals(false, effective["buildsystem.import"])
        assertEquals(2, effective.size)
    }

    @Test
    fun `non timed attachments reject disabled plugins`() {
        val permissible = permissible()
        server.pluginManager.disablePlugin(plugin)

        assertThrows(IllegalArgumentException::class.java) {
            permissible.addAttachment(plugin, "buildsystem.import", true)
        }
    }

    private fun permissible(
        allow: List<String> = emptyList(),
        deny: List<String> = emptyList(),
    ): GroundsPermissible {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(GroundsPermissionsPlugin::class.java)
        player = server.addPlayer()
        val snapshot =
            PermissionSnapshot(
                player.uniqueId,
                1,
                now,
                now,
                now.plusSeconds(60),
                allow.map { grant(PermissionEffect.ALLOW, it) },
                deny.map { grant(PermissionEffect.DENY, it) },
                emptySet(),
                emptyList(),
            )
        return GroundsPermissible(
            player,
            plugin,
            SnapshotPermissions(
                mapOf(player.uniqueId to snapshot),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            ),
        )
    }

    private fun grant(effect: PermissionEffect, pattern: String) =
        PermissionGrant(effect, pattern, PermissionScope.global(), PermissionGrantSource.ROLE)
}
