package gg.grounds.permissions.velocity

import com.velocitypowered.api.permission.PermissionFunction
import com.velocitypowered.api.permission.PermissionProvider
import com.velocitypowered.api.permission.PermissionSubject
import com.velocitypowered.api.permission.Tristate
import com.velocitypowered.api.proxy.Player
import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.SnapshotPermissions
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SnapshotPermissionProviderTest {
    private val now: Instant = Instant.parse("2026-07-09T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val permission = "grounds.lobby.command.foo"

    @Test
    fun `player holding the permission gets Tristate TRUE`() {
        val playerId = UUID.randomUUID()
        val permissions =
            SnapshotPermissions(mapOf(playerId to snapshot(playerId, permission)), clock = clock)
        val fallback = FakePermissionProvider(PermissionFunction { Tristate.UNDEFINED })
        val provider = SnapshotPermissionProvider(permissions, fallback)

        val function = provider.createFunction(fakePlayer(playerId))

        assertEquals(Tristate.TRUE, function.getPermissionValue(permission))
    }

    @Test
    fun `player without the permission falls through to the fallback function`() {
        val playerId = UUID.randomUUID()
        val permissions = SnapshotPermissions(emptyMap(), clock = clock)
        val fallback = FakePermissionProvider(PermissionFunction { Tristate.UNDEFINED })
        val provider = SnapshotPermissionProvider(permissions, fallback)

        val function = provider.createFunction(fakePlayer(playerId))

        assertEquals(Tristate.UNDEFINED, function.getPermissionValue(permission))
    }

    @Test
    fun `non-player subject gets the fallback function unchanged`() {
        val permissions = SnapshotPermissions(emptyMap(), clock = clock)
        val fallbackFunction = PermissionFunction { Tristate.FALSE }
        val fallback = FakePermissionProvider(fallbackFunction)
        val provider = SnapshotPermissionProvider(permissions, fallback)
        val console = FakePermissionSubject(Tristate.TRUE)

        val function = provider.createFunction(console)

        assertSame(fallbackFunction, function)
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

    /**
     * Velocity's [Player] interface has dozens of members we don't care about; delegate all of them
     * to a proxy that fails loudly if touched, and override only [Player.getUniqueId].
     */
    private fun fakePlayer(uniqueId: UUID): Player {
        val unimplemented =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
                _,
                method,
                _ ->
                throw UnsupportedOperationException("Unexpected call: ${method.name}")
            } as Player
        return object : Player by unimplemented {
            override fun getUniqueId(): UUID = uniqueId
        }
    }

    private class FakePermissionProvider(private val function: PermissionFunction) :
        PermissionProvider {
        override fun createFunction(subject: PermissionSubject): PermissionFunction = function
    }

    private class FakePermissionSubject(private val value: Tristate) : PermissionSubject {
        override fun getPermissionValue(permission: String): Tristate = value
    }
}
