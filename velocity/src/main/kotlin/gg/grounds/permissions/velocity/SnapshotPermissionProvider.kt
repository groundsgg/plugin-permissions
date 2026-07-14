package gg.grounds.permissions.velocity

import com.velocitypowered.api.permission.PermissionFunction
import com.velocitypowered.api.permission.PermissionProvider
import com.velocitypowered.api.permission.PermissionSubject
import com.velocitypowered.api.permission.Tristate
import com.velocitypowered.api.proxy.Player
import gg.grounds.permissions.Permissions

/**
 * Bridges the loaded permission snapshot into Velocity's native [PermissionProvider] API, so that
 * [com.velocitypowered.api.command.CommandSource.hasPermission] sees the same decisions as this
 * plugin's own `/permissions` commands.
 *
 * [fallback] is the provider that was installed before this one took over. Non-player subjects
 * (e.g. the console) always fall through to it unchanged, and any permission this provider does not
 * ALLOW also falls through to it rather than being modeled as a hard DENY.
 */
class SnapshotPermissionProvider(
    private val permissions: Permissions,
    private val fallback: PermissionProvider,
) : PermissionProvider {
    override fun createFunction(subject: PermissionSubject): PermissionFunction {
        val fallbackFunction = fallback.createFunction(subject)
        val player = subject as? Player ?: return fallbackFunction
        return PermissionFunction { permission ->
            if (permissions.hasPermission(player.uniqueId, permission)) Tristate.TRUE
            else fallbackFunction.getPermissionValue(permission)
        }
    }
}
