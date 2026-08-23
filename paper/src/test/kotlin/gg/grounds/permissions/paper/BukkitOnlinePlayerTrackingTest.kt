package gg.grounds.permissions.paper

import net.kyori.adventure.text.Component
import java.net.InetAddress
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class BukkitOnlinePlayerTrackingTest {
    @AfterEach fun tearDown() = MockBukkit.unmock()

    @Test
    fun `tracks successful logins and quits without mutating earlier snapshots`() {
        val server = MockBukkit.mock()
        val plugin = MockBukkit.load(GroundsPermissionsPlugin::class.java)
        val existingPlayer = server.addPlayer()
        val platform = BukkitPaperPermissionPlatform(plugin)
        assertTrue(existingPlayer.uniqueId in platform.onlinePlayerIds())
        platform.registerPlayerLogin { PermissionLoginResult(true, "") }
        platform.registerQuit {}
        val player = server.addPlayer()
        server.pluginManager.callEvent(
            PlayerLoginEvent(player, "localhost", InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress()),
        )
        val snapshot = platform.onlinePlayerIds()
        assertTrue(player.uniqueId in snapshot)
        server.pluginManager.callEvent(PlayerQuitEvent(player, Component.empty()))
        assertFalse(player.uniqueId in platform.onlinePlayerIds())
        assertTrue(player.uniqueId in snapshot)
    }
}
