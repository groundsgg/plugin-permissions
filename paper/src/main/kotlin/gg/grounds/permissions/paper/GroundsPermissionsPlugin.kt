package gg.grounds.permissions.paper

import gg.grounds.permissions.Permissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.permissions.PermissionAttachment
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

internal class BukkitPaperPermissionPlatform(private val plugin: JavaPlugin) :
    PaperPermissionPlatform {
    private var servicePublished = false
    private val attachments = mutableMapOf<UUID, PermissionAttachment>()
    private val onlinePlayerIds =
        ConcurrentHashMap.newKeySet<UUID>().also { ids ->
            ids += plugin.server.onlinePlayers.map { it.uniqueId }
        }

    override fun onlinePlayerIds(): Set<UUID> = onlinePlayerIds.toSet()

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

    override fun registerQuit(handler: (UUID) -> Unit): AutoCloseable =
        registerListener(
            object : Listener {
                @EventHandler
                fun onQuit(event: PlayerQuitEvent) {
                    onlinePlayerIds.remove(event.player.uniqueId)
                    handler(event.player.uniqueId)
                }
            }
        )

    override fun registerPlayerJoin(handler: (UUID) -> Unit): AutoCloseable =
        registerListener(
            object : Listener {
                @EventHandler
                fun onJoin(event: PlayerJoinEvent) {
                    onlinePlayerIds.add(event.player.uniqueId)
                    handler(event.player.uniqueId)
                }
            }
        )

    override fun registerPluginEnable(handler: () -> Unit): AutoCloseable =
        registerListener(
            object : Listener {
                @EventHandler fun onPluginEnable(event: PluginEnableEvent) = handler()
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

    override fun materializePermissions(playerId: UUID, permissions: Permissions) {
        val player = plugin.server.getPlayer(playerId) ?: return
        val attachment = attachments.getOrPut(playerId) { player.addAttachment(plugin) }
        attachment.permissions.keys.toList().forEach(attachment::unsetPermission)
        plugin.server.pluginManager.permissions.forEach { permission ->
            attachment.setPermission(
                permission.name,
                permissions.hasPermission(playerId, permission.name),
            )
        }
    }

    override fun removeMaterializedPermissions(playerId: UUID) {
        val attachment = attachments.remove(playerId) ?: return
        plugin.server.getPlayer(playerId)?.removeAttachment(attachment)
    }

    override fun removeAllMaterializedPermissions() {
        attachments.keys.toList().forEach(::removeMaterializedPermissions)
    }

    override fun materializeOnlinePermissions(permissions: Permissions) {
        plugin.server.onlinePlayers.forEach { materializePermissions(it.uniqueId, permissions) }
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
