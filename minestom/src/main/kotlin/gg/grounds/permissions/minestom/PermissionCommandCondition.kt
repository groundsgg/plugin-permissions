package gg.grounds.permissions.minestom

import gg.grounds.permissions.Permissions
import net.minestom.server.command.builder.condition.CommandCondition
import net.minestom.server.entity.Player

/**
 * Minestom has no permission API; this is the one entry point gamemodes need to gate a command on a
 * permission node, e.g. `myCommand.condition =
 * permissions.commandCondition("grounds.lobby.command.foo")`.
 *
 * Non-player senders (e.g. the console) are always allowed.
 */
fun Permissions.commandCondition(permission: String): CommandCondition =
    CommandCondition { sender, _ ->
        when (sender) {
            is Player -> hasPermission(sender.uuid, permission)
            else -> true
        }
    }
