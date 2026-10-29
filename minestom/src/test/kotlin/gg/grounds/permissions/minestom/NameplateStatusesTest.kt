package gg.grounds.permissions.minestom

import java.util.UUID
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NameplateStatusesTest {
    @Test
    fun `providers receive subject and viewer and release their icons on close`() {
        val statuses = NativeNameplateStatuses()
        val subject = UUID.randomUUID()
        val viewer = UUID.randomUUID()
        val icon = Component.text('\uE005')
        val registration =
            statuses.register { playerId, viewerId ->
                if (playerId == subject && viewerId == viewer) icon else Component.empty()
            }
        assertEquals(icon, statuses.status(subject, viewer))
        assertEquals(Component.empty(), statuses.status(viewer, subject))
        registration.close()
        registration.close()
        assertEquals(Component.empty(), statuses.status(subject, viewer))
    }

    @Test
    fun `a failed status provider cannot interrupt other modules or the nameplate refresh`() {
        val statuses = NativeNameplateStatuses()
        statuses.register { _, _ -> error("unavailable") }
        statuses.register { _, _ -> Component.text('\uE005') }
        assertEquals(
            Component.text('\uE005'),
            statuses.status(UUID.randomUUID(), UUID.randomUUID()),
        )
    }
}
