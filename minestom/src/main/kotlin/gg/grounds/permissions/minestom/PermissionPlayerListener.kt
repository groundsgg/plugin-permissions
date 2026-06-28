package gg.grounds.permissions.minestom

import net.kyori.adventure.text.Component
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent

class PermissionPlayerListener(private val loader: MinestomPermissionSnapshotLoader) {
    fun register(eventNode: EventNode<Event>) {
        eventNode.addListener(AsyncPlayerPreLoginEvent::class.java) { event ->
            val result =
                loader.loadSnapshot(
                    playerId = event.gameProfile.uuid(),
                    username = event.gameProfile.name(),
                )
            if (!result.allowed) {
                event.connection.kick(Component.text(result.message))
            }
        }
    }
}
