package gg.grounds.permissions.paper

import gg.grounds.permissions.Permissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

open class GroundsPermissionsPlugin : JavaPlugin() {
    private lateinit var runtime: PaperPermissionsRuntime

    override fun onEnable() {
        runtime = PaperPermissionsRuntime(BukkitPaperPermissionPlatform(this))
        runtime.start()
    }

    override fun onDisable() {
        if (::runtime.isInitialized) runtime.stop()
    }
}

internal class BukkitPaperPermissionPlatform(
    private val plugin: JavaPlugin,
    private val injector: PaperPermissibleInjector = PaperPermissibleInjector(),
) :
    PaperPermissionPlatform {
    private var servicePublished = false
    private val onlinePlayerIds =
        ConcurrentHashMap.newKeySet<UUID>().also { ids ->
            ids += plugin.server.onlinePlayers.map { it.uniqueId }
        }

    override fun onlinePlayerIds(): Set<UUID> = onlinePlayerIds.toSet()

    override fun validateInjection() {
        val craftHumanEntity =
            Class.forName(
                "org.bukkit.craftbukkit.entity.CraftHumanEntity",
                false,
                plugin.server.javaClass.classLoader,
            )
        injector.validate(craftHumanEntity)
    }

    override fun registerPreLogin(handler: (UUID) -> PermissionLoginResult): AutoCloseable =
        registerListener(
            object : Listener {
                @EventHandler
                fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
                    val result = handler(event.uniqueId)
                    if (!result.allowed) {
                        event.disallow(
                            AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            Component.text(result.message),
                        )
                    }
                }
            }
        )

    override fun registerQuit(handler: (PaperPermissionPlayer) -> Unit): AutoCloseable =
        registerListener(
            object : Listener {
                @EventHandler(priority = EventPriority.MONITOR)
                fun onQuit(event: PlayerQuitEvent) {
                    onlinePlayerIds.remove(event.player.uniqueId)
                    val player = BukkitPaperPermissionPlayer(event.player)
                    plugin.server.scheduler.runTaskLater(plugin, Runnable { handler(player) }, 1L)
                }
            }
        )

    override fun registerPlayerLogin(handler: (PaperPermissionPlayer) -> PermissionLoginResult): AutoCloseable =
        registerListener(
            object : Listener {
                @EventHandler(priority = EventPriority.LOWEST)
                fun onLogin(event: PlayerLoginEvent) {
                    val player = BukkitPaperPermissionPlayer(event.player)
                    val result = handler(player)
                    if (!result.allowed) {
                        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, Component.text(result.message))
                    } else {
                        onlinePlayerIds.add(player.playerId)
                    }
                }
            }
        )

    override fun scheduleRefresh(intervalSeconds: Long, task: () -> Unit): AutoCloseable {
        val scheduled =
            plugin.server.scheduler.runTaskTimerAsynchronously(
                plugin,
                Runnable(task),
                intervalSeconds * TICKS_PER_SECOND,
                intervalSeconds * TICKS_PER_SECOND,
            )
        return AutoCloseable(scheduled::cancel)
    }

    override fun publish(permissions: Permissions) {
        plugin.server.servicesManager.register(
            Permissions::class.java,
            permissions,
            plugin,
            ServicePriority.Normal,
        )
        servicePublished = true
    }

    override fun unpublish() {
        if (servicePublished) plugin.server.servicesManager.unregisterAll(plugin)
        servicePublished = false
    }

    override fun injectPermissions(player: PaperPermissionPlayer, permissions: Permissions) {
        val bukkitPlayer = player.requireBukkitPlayer()
        injector.inject(bukkitPlayer, GroundsPermissible(bukkitPlayer, plugin, permissions))
    }

    override fun refreshPermissions(playerId: UUID) {
        plugin.server.getPlayer(playerId)?.updateCommands()
    }

    override fun retirePermissions(player: PaperPermissionPlayer) {
        injector.retire(player.requireBukkitPlayer())
    }

    override fun restoreAllPermissions() {
        plugin.server.onlinePlayers.forEach(injector::restore)
    }

    override fun runOnServerThread(task: () -> Unit) {
        if (org.bukkit.Bukkit.isPrimaryThread()) task()
        else plugin.server.scheduler.runTask(plugin, Runnable(task))
    }

    private fun registerListener(listener: Listener): AutoCloseable {
        plugin.server.pluginManager.registerEvents(listener, plugin)
        return AutoCloseable { HandlerList.unregisterAll(listener) }
    }

    private companion object {
        const val TICKS_PER_SECOND = 20L
    }
}

internal data class BukkitPaperPermissionPlayer(val player: Player) : PaperPermissionPlayer {
    override val playerId: UUID get() = player.uniqueId
}

private fun PaperPermissionPlayer.requireBukkitPlayer(): Player =
    (this as? BukkitPaperPermissionPlayer)?.player
        ?: throw IllegalArgumentException("Expected BukkitPaperPermissionPlayer, got ${javaClass.name}")
