package gg.grounds.permissions.minestom

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.client.PermissionManifestRegistrationResult
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationConfig
import java.time.Instant
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MinestomPermissionSnapshotInvalidationsTest {
    private val playerId = UUID.fromString("0f287625-2442-4f55-b928-d2f53fbdf575")
    private val config =
        PermissionSnapshotInvalidationConfig(
            natsUrl = "nats://nats.nats.svc.cluster.local:4222",
            tokenFile = null,
            subject = "permissions.snapshot.invalidated",
        )

    @Test
    fun `configured runtime creates one subscriber and force fetches an online player`() {
        val snapshots = InMemoryPermissionSnapshots()
        val context = PermissionSnapshotContext(serverType = "lobby", serverId = "lobby-1")
        val snapshot = snapshot(policyVersion = 2)
        val client = MinestomInvalidationRuntimeClient(snapshot)
        val executor = MinestomQueuedExecutorService()
        val subscription = MinestomRecordingSubscription()
        var subscriberCount = 0
        var executorCount = 0

        val invalidations =
            MinestomPermissionSnapshotInvalidations.start(
                config = config,
                snapshots = snapshots,
                runtimeClient = client,
                context = context,
                isOnline = { it == playerId },
                logger = RecordingLogger(),
                subscriberFactory = { _, _, handler ->
                    subscriberCount++
                    subscription.handler = handler
                    subscription
                },
                executorFactory = {
                    executorCount++
                    executor
                },
            )

        requireNotNull(invalidations)
        assertEquals(1, subscriberCount)
        assertEquals(1, executorCount)

        subscription.handler(playerId)
        assertEquals(1, executor.queuedTaskCount)
        assertNull(snapshots.get(playerId))

        executor.runNext()

        assertEquals(listOf(playerId), client.playerIds)
        assertSame(context, client.contexts.single())
        assertEquals(snapshot, snapshots.get(playerId))

        invalidations.close()

        assertTrue(subscription.closed)
        assertTrue(executor.isShutdown)
    }

    @Test
    fun `offline invalidation does not use the REST client`() {
        val client = MinestomInvalidationRuntimeClient(snapshot(policyVersion = 2))
        val executor = MinestomQueuedExecutorService()
        val subscription = MinestomRecordingSubscription()
        val invalidations =
            MinestomPermissionSnapshotInvalidations.start(
                config = config,
                snapshots = InMemoryPermissionSnapshots(),
                runtimeClient = client,
                context = PermissionSnapshotContext(serverType = "lobby"),
                isOnline = { false },
                logger = RecordingLogger(),
                subscriberFactory = { _, _, handler ->
                    subscription.handler = handler
                    subscription
                },
                executorFactory = { executor },
            )

        requireNotNull(invalidations)
        subscription.handler(playerId)

        assertEquals(0, executor.queuedTaskCount)
        assertTrue(client.playerIds.isEmpty())
        invalidations.close()
    }

    @Test
    fun `closing before reinstall releases the previous subscription and executor`() {
        val firstExecutor = MinestomQueuedExecutorService()
        val firstSubscription = MinestomRecordingSubscription()
        val first = start(executor = firstExecutor, subscription = firstSubscription)

        first.close()

        val secondExecutor = MinestomQueuedExecutorService()
        val secondSubscription = MinestomRecordingSubscription()
        val second = start(executor = secondExecutor, subscription = secondSubscription)

        assertTrue(firstSubscription.closed)
        assertTrue(firstExecutor.isShutdown)
        assertFalse(secondSubscription.closed)
        assertFalse(secondExecutor.isShutdown)
        second.close()
    }

    @Test
    fun `absent NATS configuration does not allocate invalidation resources`() {
        var subscriberCount = 0
        var executorCount = 0

        val invalidations =
            MinestomPermissionSnapshotInvalidations.start(
                config = null,
                snapshots = InMemoryPermissionSnapshots(),
                runtimeClient = MinestomInvalidationRuntimeClient(snapshot(policyVersion = 2)),
                context = PermissionSnapshotContext(serverType = "lobby"),
                isOnline = { true },
                logger = RecordingLogger(),
                subscriberFactory = { _, _, _ ->
                    subscriberCount++
                    MinestomRecordingSubscription()
                },
                executorFactory = {
                    executorCount++
                    MinestomQueuedExecutorService()
                },
            )

        assertNull(invalidations)
        assertEquals(0, subscriberCount)
        assertEquals(0, executorCount)
    }

    @Test
    fun `subscriber startup failure does not fail runtime startup`() {
        val executor = MinestomQueuedExecutorService()

        val invalidations =
            MinestomPermissionSnapshotInvalidations.start(
                config = config,
                snapshots = InMemoryPermissionSnapshots(),
                runtimeClient = MinestomInvalidationRuntimeClient(snapshot(policyVersion = 2)),
                context = PermissionSnapshotContext(serverType = "lobby"),
                isOnline = { true },
                logger = RecordingLogger(),
                subscriberFactory = { _, _, _ -> error("nats unavailable") },
                executorFactory = { executor },
            )

        assertNull(invalidations)
        assertTrue(executor.isShutdown)
    }

    private fun start(
        executor: MinestomQueuedExecutorService,
        subscription: MinestomRecordingSubscription,
    ): MinestomPermissionSnapshotInvalidations =
        requireNotNull(
            MinestomPermissionSnapshotInvalidations.start(
                config = config,
                snapshots = InMemoryPermissionSnapshots(),
                runtimeClient = MinestomInvalidationRuntimeClient(snapshot(policyVersion = 2)),
                context = PermissionSnapshotContext(serverType = "lobby"),
                isOnline = { true },
                logger = RecordingLogger(),
                subscriberFactory = { _, _, handler ->
                    subscription.handler = handler
                    subscription
                },
                executorFactory = { executor },
            )
        )

    private fun snapshot(policyVersion: Long): PermissionSnapshot {
        val now = Instant.parse("2026-07-29T10:00:00Z")
        return PermissionSnapshot(
            playerId = playerId,
            policyVersion = policyVersion,
            issuedAt = now,
            refreshAfter = now.plusSeconds(300),
            expiresAt = now.plusSeconds(600),
            allowPatterns = emptyList(),
            denyPatterns = emptyList(),
            roleKeys = emptySet(),
            roleMetadata = emptyList(),
        )
    }
}

private class MinestomInvalidationRuntimeClient(private val snapshot: PermissionSnapshot) :
    PermissionRuntimeClient {
    val playerIds = mutableListOf<UUID>()
    val contexts = mutableListOf<PermissionSnapshotContext>()

    override fun fetchSnapshot(
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshot {
        playerIds += playerId
        contexts += context
        return snapshot
    }

    override fun registerManifest(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ): PermissionManifestRegistrationResult =
        error("Manifest registration is not used by this test")
}

private class MinestomRecordingSubscription : AutoCloseable {
    lateinit var handler: (UUID) -> Unit
    var closed = false

    override fun close() {
        closed = true
    }
}

private class MinestomQueuedExecutorService : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var shutdown = false

    val queuedTaskCount: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        check(!shutdown)
        tasks.addLast(command)
    }

    fun runNext() {
        tasks.removeFirst().run()
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return tasks.toMutableList().also { tasks.clear() }
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
}
