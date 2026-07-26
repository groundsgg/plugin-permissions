package gg.grounds.permissions.client

import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.catalog.PermissionManifestEntry
import gg.grounds.permissions.catalog.PermissionManifestScope
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestRegistrationSchedulerTest {
    private val status = PermissionRuntimeStatus()
    private val manifest = manifest("plugin-chat")

    @Test
    fun `starts registration asynchronously and allows only one active registration per source`() {
        val client = FakeRuntimeClient(PermissionManifestRegistrationResult.Accepted)
        val delays = FakeRegistrationDelayScheduler()
        val scheduler = scheduler(client, delays)

        scheduler.register(manifest, "1.4.0", PermissionSnapshotContext())
        scheduler.register(manifest, "1.5.0", PermissionSnapshotContext())

        assertEquals(0, client.registrations)
        assertEquals(listOf(Duration.ZERO), delays.scheduledDelays)

        delays.runNext()

        assertEquals(1, client.registrations)
        assertEquals(NOW, status.snapshot().lastManifestSuccessAt)
        assertFalse(delays.hasPending())
    }

    @Test
    fun `retries transient failures with capped exponential backoff`() {
        val failures =
            List(8) {
                PermissionManifestRegistrationResult.RetryableFailure(
                    reason = "service_unavailable",
                    statusCode = 503,
                    requestId = "request-$it",
                )
            }
        val client =
            FakeRuntimeClient(
                *(failures + PermissionManifestRegistrationResult.Accepted).toTypedArray()
            )
        val delays = FakeRegistrationDelayScheduler()
        val scheduler = scheduler(client, delays, jitterSource = { 0.5 })

        scheduler.register(manifest, "1.4.0", PermissionSnapshotContext())
        while (delays.hasPending()) delays.runNext()

        assertEquals(
            listOf(0L, 1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L),
            delays.scheduledDelays.map(Duration::toSeconds),
        )
        assertEquals(9, client.registrations)
        assertEquals(8, status.snapshot().manifestRetries)
        assertEquals(NOW, status.snapshot().lastManifestSuccessAt)
    }

    @Test
    fun `keeps retry jitter within the configured range`() {
        val lowDelays = FakeRegistrationDelayScheduler()
        val lowScheduler =
            scheduler(FakeRuntimeClient(retryableFailure()), lowDelays, jitterSource = { 0.0 })
        lowScheduler.register(manifest, "1.4.0", PermissionSnapshotContext())
        lowDelays.runNext()

        val highDelays = FakeRegistrationDelayScheduler()
        val highScheduler =
            scheduler(FakeRuntimeClient(retryableFailure()), highDelays, jitterSource = { 1.0 })
        highScheduler.register(manifest("plugin-agones"), "1.4.0", PermissionSnapshotContext())
        highDelays.runNext()

        assertEquals(Duration.ofMillis(800), lowDelays.scheduledDelays.last())
        assertEquals(Duration.ofMillis(1200), highDelays.scheduledDelays.last())
    }

    @Test
    fun `does not retry terminal failures and records their safe context`() {
        val client =
            FakeRuntimeClient(
                PermissionManifestRegistrationResult.TerminalFailure(
                    reason = "forbidden",
                    statusCode = 403,
                    requestId = "server-request",
                )
            )
        val delays = FakeRegistrationDelayScheduler()
        val scheduler = scheduler(client, delays)

        scheduler.register(manifest, "1.4.0", PermissionSnapshotContext())
        delays.runNext()

        assertFalse(delays.hasPending())
        assertEquals(1, client.registrations)
        assertEquals(1, status.snapshot().terminalManifestFailures)
        assertEquals(
            ManifestTerminalFailureStatus(
                source = "plugin-chat",
                statusCode = 403,
                requestId = "server-request",
                failedAt = NOW,
            ),
            status.snapshot().lastTerminalManifestFailure,
        )
    }

    @Test
    fun `cancels a scheduled retry during shutdown`() {
        val client = FakeRuntimeClient(retryableFailure())
        val delays = FakeRegistrationDelayScheduler()
        val scheduler = scheduler(client, delays)
        scheduler.register(manifest, "1.4.0", PermissionSnapshotContext())
        delays.runNext()
        assertTrue(delays.hasPending())

        scheduler.close()

        assertFalse(delays.hasPending())
        assertTrue(delays.closed)
    }

    private fun scheduler(
        client: PermissionRuntimeClient,
        delays: RegistrationDelayScheduler,
        jitterSource: () -> Double = { 0.5 },
    ): ManifestRegistrationScheduler =
        ManifestRegistrationScheduler(
            client = client,
            delayScheduler = delays,
            status = status,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            jitterSource = jitterSource,
        )

    private fun retryableFailure(): PermissionManifestRegistrationResult.RetryableFailure =
        PermissionManifestRegistrationResult.RetryableFailure(
            reason = "service_unavailable",
            statusCode = 503,
            requestId = "server-request",
        )

    private fun manifest(source: String): PermissionManifest =
        PermissionManifest(
            source = source,
            permissions =
                listOf(
                    PermissionManifestEntry(
                        key = "grounds.chat.staff",
                        label = "Staff chat",
                        description = "Allows access to staff chat.",
                        supportedScopes = listOf(PermissionManifestScope.GLOBAL),
                    )
                ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-26T18:00:00Z")
    }
}

private class FakeRuntimeClient(vararg results: PermissionManifestRegistrationResult) :
    PermissionRuntimeClient {
    private val results = ArrayDeque(results.toList())
    var registrations: Int = 0
        private set

    override fun fetchSnapshot(
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshot = error("Snapshot fetch is not used by this test")

    override fun registerManifest(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ): PermissionManifestRegistrationResult {
        registrations++
        return results.removeFirst()
    }
}

private class FakeRegistrationDelayScheduler : RegistrationDelayScheduler {
    private val scheduled = ArrayDeque<FakeScheduledRegistration>()
    val scheduledDelays = mutableListOf<Duration>()
    var closed: Boolean = false
        private set

    override fun schedule(delay: Duration, task: () -> Unit): ScheduledRegistration {
        scheduledDelays += delay
        return FakeScheduledRegistration(task).also(scheduled::addLast)
    }

    fun runNext() {
        val registration = scheduled.removeFirst()
        if (!registration.cancelled) registration.task()
    }

    fun hasPending(): Boolean = scheduled.any { !it.cancelled }

    override fun close() {
        closed = true
        scheduled.forEach(FakeScheduledRegistration::cancel)
    }
}

private class FakeScheduledRegistration(val task: () -> Unit) : ScheduledRegistration {
    var cancelled: Boolean = false
        private set

    override fun cancel() {
        cancelled = true
    }
}
