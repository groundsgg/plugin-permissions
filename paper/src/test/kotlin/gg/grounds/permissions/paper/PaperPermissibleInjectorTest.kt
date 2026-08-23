package gg.grounds.permissions.paper

import gg.grounds.permissions.SnapshotPermissions
import org.bukkit.permissions.PermissibleBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class PaperPermissibleInjectorTest {
    private lateinit var server: org.mockbukkit.mockbukkit.ServerMock

    @AfterEach fun tearDown() = MockBukkit.unmock()

    @Test
    fun `field access finds inherited permissible field`() {
        player()
        val original = PermissibleBase(null)
        val holder = TestPlayer(original)
        val access = PermissibleFieldAccess.locate(TestPlayer::class.java)
        val replacement = PermissibleBase(null)

        access.write(holder, replacement)

        assertSame(replacement, access.read(holder))
        access.write(holder, original)
        assertSame(original, access.read(holder))
    }

    @Test
    fun `field access rejects a perm field with an incompatible type`() {
        val exception =
            assertThrows(PaperPermissibleInjectionException::class.java) {
                PermissibleFieldAccess.locate(WrongPermHolder::class.java)
            }

        assertTrue(exception.message.orEmpty().contains(WrongPermHolder::class.java.name))
    }

    @Test
    fun `inject migrates attachments and restore reinstates original permissible`() {
        val player = player()
        val original = PermissibleBase(player)
        val holder = TestPlayer(original)
        val injector = PaperPermissibleInjector { holder }
        original.addAttachment(plugin(), "grounds.migrated", true)
        val replacement = GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap()))

        injector.inject(player, replacement)

        assertSame(replacement, PermissibleFieldAccess.locate(holder.javaClass).read(holder))
        assertEquals(true, replacement.currentAttachments().single().permissions["grounds.migrated"])
        injector.restore(player)

        assertSame(original, PermissibleFieldAccess.locate(holder.javaClass).read(holder))
        assertTrue(replacement.currentAttachments().isEmpty())
    }

    @Test
    fun `inject rejects duplicate Grounds permissible`() {
        val player = player()
        val holder = TestPlayer(PermissibleBase(player))
        val injector = PaperPermissibleInjector { holder }
        injector.inject(player, GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap())))

        assertThrows(PaperPermissibleInjectionException::class.java) {
            injector.inject(player, GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap())))
        }
    }

    @Test
    fun `inject rejects a foreign custom permissible`() {
        val player = player()
        val holder = TestPlayer(CustomPermissible())

        val exception =
            assertThrows(PaperPermissibleInjectionException::class.java) {
                PaperPermissibleInjector { holder }.inject(
                    player,
                    GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap())),
                )
            }

        assertTrue(exception.message.orEmpty().contains(CustomPermissible::class.java.name))
    }

    @Test
    fun `retire replaces Grounds permissible without retaining the player`() {
        val player = player()
        val holder = TestPlayer(PermissibleBase(player))
        val injector = PaperPermissibleInjector { holder }
        injector.inject(player, GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap())))

        injector.retire(player)

        val retired = PermissibleFieldAccess.locate(holder.javaClass).read(holder)
        assertTrue(retired !is GroundsPermissible)
        assertEquals(false, retired.hasPermission("grounds.anything"))
    }

    private fun player(): org.bukkit.entity.Player {
        server = MockBukkit.mock()
        MockBukkit.load(GroundsPermissionsPlugin::class.java)
        return server.addPlayer()
    }

    private fun plugin() = server.pluginManager.plugins.single() as GroundsPermissionsPlugin

    private open class TestHuman(protected var perm: PermissibleBase)

    private class TestPlayer(perm: PermissibleBase) : TestHuman(perm)

    private class WrongPermHolder(@Suppress("unused") private var perm: String)

    private class CustomPermissible : PermissibleBase(null)
}
