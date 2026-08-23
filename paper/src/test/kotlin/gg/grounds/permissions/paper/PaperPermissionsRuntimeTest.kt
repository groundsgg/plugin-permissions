package gg.grounds.permissions.paper

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.Permissions
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.client.PermissionManifestRegistrationResult
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.client.SnapshotFailureReason
import gg.grounds.permissions.client.SnapshotUnavailableException
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger

class PaperPermissionsRuntimeTest {
    @Test fun `disabled configuration does not install a runtime`() {
        val platform = RecordingPlatform()
        runtime(platform, emptyMap()).start()
        assertNull(platform.permissions)
        assertFalse(platform.validated)
    }

    @Test fun `async pre-login succeeds and stores a snapshot`() {
        val id = UUID.randomUUID()
        val expected = snapshot(id)
        val platform = RecordingPlatform()
        val runtime = runtime(platform, environment(), Client { expected })
        runtime.start()
        assertTrue(requireNotNull(platform.preLogin).invoke(id).allowed)
        assertEquals(expected, runtime.snapshotForTest(id))
    }

    @Test fun `async pre-login denies unavailable snapshots`() {
        val platform = RecordingPlatform()
        runtime(platform, environment(), Client { throw SnapshotUnavailableException(SnapshotFailureReason.UNAVAILABLE) }).start()
        assertFalse(requireNotNull(platform.preLogin).invoke(UUID.randomUUID()).allowed)
    }

    @Test fun `login injects only after a successful pre-login snapshot`() {
        val id = UUID.randomUUID()
        val platform = RecordingPlatform()
        val runtime = runtime(platform, environment(), Client { snapshot(id, allowPatterns = listOf(grant("*"))) })
        runtime.start()
        val player = TestPermissionPlayer(id)
        assertFalse(requireNotNull(platform.login).invoke(player).allowed)
        assertTrue(requireNotNull(platform.preLogin).invoke(id).allowed)
        assertTrue(requireNotNull(platform.login).invoke(player).allowed)
        assertEquals(listOf(player), platform.injected)
    }

    @Test fun `login denies when injection fails`() {
        val id = UUID.randomUUID()
        val platform = RecordingPlatform(injectionFailure = IllegalStateException("inaccessible perm field"))
        val runtime = runtime(platform, environment(), Client { snapshot(id) })
        runtime.start()
        requireNotNull(platform.preLogin).invoke(id)
        assertFalse(requireNotNull(platform.login).invoke(TestPermissionPlayer(id)).allowed)
        assertTrue(platform.injected.isEmpty())
    }

    @Test fun `runtime publishes common permissions service`() {
        val platform = RecordingPlatform()
        runtime(platform, environment()).start()
        assertNotNull(platform.permissions)
        assertTrue(platform.validated)
    }

    @Test fun `snapshot refresh updates commands without reinjecting`() {
        val id = UUID.randomUUID()
        val old = snapshot(id, refreshAfter = NOW.minusSeconds(1))
        val fresh = snapshot(id, 2, allowPatterns = listOf(grant("*")))
        var calls = 0
        val platform = RecordingPlatform(setOf(id))
        val runtime = runtime(platform, environment(), Client { if (calls++ == 0) old else fresh })
        runtime.start()
        val player = TestPermissionPlayer(id)
        requireNotNull(platform.preLogin).invoke(id)
        requireNotNull(platform.login).invoke(player)
        requireNotNull(platform.refresh).invoke()
        assertEquals(fresh, runtime.snapshotForTest(id))
        assertEquals(listOf(player), platform.injected)
        assertEquals(listOf(id), platform.refreshed)
    }

    @Test fun `successful invalidation refreshes commands without reinjecting`() {
        val id = UUID.randomUUID()
        val platform = RecordingPlatform(setOf(id))
        var callback: ((UUID) -> Unit)? = null
        var snapshots: InMemoryPermissionSnapshots? = null
        val runtime = runtime(platform, environment(true), Client { snapshot(id) }) { _, store, _, _, _, _, refreshed ->
            snapshots = store
            callback = refreshed
            Closeable()
        }
        runtime.start()
        val player = TestPermissionPlayer(id)
        requireNotNull(platform.preLogin).invoke(id)
        requireNotNull(platform.login).invoke(player)
        snapshots!!.put(snapshot(id, 2, allowPatterns = listOf(grant("*"))))
        callback!!.invoke(id)
        assertEquals(listOf(player), platform.injected)
        assertEquals(listOf(id), platform.refreshed)
    }

    @Test fun `replacement closes the previous NATS invalidation lifecycle`() {
        val platform = RecordingPlatform()
        val handles = mutableListOf<Closeable>()
        val runtime = runtime(platform, environment(true), invalidationStarter = { _, _, _, _, _, _, _ -> Closeable().also(handles::add) })
        runtime.start()
        runtime.start()
        assertTrue(handles.first().closed)
        runtime.stop()
        assertTrue(handles.last().closed)
    }

    @Test fun `quit retires player before removing its snapshot`() {
        val id = UUID.randomUUID()
        val player = TestPermissionPlayer(id)
        val platform = RecordingPlatform()
        val runtime = runtime(platform, environment(), Client { snapshot(id) })
        runtime.start()
        requireNotNull(platform.preLogin).invoke(id)
        requireNotNull(platform.login).invoke(player)
        requireNotNull(platform.quit).invoke(player)
        assertEquals(listOf(player), platform.retired)
        assertNotNull(platform.snapshotDuringRetirement)
        assertNull(runtime.snapshotForTest(id))
    }

    @Test fun `delayed quit from an old session preserves a reconnected session`() {
        val id = UUID.randomUUID()
        val oldSession = TestPermissionPlayer(id)
        val newSession = TestPermissionPlayer(id)
        val platform = RecordingPlatform()
        val runtime = runtime(platform, environment(), Client { snapshot(id) })
        runtime.start()
        requireNotNull(platform.preLogin).invoke(id)
        requireNotNull(platform.login).invoke(oldSession)
        requireNotNull(platform.preLogin).invoke(id)
        requireNotNull(platform.login).invoke(newSession)

        requireNotNull(platform.quit).invoke(oldSession)

        assertEquals(listOf(oldSession, newSession), platform.injected)
        assertTrue(platform.retired.isEmpty())
        assertNotNull(runtime.snapshotForTest(id))
    }

    @Test fun `restart reinjects an online player with a valid snapshot`() {
        val id = UUID.randomUUID()
        val player = TestPermissionPlayer(id)
        val onlinePlayers = mutableSetOf<PaperPermissionPlayer>()
        val platform = RecordingPlatform(onlinePlayers = onlinePlayers)
        val runtime = runtime(platform, environment(), Client { snapshot(id) })
        runtime.start()
        requireNotNull(platform.preLogin).invoke(id)
        requireNotNull(platform.login).invoke(player)
        onlinePlayers += player

        runtime.start()

        assertEquals(listOf(player, player), platform.injected)
        assertTrue(platform.restoredAll)
    }

    @Test fun `login rollback retires and clears a later disallowed session`() {
        val id = UUID.randomUUID()
        val player = TestPermissionPlayer(id)
        val platform = RecordingPlatform()
        val runtime = runtime(platform, environment(), Client { snapshot(id) })
        runtime.start()
        requireNotNull(platform.preLogin).invoke(id)
        requireNotNull(platform.login).invoke(player)

        requireNotNull(platform.loginRollback).invoke(player)

        assertEquals(listOf(player), platform.retired)
        assertNull(runtime.snapshotForTest(id))
        requireNotNull(platform.preLogin).invoke(id)
        assertTrue(requireNotNull(platform.login).invoke(TestPermissionPlayer(id)).allowed)
    }

    @Test fun `shutdown restores all permissions and closes platform hooks`() {
        val platform = RecordingPlatform()
        val runtime = runtime(platform, environment())
        runtime.start()
        runtime.stop()
        assertTrue(platform.pre.closed && platform.loginClose.closed && platform.quitClose.closed && platform.refreshClose.closed && platform.restoredAll && platform.unpublished)
    }

    private fun runtime(platform: RecordingPlatform, environment: Map<String, String>, client: Client = Client { snapshot(UUID.randomUUID()) }, invalidationStarter: (gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationConfig?, InMemoryPermissionSnapshots, PermissionRuntimeClient, PermissionSnapshotContext, (UUID) -> Boolean, org.slf4j.Logger, (UUID) -> Unit) -> AutoCloseable? = { _, _, _, _, _, _, _ -> null }) =
        PaperPermissionsRuntime({ environment }, PaperPermissionRuntimeClientFactory { _, _ -> client }, PaperPermissionInvalidationStarter(invalidationStarter), platform, NOPLogger.NOP_LOGGER, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC))

    private fun environment(nats: Boolean = false) = buildMap {
        put("PERMISSIONS_SERVICE_URL", "http://permissions:8080")
        put("PERMISSIONS_TOKEN_FILE", "/var/run/token")
        if (nats) put("NATS_URL", "nats://nats:4222")
    }

    private fun snapshot(playerId: UUID, version: Long = 1, refreshAfter: Instant = NOW.plusSeconds(300), allowPatterns: List<PermissionGrant> = emptyList()) =
        PermissionSnapshot(playerId, version, NOW.minusSeconds(30), refreshAfter, NOW.plusSeconds(3600), allowPatterns, emptyList(), emptySet(), emptyList())

    private fun grant(pattern: String) = PermissionGrant(PermissionEffect.ALLOW, pattern, PermissionScope.global(), PermissionGrantSource.ROLE)
    private companion object { val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z") }
}

private class TestPermissionPlayer(override val playerId: UUID) : PaperPermissionPlayer {
    override val session: Any = this
}

private class RecordingPlatform(
    private val players: Set<UUID> = emptySet(),
    private val injectionFailure: RuntimeException? = null,
    private val onlinePlayers: Set<PaperPermissionPlayer> = emptySet(),
) : PaperPermissionPlatform {
    var preLogin: ((UUID) -> PermissionLoginResult)? = null
    var login: ((PaperPermissionPlayer) -> PermissionLoginResult)? = null
    var loginRollback: ((PaperPermissionPlayer) -> Unit)? = null
    var quit: ((PaperPermissionPlayer) -> Unit)? = null
    var refresh: (() -> Unit)? = null
    var permissions: Permissions? = null
    var unpublished = false
    var validated = false
    val injected = mutableListOf<PaperPermissionPlayer>()
    val refreshed = mutableListOf<UUID>()
    val retired = mutableListOf<PaperPermissionPlayer>()
    var snapshotDuringRetirement: PermissionSnapshot? = null
    var restoredAll = false
    val pre = Closeable()
    val loginClose = Closeable()
    val quitClose = Closeable()
    val refreshClose = Closeable()
    override fun onlinePlayerIds() = players
    override fun onlinePlayers() = onlinePlayers
    override fun validateInjection() { validated = true }
    override fun registerPreLogin(handler: (UUID) -> PermissionLoginResult) = pre.also { preLogin = handler }
    override fun registerPlayerLogin(handler: (PaperPermissionPlayer) -> PermissionLoginResult) = loginClose.also { login = handler }
    override fun registerPlayerLoginRollback(handler: (PaperPermissionPlayer) -> Unit) = loginClose.also { loginRollback = handler }
    override fun registerQuit(handler: (PaperPermissionPlayer) -> Unit) = quitClose.also { quit = handler }
    override fun scheduleRefresh(intervalSeconds: Long, task: () -> Unit) = refreshClose.also { refresh = task }
    override fun publish(permissions: Permissions) { this.permissions = permissions }
    override fun unpublish() { unpublished = true; permissions = null }
    override fun injectPermissions(player: PaperPermissionPlayer, permissions: Permissions) { injectionFailure?.let { throw it }; injected += player }
    override fun refreshPermissions(playerId: UUID) { refreshed += playerId }
    override fun retirePermissions(player: PaperPermissionPlayer) { retired += player; snapshotDuringRetirement = permissions?.snapshot(player.playerId) }
    override fun restoreAllPermissions() { restoredAll = true }
    override fun runOnServerThread(task: () -> Unit) = task()
}

private class Closeable : AutoCloseable { var closed = false; override fun close() { closed = true } }

private class Client(private val fetch: (UUID) -> PermissionSnapshot) : PermissionRuntimeClient {
    override fun fetchSnapshot(playerId: UUID, context: PermissionSnapshotContext) = fetch(playerId)
    override fun registerManifest(manifest: PermissionManifest, sourceVersion: String, context: PermissionSnapshotContext) = PermissionManifestRegistrationResult.Accepted
}
