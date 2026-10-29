package gg.grounds.permissions.minestom

import java.net.SocketAddress
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.play.DisplayScoreboardPacket
import net.minestom.server.network.packet.server.play.ResetScorePacket
import net.minestom.server.network.packet.server.play.ScoreboardObjectivePacket
import net.minestom.server.network.packet.server.play.UpdateScorePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.scoreboard.Sidebar.NumberFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class RankNameTagStatusLineTest {
    @Test
    fun `bedrock status uses native below-name slot and the viewers own font`() {
        val javaConnection = RecordingConnection()
        val bedrockConnection = RecordingConnection()
        val java = player("Java", javaConnection)
        val bedrock = player("Bedrock", bedrockConnection)
        val line = RankNameTagStatusLine()
        line.refresh(listOf(java, bedrock)) { it == bedrock.uuid }
        for ((connection, icon) in
            listOf(javaConnection to "\uE004", bedrockConnection to "\uE500")) {
            val objective =
                connection.packets.filterIsInstance<ScoreboardObjectivePacket>().single()
            assertEquals(Component.empty(), objective.objectiveValue())
            assertEquals(NumberFormat.blank(), objective.numberFormat())
            assertEquals(
                2.toByte(),
                connection.packets.filterIsInstance<DisplayScoreboardPacket>().single().position(),
            )
            val score = connection.packets.filterIsInstance<UpdateScorePacket>().single()
            assertEquals("Bedrock", score.entityName())
            assertEquals(icon, (score.numberFormat()!!.content() as TextComponent).content())
        }
        val count = javaConnection.packets.size
        line.refresh(listOf(java, bedrock)) { it == bedrock.uuid }
        assertEquals(count, javaConnection.packets.size, "Unchanged statuses must not send packets")
        line.close()
        assertEquals(
            1.toByte(),
            (javaConnection.packets.last() as ScoreboardObjectivePacket).mode(),
        )
    }

    @Test
    fun `game below-name objectives keep ownership until cleared`() {
        val connection = RecordingConnection()
        val viewer = player("Viewer", connection)
        val line = RankNameTagStatusLine()
        line.observe(viewer, DisplayScoreboardPacket(2, "game-health"))
        line.refresh(listOf(viewer)) { true }
        assertTrue(connection.packets.isEmpty())
        line.observe(viewer, DisplayScoreboardPacket(2, ""))
        line.refresh(listOf(viewer)) { true }
        assertEquals(1, connection.packets.filterIsInstance<DisplayScoreboardPacket>().size)
        line.close()
    }

    @Test
    fun `queued status display cannot reclaim the slot after a game activates its objective`() {
        val connection = RecordingConnection()
        val viewer = player("Viewer", connection)
        val other = player("Other", RecordingConnection())
        val line = RankNameTagStatusLine()
        line.refresh(listOf(viewer, other)) { true }
        val queued = connection.packets.filterIsInstance<DisplayScoreboardPacket>().single()
        line.observe(viewer, DisplayScoreboardPacket(2, "game-health"))
        assertFalse(line.observe(viewer, queued))
        connection.packets.clear()
        line.refresh(listOf(viewer, other)) { it == other.uuid }
        assertTrue(
            connection.packets.isEmpty(),
            "Queued status display must yield to the game objective",
        )
        line.observe(viewer, DisplayScoreboardPacket(2, ""))
        line.refresh(listOf(viewer, other)) { it == other.uuid }
        assertEquals(1, connection.packets.filterIsInstance<DisplayScoreboardPacket>().size)
        line.close()
    }

    @Test
    fun `deleting the gameplay objective releases the below-name slot`() {
        val connection = RecordingConnection()
        val viewer = player("Viewer", connection)
        val line = RankNameTagStatusLine()
        line.observe(viewer, DisplayScoreboardPacket(2, "game-health"))
        line.observe(
            viewer,
            ScoreboardObjectivePacket(
                "unrelated",
                1,
                Component.empty(),
                ScoreboardObjectivePacket.Type.INTEGER,
                null,
            ),
        )
        line.refresh(listOf(viewer)) { true }
        assertTrue(connection.packets.isEmpty())
        line.observe(
            viewer,
            ScoreboardObjectivePacket(
                "game-health",
                1,
                Component.empty(),
                ScoreboardObjectivePacket.Type.INTEGER,
                null,
            ),
        )
        line.refresh(listOf(viewer)) { true }
        assertEquals(1, connection.packets.filterIsInstance<DisplayScoreboardPacket>().size)
        line.close()
    }

    @Test
    fun `edition changes remove stale scores and empty status rows are removed`() {
        val connection = RecordingConnection()
        val viewer = player("Viewer", connection)
        val other = player("Other", RecordingConnection())
        val bedrockIds = mutableSetOf(viewer.uuid, other.uuid)
        val line = RankNameTagStatusLine()
        line.refresh(listOf(viewer, other), bedrockIds::contains)
        bedrockIds.remove(other.uuid)
        line.refresh(listOf(viewer, other), bedrockIds::contains)
        assertEquals(
            "Other",
            connection.packets.filterIsInstance<ResetScorePacket>().single().owner(),
        )
        // A linked account can change viewer edition without reconnecting.
        bedrockIds.remove(viewer.uuid)
        bedrockIds.add(other.uuid)
        line.refresh(listOf(viewer, other), bedrockIds::contains)
        val score = connection.packets.filterIsInstance<UpdateScorePacket>().last()
        assertEquals("Other", score.entityName())
        assertEquals("\uE004", (score.numberFormat()!!.content() as TextComponent).content())
        bedrockIds.clear()
        line.refresh(listOf(viewer, other), bedrockIds::contains)
        assertEquals(1.toByte(), (connection.packets.last() as ScoreboardObjectivePacket).mode())
        val count = connection.packets.size
        line.refresh(listOf(viewer, other), bedrockIds::contains)
        assertEquals(count, connection.packets.size)
        line.close()
    }

    private fun player(name: String, connection: RecordingConnection) =
        Player(connection, GameProfile(UUID.randomUUID(), name))

    private class RecordingConnection : PlayerConnection() {
        val packets = mutableListOf<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            packets.add(packet)
        }

        override fun getRemoteAddress(): SocketAddress? = null
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
