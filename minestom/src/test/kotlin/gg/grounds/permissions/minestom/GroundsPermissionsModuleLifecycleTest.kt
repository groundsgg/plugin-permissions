package gg.grounds.permissions.minestom

import gg.grounds.modules.ServiceRegistry
import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.client.PermissionManifestRegistrationResult
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationConfig
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.ServerType
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GroundsPermissionsModuleLifecycleTest {
    @Test
    fun `keeps the public no-argument module constructor`() {
        assertNotNull(GroundsPermissionsModule::class.java.getConstructor())
    }

    @Test
    fun `install composes the runtime and passes its real dependencies to invalidations`() {
        val captures = mutableListOf<MinestomInvalidationCapture>()
        val handle = MinestomLifecycleHandle()
        val fixture = fixture { config, snapshots, client, context, logger ->
            captures += MinestomInvalidationCapture(config, snapshots, client, context, logger)
            handle
        }

        fixture.module.install(fixture.context)

        val capture = captures.single()
        assertEquals("nats://nats.nats.svc.cluster.local:4222", capture.config?.natsUrl)
        assertTrue(capture.snapshots.all().isEmpty())
        assertSame(fixture.client, capture.client)
        assertEquals("lobby", capture.context.serverType)
        assertEquals("lobby-1", capture.context.serverId)
        assertNotNull(capture.logger)

        fixture.module.stop()
        assertTrue(handle.closed)
    }

    @Test
    fun `reinstall closes the previous invalidation handle before creating the next`() {
        val events = mutableListOf<String>()
        var installation = 0
        val fixture = fixture { _, _, _, _, _ ->
            installation++
            val current = installation
            events += "create-$current"
            MinestomLifecycleHandle { events += "close-$current" }
        }

        fixture.module.install(fixture.context)
        fixture.module.install(fixture.context)
        fixture.module.stop()
        fixture.module.stop()

        assertEquals(listOf("create-1", "close-1", "create-2", "close-2"), events)
    }

    @Test
    fun `stop during composition closes the invalidation handle returned after stop`() {
        val starterEntered = CountDownLatch(1)
        val releaseStarter = CountDownLatch(1)
        val handle = MinestomLifecycleHandle()
        val fixture = fixture { _, _, _, _, _ ->
            starterEntered.countDown()
            check(releaseStarter.await(5, TimeUnit.SECONDS))
            handle
        }

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val installation = executor.submit { fixture.module.install(fixture.context) }
            assertTrue(starterEntered.await(5, TimeUnit.SECONDS))

            fixture.module.stop()
            releaseStarter.countDown()
            installation.get(5, TimeUnit.SECONDS)
        }

        assertTrue(handle.closed)
    }

    @Test
    fun `failure in later manifest setup never starts invalidation resources`() {
        var starterCalls = 0
        val fixture = fixture { _, _, _, _, _ ->
            starterCalls++
            MinestomLifecycleHandle()
        }
        whenever(fixture.context.activeModuleProviders).thenThrow(IllegalStateException("failed"))

        assertThrows(IllegalStateException::class.java) { fixture.module.install(fixture.context) }
        assertEquals(0, starterCalls)

        fixture.module.stop()
    }

    private fun fixture(
        starter:
            (
                PermissionSnapshotInvalidationConfig?,
                InMemoryPermissionSnapshots,
                PermissionRuntimeClient,
                PermissionSnapshotContext,
                org.slf4j.Logger,
            ) -> AutoCloseable?
    ): MinestomCompositionFixture = MinestomCompositionFixture(starter)
}

private class MinestomCompositionFixture(
    starter:
        (
            PermissionSnapshotInvalidationConfig?,
            InMemoryPermissionSnapshots,
            PermissionRuntimeClient,
            PermissionSnapshotContext,
            org.slf4j.Logger,
        ) -> AutoCloseable?
) {
    val client = MinestomCompositionClient()
    val runtimePlatform = RecordingMinestomRuntimePlatform()
    val services: ServiceRegistry = mock()
    val context: GroundsServerContext = mock {
        on { serverType } doReturn ServerType.LOBBY
        on { services } doReturn services
        on { activeModuleProviders } doReturn emptyList()
        on { eventNode(any()) } doReturn EventNode.all("permissions-test")
    }
    val module =
        GroundsPermissionsModule(
            environmentProvider = { configuredEnvironment() },
            runtimeClientFactory = MinestomPermissionRuntimeClientFactory { _, _ -> client },
            snapshotInvalidationStarter = MinestomPermissionSnapshotInvalidationStarter(starter),
            runtimePlatform = runtimePlatform,
        )

    private fun configuredEnvironment(): Map<String, String> =
        mapOf(
            "PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080",
            "PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token",
            "GROUNDS_PERMISSION_SERVER_TYPE" to "lobby",
            "GROUNDS_PERMISSION_SERVER_ID" to "lobby-1",
            "PERMISSIONS_REFRESH_INTERVAL_SECONDS" to "3600",
            "NATS_URL" to "nats://nats.nats.svc.cluster.local:4222",
        )
}

private data class MinestomInvalidationCapture(
    val config: PermissionSnapshotInvalidationConfig?,
    val snapshots: InMemoryPermissionSnapshots,
    val client: PermissionRuntimeClient,
    val context: PermissionSnapshotContext,
    val logger: org.slf4j.Logger,
)

private class MinestomCompositionClient : PermissionRuntimeClient {
    override fun fetchSnapshot(
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshot = error("Snapshot fetch is not used during composition")

    override fun registerManifest(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ): PermissionManifestRegistrationResult = PermissionManifestRegistrationResult.Accepted
}

private class RecordingMinestomRuntimePlatform : MinestomPermissionRuntimePlatform {
    val eventNodes = mutableSetOf<EventNode<Event>>()

    override fun onlinePlayerIds(): Set<UUID> = emptySet()

    override fun addEventNode(node: EventNode<Event>) {
        eventNodes += node
    }

    override fun removeEventNode(node: EventNode<Event>) {
        eventNodes -= node
    }
}

private class MinestomLifecycleHandle(private val onClose: () -> Unit = {}) : AutoCloseable {
    var closed = false

    override fun close() {
        check(!closed)
        closed = true
        onClose()
    }
}
