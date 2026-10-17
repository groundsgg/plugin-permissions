package gg.grounds.permissions.minestom

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.client.SnapshotFailureReason
import gg.grounds.permissions.client.SnapshotUnavailableException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionSnapshotPrefetchesTest {
    private val playerId = UUID.randomUUID()
    private val executor = QueuedExecutor()
    private val requests = ConcurrentLinkedQueue<UUID>()

    @Test
    fun `a login after the prefetch uses it without a second request`() {
        val prefetches = prefetches()

        prefetches.prefetch(listOf(playerId), "match m1")
        executor.runAll()
        val login = prefetches.fetchForLogin(playerId)

        assertEquals(listOf(playerId), requests.toList())
        assertEquals(PrefetchOutcome.HIT, login.prefetch)
        assertEquals(playerId, login.snapshot.playerId)
    }

    @Test
    fun `a login during the prefetch waits for it instead of sending a second request`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prefetches =
            prefetches(executor = Executors.newVirtualThreadPerTaskExecutor()) { id ->
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                snapshot(id)
            }

        prefetches.prefetch(listOf(playerId), "match m1")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Executors.newVirtualThreadPerTaskExecutor().use { logins ->
            val login = logins.submit<LoginSnapshot> { prefetches.fetchForLogin(playerId) }
            release.countDown()

            assertEquals(PrefetchOutcome.HIT, login.get(5, TimeUnit.SECONDS).prefetch)
        }
        assertEquals(listOf(playerId), requests.toList())
    }

    @Test
    fun `a login whose prefetch is still queued fetches it once itself`() {
        val prefetches = prefetches()

        prefetches.prefetch(listOf(playerId), "match m1")
        val login = prefetches.fetchForLogin(playerId)
        executor.runAll()

        assertEquals(PrefetchOutcome.MISS, login.prefetch)
        assertEquals(listOf(playerId), requests.toList())
    }

    @Test
    fun `a failed prefetch leaves the login to fetch normally`() {
        var fail = true
        val prefetches = prefetches { id ->
            if (fail) throw SnapshotUnavailableException(SnapshotFailureReason.UNAVAILABLE)
            snapshot(id)
        }

        prefetches.prefetch(listOf(playerId), "match m1")
        executor.runAll()
        fail = false
        val login = prefetches.fetchForLogin(playerId)

        assertEquals(listOf(playerId, playerId), requests.toList())
        assertEquals(playerId, login.snapshot.playerId)
    }

    @Test
    fun `an invalidation between prefetch and login makes the login fetch again`() {
        var policyVersion = 1L
        val prefetches = prefetches { id -> snapshot(id, policyVersion = policyVersion) }

        prefetches.prefetch(listOf(playerId), "match m1")
        executor.runAll()
        policyVersion = 2
        prefetches.invalidate(playerId)
        val login = prefetches.fetchForLogin(playerId)

        assertEquals(listOf(playerId, playerId), requests.toList())
        assertEquals(2, login.snapshot.policyVersion)
    }

    @Test
    fun `an invalidation during the prefetch makes the login fetch again`() {
        val prefetches = prefetches()

        prefetches.prefetch(listOf(playerId), "match m1")
        prefetches.invalidate(playerId)
        executor.runAll()
        prefetches.fetchForLogin(playerId)

        assertEquals(listOf(playerId, playerId), requests.toList())
    }

    @Test
    fun `a prefetched snapshot past its refreshAfter is fetched again`() {
        val clock = MutableClock(NOW)
        val prefetches = prefetches(clock = clock)

        prefetches.prefetch(listOf(playerId), "match m1")
        executor.runAll()
        clock.now = NOW.plusSeconds(REFRESH_AFTER_SECONDS)
        val login = prefetches.fetchForLogin(playerId)

        assertEquals(PrefetchOutcome.MISS, login.prefetch)
        assertEquals(listOf(playerId, playerId), requests.toList())
    }

    @Test
    fun `a login without a prefetch fetches as before`() {
        val login = prefetches().fetchForLogin(playerId)

        assertEquals(PrefetchOutcome.NONE, login.prefetch)
        assertEquals(listOf(playerId), requests.toList())
    }

    @Test
    fun `skips players already online, already prefetching, or listed twice`() {
        val online = UUID.randomUUID()
        val prefetches = prefetches(onlinePlayerIds = { setOf(online) })

        prefetches.prefetch(listOf(playerId, playerId, online), "match m1")
        prefetches.prefetch(listOf(playerId), "match m1")
        executor.runAll()

        assertEquals(listOf(playerId), requests.toList())
    }

    @Test
    fun `a prefetch the executor rejects neither throws nor blocks the login`() {
        val prefetches =
            prefetches(executor = Executor { throw RejectedExecutionException("stopped") })

        prefetches.prefetch(listOf(playerId), "match m1")
        val login = prefetches.fetchForLogin(playerId)

        assertEquals(playerId, login.snapshot.playerId)
        assertEquals(listOf(playerId), requests.toList())
    }

    @Test
    fun `a full roster is fetched with bounded concurrency`() {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        val prefetches =
            prefetches(executor = pool) { id ->
                peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                Thread.sleep(20)
                running.decrementAndGet()
                snapshot(id)
            }
        val roster = List(16) { UUID.randomUUID() }

        prefetches.prefetch(roster, "match m1")
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(16, requests.size)
        assertTrue(peak.get() <= 4, "peak concurrency was ${peak.get()}")
    }

    @Test
    fun `loader logs in with the prefetched snapshot`() {
        val prefetches = prefetches()
        val snapshots = InMemoryPermissionSnapshots()
        val loader =
            MinestomPermissionSnapshotLoader(
                logger = RecordingLogger(),
                snapshots = snapshots,
                fetchForLogin = prefetches::fetchForLogin,
            )

        prefetches.prefetch(listOf(playerId), "match m1")
        executor.runAll()
        val result = loader.loadSnapshot(playerId)

        assertTrue(result.allowed)
        assertEquals(playerId, snapshots.get(playerId)?.playerId)
        assertEquals(1, requests.size)
    }

    private fun prefetches(
        executor: Executor = this.executor,
        clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
        onlinePlayerIds: () -> Set<UUID> = { emptySet() },
        fetch: (UUID) -> PermissionSnapshot = { id -> snapshot(id) },
    ): PermissionSnapshotPrefetches =
        PermissionSnapshotPrefetches(
            fetch = { id ->
                requests += id
                fetch(id)
            },
            executor = executor,
            onlinePlayerIds = onlinePlayerIds,
            clock = clock,
            logger = RecordingLogger(),
        )

    private fun snapshot(id: UUID, policyVersion: Long = 1): PermissionSnapshot =
        PermissionSnapshot(
            playerId = id,
            policyVersion = policyVersion,
            issuedAt = NOW,
            refreshAfter = NOW.plusSeconds(REFRESH_AFTER_SECONDS),
            expiresAt = NOW.plusSeconds(600),
            allowPatterns = emptyList(),
            denyPatterns = emptyList(),
            roleKeys = emptySet(),
            roleMetadata = emptyList(),
        )

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-10-17T16:40:00Z")
        private const val REFRESH_AFTER_SECONDS = 300L
    }
}
