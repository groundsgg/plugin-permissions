package gg.grounds.permissions.invalidation

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger

class PermissionSnapshotInvalidationCoordinatorTest {
    private val playerId = UUID.fromString("0f287625-2442-4f55-b928-d2f53fbdf575")
    private val oldSnapshot = snapshot(policyVersion = 1)
    private val newSnapshot = snapshot(policyVersion = 2)

    @Test
    fun `refreshes and replaces the snapshot for an online player`() {
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to oldSnapshot))
        val fetched = mutableListOf<UUID>()
        val coordinator =
            coordinator(snapshots, isOnline = { true }) {
                fetched += it
                newSnapshot
            }

        coordinator.invalidate(playerId)

        assertEquals(listOf(playerId), fetched)
        assertEquals(newSnapshot, snapshots.get(playerId))
    }

    @Test
    fun `does not request a snapshot for an offline player`() {
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to oldSnapshot))
        var fetchCount = 0
        val coordinator =
            coordinator(snapshots, isOnline = { false }) {
                fetchCount++
                newSnapshot
            }

        coordinator.invalidate(playerId)

        assertEquals(0, fetchCount)
        assertEquals(oldSnapshot, snapshots.get(playerId))
    }

    @Test
    fun `coalesces duplicate invalidations while a refresh is in flight`() {
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to oldSnapshot))
        val executor = QueuedExecutor()
        var fetchCount = 0
        val coordinator =
            PermissionSnapshotInvalidationCoordinator(
                snapshots = snapshots,
                isOnline = { true },
                fetchSnapshot = {
                    fetchCount++
                    newSnapshot
                },
                executor = executor,
                logger = NOPLogger.NOP_LOGGER,
            )

        coordinator.invalidate(playerId)
        coordinator.invalidate(playerId)
        executor.runNext()

        assertEquals(1, fetchCount)
        assertEquals(newSnapshot, snapshots.get(playerId))
    }

    @Test
    fun `keeps the previous snapshot when the forced refresh fails`() {
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to oldSnapshot))
        val coordinator = coordinator(snapshots, isOnline = { true }) { null }

        coordinator.invalidate(playerId)

        assertEquals(oldSnapshot, snapshots.get(playerId))
    }

    @Test
    fun `callback observes the committed refreshed snapshot`() {
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to oldSnapshot))
        var observed: PermissionSnapshot? = null
        val coordinator =
            PermissionSnapshotInvalidationCoordinator(
                snapshots,
                { true },
                { newSnapshot },
                Executor(Runnable::run),
                NOPLogger.NOP_LOGGER,
            ) {
                observed = snapshots.get(it)
            }
        coordinator.invalidate(playerId)
        assertEquals(newSnapshot, observed)
    }

    private fun coordinator(
        snapshots: InMemoryPermissionSnapshots,
        isOnline: (UUID) -> Boolean,
        fetchSnapshot: (UUID) -> PermissionSnapshot?,
    ): PermissionSnapshotInvalidationCoordinator =
        PermissionSnapshotInvalidationCoordinator(
            snapshots = snapshots,
            isOnline = isOnline,
            fetchSnapshot = fetchSnapshot,
            executor = Executor(Runnable::run),
            logger = NOPLogger.NOP_LOGGER,
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

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
