package gg.grounds.permissions.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import java.util.UUID
import net.kyori.adventure.text.Component

class VelocityPermissionsCommand(
    private val plugin: Any,
    private val proxy: ProxyServer,
    private val router: PermissionCommandRouter,
    private val commandPermissions: PermissionCommandPermissions,
    private val isAuthorized: (CommandSource, String) -> Boolean,
) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        proxy.scheduler
            .buildTask(
                plugin,
                Runnable { sendResult(source, router.execute(invocation.arguments())) },
            )
            .schedule()
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> =
        router.suggest(invocation.arguments())

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        isAuthorized(invocation.source(), commandPermissions.forArguments(invocation.arguments()))

    private fun sendResult(source: CommandSource, result: PermissionCommandResult) {
        when (result) {
            is PermissionCommandResult.Success ->
                result.lines.forEach { source.sendMessage(Component.text(it)) }
            is PermissionCommandResult.Failure -> source.sendMessage(Component.text(result.message))
        }
    }

    companion object {
        fun findOnlinePlayer(proxy: ProxyServer, identifier: String): OnlinePermissionPlayer? {
            val player =
                runCatching { UUID.fromString(identifier) }
                    .getOrNull()
                    ?.let { proxy.getPlayer(it).orElse(null) }
                    ?: proxy.getPlayer(identifier).orElse(null)
                    ?: return null
            return OnlinePermissionPlayer(player.uniqueId, player.username)
        }

        fun onlinePlayers(proxy: ProxyServer): Collection<OnlinePermissionPlayer> =
            proxy.allPlayers.map { player ->
                OnlinePermissionPlayer(player.uniqueId, player.username)
            }

        fun isPlayerAuthorized(
            source: CommandSource,
            permissions: gg.grounds.permissions.Permissions,
            permission: String,
        ): Boolean =
            when (source) {
                is Player -> permissions.hasPermission(source.uniqueId, permission)
                else -> true
            }
    }
}
