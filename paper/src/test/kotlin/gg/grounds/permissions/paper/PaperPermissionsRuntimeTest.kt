package gg.grounds.permissions.paper

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
    @Test
    fun `disabled configuration does not install a runtime`() {
        val platform = RecordingPlatform()
        runtime(platform, emptyMap()).start()
        assertNull(platform.permissions)
    }

    @Test
    fun `async pre-login succeeds and stores a snapshot`() {
        val playerId = UUID.randomUUID()
        val snapshot = snapshot(playerId)
        val platform = RecordingPlatform()
        val runtime = runtime(platform, environment(), Client { snapshot })
        runtime.start()
        assertTrue(requireNotNull(platform.preLogin).invoke(playerId).allowed)
        assertEquals(snapshot, runtime.snapshotForTest(playerId))
    }

    @Test
    fun `async pre-login denies unavailable snapshots`() {
        val platform = RecordingPlatform()
        runtime(
                platform,
                environment(),
                Client { throw SnapshotUnavailableException(SnapshotFailureReason.UNAVAILABLE) },
            )
            .start()
        assertFalse(requireNotNull(platform.preLogin).invoke(UUID.randomUUID()).allowed)
    }

    @Test
    fun `runtime publishes common permissions service`() {
        val platform = RecordingPlatform()
        runtime(platform, environment()).start()
        assertNotNull(platform.permissions)
    }

    @Test
    fun `refreshes online uuid snapshots`() {
        val playerId = UUID.randomUUID()
        val initial = snapshot(playerId, refreshAfter = NOW.minusSeconds(1))
        val refreshed = snapshot(playerId, 2)
        var clientCalls = 0
        val platform = RecordingPlatform(setOf(playerId))
        val client = Client { if (clientCalls++ == 0) initial else refreshed }
        val runtime = runtime(platform, environment(), client)
        runtime.start()
        requireNotNull(platform.preLogin).invoke(playerId)
        requireNotNull(platform.refresh).invoke()
        assertEquals(refreshed, runtime.snapshotForTest(playerId))
    }

    @Test
    fun `replacement closes the previous NATS invalidation lifecycle`() {
        val platform = RecordingPlatform()
        val handles = mutableListOf<Closeable>()
        val runtime =
            runtime(
                platform,
                environment(true),
                invalidationStarter = { _, _, _, _, _, _ -> Closeable().also(handles::add) },
            )
        runtime.start()
        runtime.start()
        assertTrue(handles.first().closed)
        runtime.stop()
        assertTrue(handles.last().closed)
    }

    @Test
    fun `quit removes player snapshot and shutdown closes platform hooks`() {
        val playerId = UUID.randomUUID()
        val platform = RecordingPlatform()
        val runtime = runtime(platform, environment(), Client { snapshot(playerId) })
        runtime.start()
        requireNotNull(platform.preLogin).invoke(playerId)
        requireNotNull(platform.quit).invoke(playerId)
        assertNull(runtime.snapshotForTest(playerId))
        runtime.stop()
        assertTrue(
            platform.pre.closed &&
                platform.quitClose.closed &&
                platform.refreshClose.closed &&
                platform.unpublished
        )
    }

    private fun runtime(
        platform: RecordingPlatform,
        environment: Map<String, String>,
        client: Client = Client { snapshot(UUID.randomUUID()) },
        invalidationStarter:
            (
                gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationConfig?,
                gg.grounds.permissions.InMemoryPermissionSnapshots,
                PermissionRuntimeClient,
                PermissionSnapshotContext,
                (UUID) -> Boolean,
                org.slf4j.Logger,
            ) -> AutoCloseable? =
            { _, _, _, _, _, _ ->
                null
            },
    ) =
        PaperPermissionsRuntime(
            { environment },
            PaperPermissionRuntimeClientFactory { _, _ -> client },
            PaperPermissionInvalidationStarter(invalidationStarter),
            platform,
            NOPLogger.NOP_LOGGER,
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
        )

    private fun environment(nats: Boolean = false) = buildMap {
        put("PERMISSIONS_SERVICE_URL", "http://permissions:8080")
        put("PERMISSIONS_TOKEN_FILE", "/var/run/token")
        if (nats) put("NATS_URL", "nats://nats:4222")
    }

    private fun snapshot(
        playerId: UUID,
        version: Long = 1,
        refreshAfter: Instant = NOW.plusSeconds(300),
    ) =
        PermissionSnapshot(
            playerId,
            version,
            NOW.minusSeconds(30),
            refreshAfter,
            NOW.plusSeconds(3600),
            emptyList(),
            emptyList(),
            emptySet(),
            emptyList(),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")
    }
}

private class RecordingPlatform(private val players: Set<UUID> = emptySet()) :
    PaperPermissionPlatform {
    var preLogin: ((UUID) -> PermissionLoginResult)? = null
    var quit: ((UUID) -> Unit)? = null
    var refresh: (() -> Unit)? = null
    var permissions: Permissions? = null
    var unpublished = false
    val pre = Closeable()
    val quitClose = Closeable()
    val refreshClose = Closeable()

    override fun onlinePlayerIds() = players

    override fun registerPreLogin(handler: (UUID) -> PermissionLoginResult) =
        pre.also { preLogin = handler }

    override fun registerQuit(handler: (UUID) -> Unit) = quitClose.also { quit = handler }

    override fun scheduleRefresh(intervalSeconds: Long, task: () -> Unit) =
        refreshClose.also { refresh = task }

    override fun publish(permissions: Permissions) {
        this.permissions = permissions
    }

    override fun unpublish() {
        unpublished = true
        permissions = null
    }
}

private class Closeable : AutoCloseable {
    var closed = false

    override fun close() {
        closed = true
    }
}

private class Client(private val fetch: (UUID) -> PermissionSnapshot) : PermissionRuntimeClient {
    override fun fetchSnapshot(playerId: UUID, context: PermissionSnapshotContext) = fetch(playerId)

    override fun registerManifest(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ) = PermissionManifestRegistrationResult.Accepted
}
