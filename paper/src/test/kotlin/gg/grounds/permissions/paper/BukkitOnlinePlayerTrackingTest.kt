package gg.grounds.permissions.paper

import net.kyori.adventure.text.Component
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class BukkitOnlinePlayerTrackingTest {
    @AfterEach fun tearDown() = MockBukkit.unmock()

    @Test
    fun `tracks joins and quits without mutating earlier snapshots`() {
        val server = MockBukkit.mock()
        val plugin = MockBukkit.load(GroundsPermissionsPlugin::class.java)
        val existingPlayer = server.addPlayer()
        val platform = BukkitPaperPermissionPlatform(plugin)
        assertTrue(existingPlayer.uniqueId in platform.onlinePlayerIds())
        platform.registerPlayerJoin {}
        platform.registerQuit {}
        val player = server.addPlayer()
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        val snapshot = platform.onlinePlayerIds()
        assertTrue(player.uniqueId in snapshot)
        server.pluginManager.callEvent(PlayerQuitEvent(player, Component.empty()))
        assertFalse(player.uniqueId in platform.onlinePlayerIds())
        assertTrue(player.uniqueId in snapshot)
    }
}
