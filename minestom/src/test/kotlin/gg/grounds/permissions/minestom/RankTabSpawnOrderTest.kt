package gg.grounds.permissions.minestom

import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.SnapshotPermissions
import java.net.SocketAddress
import java.time.Instant
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.network.ConnectionState
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RankTabSpawnOrderTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `rank decoration preserves profile before spawn for every viewer edition`(
        bedrock: Boolean
    ) {
        val subject = UUID.randomUUID()
        val now = Instant.now()
        val snapshot =
            PermissionSnapshot(
                subject,
                1,
                now.minusSeconds(30),
                now.plusSeconds(300),
                now.plusSeconds(3600),
                emptyList(),
                emptyList(),
                setOf("administrator"),
                listOf(RoleMetadata("administrator", "Administrator", color = "#C9826B")),
            )
        val connection = QueuedConnection()
        val viewer =
            Player(
                connection,
                GameProfile(if (bedrock) UUID(0, 123) else UUID.randomUUID(), "Viewer"),
            )
        val original =
            PlayerInfoUpdatePacket(
                PlayerInfoUpdatePacket.Action.ADD_PLAYER,
                PlayerInfoUpdatePacket.Entry(
                    subject,
                    "Steve",
                    emptyList(),
                    true,
                    42,
                    GameMode.SURVIVAL,
                    null,
                    null,
                    0,
                    true,
                ),
            )
        val spawn = SpawnEntityPacket(42, subject, EntityType.PLAYER, Pos.ZERO, 0f, 0, Vec.ZERO)

        MinestomRankNameTags(SnapshotPermissions(mapOf(subject to snapshot))).use {
            connection.sendPacket(original)
            connection.sendPacket(spawn)
            val written = connection.drain(viewer)

            assertSame(
                original,
                written.first(),
                "ADD_PLAYER must retain its place before entity spawn",
            )
            assertSame(spawn, written[1])
            val info = written.filterIsInstance<PlayerInfoUpdatePacket>()
            assertEquals(1, info.count { PlayerInfoUpdatePacket.Action.ADD_PLAYER in it.actions() })
            val decoration = info.last()
            assertEquals(
                setOf(PlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                decoration.actions(),
            )
            assertEquals(subject, decoration.entries().single().uuid())
            assertNotNull(decoration.entries().single().displayName())
            assertEquals(3, written.size, "Display updates must not recursively queue more packets")
        }
    }

    // Socket connections dispatch outgoing events while draining, after all spawn packets have
    // already been queued. An immediate-send fake would hide the production ordering regression.
    private class QueuedConnection : PlayerConnection() {
        private val queued = ArrayDeque<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            queued.addLast(packet)
        }

        override fun getRemoteAddress(): SocketAddress? = null

        fun drain(viewer: Player): List<ServerPacket> = buildList {
            repeat(20) {
                if (queued.isEmpty()) return@buildList
                val packet =
                    SendablePacket.extractServerPacket(ConnectionState.PLAY, queued.removeFirst())!!
                val event = PlayerPacketOutEvent(viewer, packet)
                EventDispatcher.call(event)
                if (!event.isCancelled) add(packet)
            }
            fail<Unit>("Outgoing packet listener keeps resending packets")
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
