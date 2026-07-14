package gg.grounds.permissions.minestom

import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.SnapshotPermissions
import java.net.SocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import net.kyori.adventure.identity.Identity
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.tag.TagHandler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class PermissionCommandConditionTest {
    private val now: Instant = Instant.parse("2026-07-09T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val permission = "grounds.lobby.command.foo"

    @Test
    fun `allows a player holding the permission`() {
        val playerId = UUID.randomUUID()
        val permissions =
            SnapshotPermissions(mapOf(playerId to snapshot(playerId, permission)), clock = clock)
        val condition = permissions.commandCondition(permission)

        assertTrue(condition.canUse(fakePlayer(playerId), "foo"))
    }

    @Test
    fun `denies a player without the permission`() {
        val playerId = UUID.randomUUID()
        val permissions = SnapshotPermissions(emptyMap(), clock = clock)
        val condition = permissions.commandCondition(permission)

        assertFalse(condition.canUse(fakePlayer(playerId), "foo"))
    }

    @Test
    fun `allows a non-player sender`() {
        val permissions = SnapshotPermissions(emptyMap(), clock = clock)
        val condition = permissions.commandCondition(permission)

        assertTrue(condition.canUse(FakeConsoleSender(), "foo"))
    }

    private fun snapshot(playerId: UUID, permission: String): PermissionSnapshot =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = 1,
            issuedAt = now.minusSeconds(30),
            refreshAfter = now.plusSeconds(300),
            expiresAt = now.plusSeconds(3600),
            allowPatterns =
                listOf(
                    PermissionGrant(
                        effect = PermissionEffect.ALLOW,
                        pattern = permission,
                        scope = PermissionScope.global(),
                        source = PermissionGrantSource.ROLE,
                    )
                ),
            denyPatterns = emptyList(),
            roleKeys = emptySet(),
            roleMetadata = emptyList(),
        )

    private fun fakePlayer(uuid: UUID): Player = Player(FakeConnection(), GameProfile(uuid, "Alex"))

    private class FakeConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) {}

        override fun getRemoteAddress(): SocketAddress? = null
    }

    private class FakeConsoleSender : CommandSender {
        private val handler = TagHandler.newHandler()

        override fun tagHandler(): TagHandler = handler

        override fun identity(): Identity = Identity.nil()
    }

    companion object {
        // Player's static initializer reaches into MinecraftServer's dimension type registry, so
        // constructing one in a test requires the server to be booted first.
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
