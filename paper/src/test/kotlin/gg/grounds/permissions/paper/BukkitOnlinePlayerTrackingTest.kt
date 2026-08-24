package gg.grounds.permissions.paper

import java.net.InetAddress
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
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
            PlayerLoginEvent(
                player,
                "localhost",
                InetAddress.getLoopbackAddress(),
                InetAddress.getLoopbackAddress(),
            )
        )
        val snapshot = platform.onlinePlayerIds()
        assertTrue(player.uniqueId in snapshot)
        server.pluginManager.callEvent(PlayerQuitEvent(player, Component.empty()))
        assertFalse(player.uniqueId in platform.onlinePlayerIds())
        assertTrue(player.uniqueId in snapshot)
    }

    @Test
    fun `final login phase rolls back a later denial`() {
        val server = MockBukkit.mock()
        val plugin = MockBukkit.load(GroundsPermissionsPlugin::class.java)
        val platform = BukkitPaperPermissionPlatform(plugin)
        val player = server.addPlayer()
        var rolledBack: PaperPermissionPlayer? = null
        platform.registerPlayerLogin { PermissionLoginResult(true, "") }
        platform.registerPlayerLoginRollback { rolledBack = it }
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler(priority = EventPriority.NORMAL)
                fun deny(event: PlayerLoginEvent) {
                    event.disallow(
                        PlayerLoginEvent.Result.KICK_OTHER,
                        Component.text("later denial"),
                    )
                }
            },
            plugin,
        )

        server.pluginManager.callEvent(
            PlayerLoginEvent(
                player,
                "localhost",
                InetAddress.getLoopbackAddress(),
                InetAddress.getLoopbackAddress(),
            )
        )

        assertFalse(player.uniqueId in platform.onlinePlayerIds())
        assertTrue(rolledBack is BukkitPaperPermissionPlayer)
    }

    @Test
    fun `login injection respects an earlier disallow`() {
        val server = MockBukkit.mock()
        val plugin = MockBukkit.load(GroundsPermissionsPlugin::class.java)
        val platform = BukkitPaperPermissionPlatform(plugin)
        val player = server.addPlayer()
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler(priority = EventPriority.LOWEST)
                fun deny(event: PlayerLoginEvent) {
                    event.disallow(
                        PlayerLoginEvent.Result.KICK_OTHER,
                        Component.text("earlier denial"),
                    )
                }
            },
            plugin,
        )
        var injected = false
        platform.registerPlayerLogin {
            injected = true
            PermissionLoginResult(true, "")
        }

        server.pluginManager.callEvent(
            PlayerLoginEvent(
                player,
                "localhost",
                InetAddress.getLoopbackAddress(),
                InetAddress.getLoopbackAddress(),
            )
        )

        assertFalse(injected)
        assertFalse(player.uniqueId in platform.onlinePlayerIds())
    }
}
