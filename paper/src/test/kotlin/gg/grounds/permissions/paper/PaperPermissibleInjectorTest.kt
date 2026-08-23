package gg.grounds.permissions.paper

import gg.grounds.permissions.SnapshotPermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.bukkit.permissions.PermissibleBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertEquals(
            true,
            replacement.currentAttachments().single().permissions["grounds.migrated"],
        )
        injector.restore(player)

        assertSame(original, PermissibleFieldAccess.locate(holder.javaClass).read(holder))
        assertTrue(replacement.currentAttachments().isEmpty())
    }

    @Test
    fun `restore preserves a foreign permissible and retains the original for recovery`() {
        val player = player()
        val original = PermissibleBase(player)
        val holder = TestPlayer(original)
        val access = PermissibleFieldAccess.locate(holder.javaClass)
        val injector = PaperPermissibleInjector { holder }
        val grounds = GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap()))
        injector.inject(player, grounds)
        val foreign = CustomPermissible()
        access.write(holder, foreign)

        assertThrows(PaperPermissibleInjectionException::class.java) { injector.restore(player) }
        assertSame(foreign, access.read(holder))

        access.write(holder, grounds)
        injector.restore(player)
        assertSame(original, access.read(holder))
    }

    @Test
    fun `retire preserves a foreign permissible and retains the original for recovery`() {
        val player = player()
        val original = PermissibleBase(player)
        val holder = TestPlayer(original)
        val access = PermissibleFieldAccess.locate(holder.javaClass)
        val injector = PaperPermissibleInjector { holder }
        val grounds = GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap()))
        injector.inject(player, grounds)
        val foreign = CustomPermissible()
        access.write(holder, foreign)

        assertThrows(PaperPermissibleInjectionException::class.java) { injector.retire(player) }
        assertSame(foreign, access.read(holder))

        access.write(holder, grounds)
        injector.retire(player)
        assertTrue(access.read(holder) !is GroundsPermissible)
    }

    @Test
    fun `inject rejects duplicate Grounds permissible`() {
        val player = player()
        val holder = TestPlayer(PermissibleBase(player))
        val injector = PaperPermissibleInjector { holder }
        injector.inject(
            player,
            GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap())),
        )

        assertThrows(PaperPermissibleInjectionException::class.java) {
            injector.inject(
                player,
                GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap())),
            )
        }
    }

    @Test
    fun `inject rejects a foreign custom permissible`() {
        val player = player()
        val holder = TestPlayer(CustomPermissible())

        val exception =
            assertThrows(PaperPermissibleInjectionException::class.java) {
                PaperPermissibleInjector { holder }
                    .inject(
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
        injector.inject(
            player,
            GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap())),
        )

        injector.retire(player)

        val retired = PermissibleFieldAccess.locate(holder.javaClass).read(holder)
        assertTrue(retired !is GroundsPermissible)
        assertEquals(false, retired.hasPermission("grounds.anything"))
        retired.addAttachment(plugin(), "grounds.anything", true)
        retired.recalculatePermissions()
        retired.clearPermissions()
        assertTrue(retired.effectivePermissions.isEmpty())
        assertFalse(retired.hasPermission("grounds.anything"))
    }

    @Test
    fun `restoreAll continues after a foreign replacement`() {
        val first = player()
        val second = server.addPlayer()
        val firstOriginal = PermissibleBase(first)
        val secondOriginal = PermissibleBase(second)
        val firstHolder = TestPlayer(firstOriginal)
        val secondHolder = TestPlayer(secondOriginal)
        val holders =
            java.util.IdentityHashMap<org.bukkit.entity.Player, TestPlayer>().apply {
                put(first, firstHolder)
                put(second, secondHolder)
            }
        val injector = PaperPermissibleInjector { player -> holders.getValue(player) }
        injector.inject(first, GroundsPermissible(first, plugin(), SnapshotPermissions(emptyMap())))
        injector.inject(
            second,
            GroundsPermissible(second, plugin(), SnapshotPermissions(emptyMap())),
        )
        PermissibleFieldAccess.locate(firstHolder.javaClass).write(firstHolder, CustomPermissible())

        val failures = mutableListOf<org.bukkit.entity.Player>()
        injector.restoreAll(listOf(first, second)) { player, _ -> failures += player }

        assertEquals(listOf(first), failures)
        assertSame(
            secondOriginal,
            PermissibleFieldAccess.locate(secondHolder.javaClass).read(secondHolder),
        )
    }

    @Test
    fun `lifecycle operations on one injector are serialized`() {
        val player = player()
        val holder = TestPlayer(PermissibleBase(player))
        val targetCalls = AtomicInteger()
        val injectEntered = CountDownLatch(1)
        val releaseInject = CountDownLatch(1)
        val retireEntered = CountDownLatch(1)
        val failures = AtomicReference<Throwable?>()
        val injector = PaperPermissibleInjector {
            when (targetCalls.incrementAndGet()) {
                1 -> {
                    injectEntered.countDown()
                    releaseInject.await()
                }
                2 -> retireEntered.countDown()
            }
            holder
        }
        val grounds = GroundsPermissible(player, plugin(), SnapshotPermissions(emptyMap()))
        val injecting = Thread {
            runCatching { injector.inject(player, grounds) }.onFailure(failures::set)
        }
        val retiring = Thread { runCatching { injector.retire(player) }.onFailure(failures::set) }

        injecting.start()
        assertTrue(injectEntered.await(1, TimeUnit.SECONDS))
        retiring.start()
        assertFalse(retireEntered.await(100, TimeUnit.MILLISECONDS))
        releaseInject.countDown()
        injecting.join()
        retiring.join()

        assertEquals(null, failures.get())
        assertTrue(
            PermissibleFieldAccess.locate(holder.javaClass).read(holder) !is GroundsPermissible
        )
    }

    private fun player(): org.bukkit.entity.Player {
        server = MockBukkit.mock()
        MockBukkit.load(GroundsPermissionsPlugin::class.java)
        return server.addPlayer()
    }

    private fun plugin() = server.pluginManager.plugins.single() as GroundsPermissionsPlugin

    private open class TestHuman(protected val perm: PermissibleBase)

    private class TestPlayer(perm: PermissibleBase) : TestHuman(perm)

    private class WrongPermHolder(@Suppress("unused") private var perm: String)

    private class CustomPermissible : PermissibleBase(null)
}
