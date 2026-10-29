package gg.grounds.permissions.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.i18n.Translations
import gg.grounds.presentation.VelocitySystemMessages
import java.util.UUID

class VelocityPermissionsCommand(
    private val plugin: Any,
    private val proxy: ProxyServer,
    private val router: PermissionCommandRouter,
    private val commandPermissions: PermissionCommandPermissions,
    private val isAuthorized: (CommandSource, String) -> Boolean,
    private val messages: Translations =
        Translations.forBundleWithSystemRenderer(
            "gg.grounds.permissions.messages",
            VelocityPermissionsCommand::class.java.classLoader,
            systemRenderer = VelocitySystemMessages.renderer,
        ),
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
            is PermissionCommandResult.Success -> {
                val localized = result.localizedLines
                if (localized != null) {
                    localized.forEach { sendLocalized(source, it) }
                } else {
                    result.lines.forEach { line ->
                        messages.send(source, PermissionsMessage.COMMAND_OUTPUT, "message" to line)
                    }
                }
            }
            is PermissionCommandResult.Failure -> {
                val localized = result.localized
                if (localized != null) {
                    sendLocalized(source, localized)
                } else {
                    messages.send(
                        source,
                        PermissionsMessage.COMMAND_FAILURE,
                        "message" to requireNotNull(result.message),
                    )
                }
            }
        }
    }

    private fun sendLocalized(source: CommandSource, message: LocalizedPermissionMessage) {
        if (message.args.isEmpty() && PermissionCommandInteraction.supports(message.key)) {
            messages.sendRendered(
                source,
                message.key,
                PermissionCommandInteraction.render(messages, message.key, source),
            )
        } else {
            messages.send(
                source,
                message.key,
                *message.args.map { it.key to it.value }.toTypedArray(),
            )
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
