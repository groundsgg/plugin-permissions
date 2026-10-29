package gg.grounds.permissions.minestom

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration

/** Supplies viewer-specific icons for the native row below a player's name. */
fun interface NameplateStatusProvider {
    fun status(playerId: UUID, viewerId: UUID): Component
}

/** Registered by grounds.permissions; other modules contribute icons without creating entities. */
interface NameplateStatuses {
    /** The returned handle removes this provider when its module stops. */
    fun register(provider: NameplateStatusProvider): AutoCloseable
}

internal class NativeNameplateStatuses : NameplateStatuses {
    private class Registration(val provider: NameplateStatusProvider)

    private val registrations = CopyOnWriteArrayList<Registration>()

    override fun register(provider: NameplateStatusProvider): AutoCloseable {
        val registration = Registration(provider)
        registrations.add(registration)
        return AutoCloseable { registrations.remove(registration) }
    }

    fun status(playerId: UUID, viewerId: UUID): Component =
        Component.join(
            JoinConfiguration.separator(Component.space()),
            registrations.mapNotNull { registration ->
                runCatching { registration.provider.status(playerId, viewerId) }
                    .getOrNull()
                    ?.takeUnless { it == Component.empty() }
            },
        )
}
