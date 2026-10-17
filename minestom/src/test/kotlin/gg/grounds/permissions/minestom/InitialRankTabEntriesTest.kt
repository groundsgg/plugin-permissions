package gg.grounds.permissions.minestom

import gg.grounds.permissions.RoleMetadata
import java.util.EnumSet
import java.util.UUID
import net.kyori.adventure.text.TextComponent
import net.minestom.server.entity.GameMode
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InitialRankTabEntriesTest {
    private val id = UUID.randomUUID()
    private val role = RoleMetadata("administrator", "Administrator", color = "#C9826B")

    private fun packet() =
        PlayerInfoUpdatePacket(
            EnumSet.of(PlayerInfoUpdatePacket.Action.ADD_PLAYER),
            listOf(
                PlayerInfoUpdatePacket.Entry(
                    id,
                    "Steve",
                    emptyList(),
                    false,
                    42,
                    GameMode.SPECTATOR,
                    null,
                    null,
                    12,
                    false,
                )
            ),
        )

    private fun plain(c: net.kyori.adventure.text.Component): String =
        (if (c is TextComponent) c.content() else "") + c.children().joinToString("") { plain(it) }

    @Test
    fun `first java entry already has badge and a second pass does not resend recursively`() {
        val original = packet()
        val first = InitialRankTabEntries.format(original, { role }, { false }, false)
        val entry = first.entries().single()
        assertTrue(PlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME in first.actions())
        assertEquals(EnumSet.of(PlayerInfoUpdatePacket.Action.ADD_PLAYER), original.actions())
        assertTrue(plain(entry.displayName()!!).contains("ADMIN"))
        assertFalse(plain(entry.displayName()!!).contains("Administrator"))
        assertFalse(entry.listed())
        assertEquals(42, entry.latency())
        assertEquals(GameMode.SPECTATOR, entry.gameMode())
        assertEquals(12, entry.listOrder())
        assertFalse(entry.displayHat())
        assertSame(first, InitialRankTabEntries.format(first, { role }, { false }, false))
    }

    @Test
    fun `first bedrock entry uses native badge and unknown roles preserve the original packet`() {
        val original = packet()
        val first = InitialRankTabEntries.format(original, { role }, { true }, true)
        assertEquals("\uE600 \uE500 Steve", plain(first.entries().single().displayName()!!))
        assertSame(original, InitialRankTabEntries.format(original, { null }, { false }, false))
    }
}
