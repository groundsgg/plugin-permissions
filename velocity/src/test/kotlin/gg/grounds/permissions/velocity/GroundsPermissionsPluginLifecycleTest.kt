package gg.grounds.permissions.velocity

import com.velocitypowered.api.command.CommandManager
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.EventManager
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.PluginManager
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import com.velocitypowered.api.scheduler.Scheduler
import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.client.PermissionManifestRegistrationResult
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationConfig
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GroundsPermissionsPluginLifecycleTest {
    @Test
    fun `initialize composes the runtime and passes its real dependencies to invalidations`() {
        val captures = mutableListOf<VelocityInvalidationCapture>()
        val handle = VelocityRecordingHandle()
        val fixture = fixture { config, snapshots, client, context, proxy, logger ->
            captures +=
                VelocityInvalidationCapture(config, snapshots, client, context, proxy, logger)
            handle
        }

        fixture.plugin.onInitialize(ProxyInitializeEvent())

        val capture = captures.single()
        assertEquals("nats://nats.nats.svc.cluster.local:4222", capture.config?.natsUrl)
        assertTrue(capture.snapshots.all().isEmpty())
        assertSame(fixture.client, capture.client)
        assertEquals("velocity", capture.context.serverType)
        assertEquals("proxy-1", capture.context.serverId)
        assertSame(fixture.proxy, capture.proxy)
        assertSame(fixture.logger, capture.logger)

        fixture.plugin.onShutdown(ProxyShutdownEvent())
        assertTrue(handle.closed)
    }

    @Test
    fun `reinitialization closes the previous invalidation handle before creating the next`() {
        val events = mutableListOf<String>()
        var initialization = 0
        val fixture = fixture { _, _, _, _, _, _ ->
            initialization++
            val current = initialization
            events += "create-$current"
            VelocityRecordingHandle { events += "close-$current" }
        }

        fixture.plugin.onInitialize(ProxyInitializeEvent())
        fixture.plugin.onInitialize(ProxyInitializeEvent())
        fixture.plugin.onShutdown(ProxyShutdownEvent())
        fixture.plugin.onShutdown(ProxyShutdownEvent())

        assertEquals(listOf("create-1", "close-1", "create-2", "close-2"), events)
    }

    @Test
    fun `shutdown during composition closes the invalidation handle returned after shutdown`() {
        val starterEntered = CountDownLatch(1)
        val releaseStarter = CountDownLatch(1)
        val handle = VelocityRecordingHandle()
        val fixture = fixture { _, _, _, _, _, _ ->
            starterEntered.countDown()
            check(releaseStarter.await(5, TimeUnit.SECONDS))
            handle
        }

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val initialization =
                executor.submit { fixture.plugin.onInitialize(ProxyInitializeEvent()) }
            assertTrue(starterEntered.await(5, TimeUnit.SECONDS))

            fixture.plugin.onShutdown(ProxyShutdownEvent())
            releaseStarter.countDown()
            initialization.get(5, TimeUnit.SECONDS)
        }

        assertTrue(handle.closed)
    }

    @Test
    fun `unavailable optional invalidations leave composed runtime operational`() {
        val fixture = fixture { _, _, _, _, _, _ -> null }

        fixture.plugin.onInitialize(ProxyInitializeEvent())
        fixture.plugin.onShutdown(ProxyShutdownEvent())
    }

    private fun fixture(
        starter:
            (
                PermissionSnapshotInvalidationConfig?,
                InMemoryPermissionSnapshots,
                PermissionRuntimeClient,
                PermissionSnapshotContext,
                ProxyServer,
                org.slf4j.Logger,
            ) -> AutoCloseable?
    ): VelocityCompositionFixture = VelocityCompositionFixture(starter)
}

private class VelocityCompositionFixture(
    starter:
        (
            PermissionSnapshotInvalidationConfig?,
            InMemoryPermissionSnapshots,
            PermissionRuntimeClient,
            PermissionSnapshotContext,
            ProxyServer,
            org.slf4j.Logger,
        ) -> AutoCloseable?
) {
    val logger = RecordingLogger()
    val client = VelocityCompositionClient()
    val proxy: ProxyServer = mock()
    val plugin: GroundsPermissionsPlugin

    init {
        val eventManager: EventManager = mock()
        val scheduler: Scheduler = mock()
        val taskBuilder: Scheduler.TaskBuilder = mock(defaultAnswer = Answers.RETURNS_SELF)
        val scheduledTask: ScheduledTask = mock()
        val commandManager: CommandManager = mock()
        val commandBuilder: CommandMeta.Builder = mock(defaultAnswer = Answers.RETURNS_SELF)
        val commandMeta: CommandMeta = mock()
        val pluginManager: PluginManager = mock { on { plugins } doReturn emptyList() }

        whenever(proxy.eventManager).thenReturn(eventManager)
        whenever(proxy.scheduler).thenReturn(scheduler)
        whenever(proxy.commandManager).thenReturn(commandManager)
        whenever(proxy.pluginManager).thenReturn(pluginManager)
        whenever(scheduler.buildTask(any(), any<Runnable>())).thenReturn(taskBuilder)
        whenever(taskBuilder.schedule()).thenReturn(scheduledTask)
        whenever(commandManager.metaBuilder("permissions")).thenReturn(commandBuilder)
        whenever(commandBuilder.build()).thenReturn(commandMeta)

        plugin =
            GroundsPermissionsPlugin(
                proxy = proxy,
                logger = logger,
                environmentProvider = { configuredEnvironment() },
                runtimeClientFactory = VelocityPermissionRuntimeClientFactory { _, _ -> client },
                snapshotInvalidationStarter = VelocityPermissionSnapshotInvalidationStarter(starter),
            )
    }

    private fun configuredEnvironment(): Map<String, String> =
        mapOf(
            "PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080",
            "PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token",
            "GROUNDS_PERMISSION_SERVER_TYPE" to "velocity",
            "GROUNDS_PERMISSION_SERVER_ID" to "proxy-1",
            "PERMISSIONS_REFRESH_INTERVAL_SECONDS" to "3600",
            "NATS_URL" to "nats://nats.nats.svc.cluster.local:4222",
        )
}

private data class VelocityInvalidationCapture(
    val config: PermissionSnapshotInvalidationConfig?,
    val snapshots: InMemoryPermissionSnapshots,
    val client: PermissionRuntimeClient,
    val context: PermissionSnapshotContext,
    val proxy: ProxyServer,
    val logger: org.slf4j.Logger,
)

private class VelocityCompositionClient : PermissionRuntimeClient {
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

private class VelocityRecordingHandle(private val onClose: () -> Unit = {}) : AutoCloseable {
    var closed = false

    override fun close() {
        check(!closed)
        closed = true
        onClose()
    }
}
