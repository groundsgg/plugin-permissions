package gg.grounds.permissions

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PermissionSnapshotRefreshSweepTest {
    private val now = Instant.parse("2026-06-28T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val playerId = UUID.fromString("f39f6265-f481-4c90-8dd8-4d334b82f937")

    @Test
    fun refetchesSnapshotPastRefreshAfterForOnlinePlayer() {
        val stale = snapshot(refreshAfter = now.minusSeconds(1))
        val refreshed = snapshot(policyVersion = 2)
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to stale))
        val sweep = sweep(snapshots, online = setOf(playerId), fetch = { refreshed })

        sweep.run()

        assertEquals(refreshed, snapshots.get(playerId))
    }

    @Test
    fun fetchesSnapshotForOnlinePlayerWithNoExistingSnapshot() {
        val fetched = snapshot()
        val snapshots = InMemoryPermissionSnapshots()
        val sweep = sweep(snapshots, online = setOf(playerId), fetch = { fetched })

        sweep.run()

        assertEquals(fetched, snapshots.get(playerId))
    }

    @Test
    fun leavesSnapshotBeforeRefreshAfterUntouched() {
        val fresh = snapshot(refreshAfter = now.plusSeconds(60))
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to fresh))
        val sweep = sweep(snapshots, online = setOf(playerId), fetch = { fail() })

        sweep.run()

        assertEquals(fresh, snapshots.get(playerId))
    }

    @Test
    fun keepsExistingSnapshotWhenFetchFailsForOnlinePlayer() {
        val stale = snapshot(refreshAfter = now.minusSeconds(1))
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to stale))
        val sweep = sweep(snapshots, online = setOf(playerId), fetch = { null })

        sweep.run()

        assertEquals(stale, snapshots.get(playerId))
    }

    @Test
    fun keepsOfflinePlayerWithUnexpiredSnapshot() {
        val stillValid = snapshot(expiresAt = now.plusSeconds(60))
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to stillValid))
        val sweep = sweep(snapshots, online = emptySet(), fetch = { fail() })

        sweep.run()

        assertEquals(stillValid, snapshots.get(playerId))
    }

    @Test
    fun dropsOfflinePlayerWithExpiredSnapshot() {
        val expired = snapshot(expiresAt = now.minusSeconds(1))
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to expired))
        val sweep = sweep(snapshots, online = emptySet(), fetch = { fail() })

        sweep.run()

        assertNull(snapshots.get(playerId))
    }

    @Test
    fun keepsSnapshotWrittenByALoginThatRacedTheSweep() {
        val latecomer = UUID.fromString("b1a2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
        val stale = snapshot(refreshAfter = now.minusSeconds(1))
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to stale))
        val sweep =
            sweep(
                snapshots,
                online = setOf(playerId),
                fetch = {
                    // The login lands mid-sweep: after the sweep sampled the online players and the
                    // current map, but before it writes its result back.
                    snapshots.put(snapshot(playerId = latecomer))
                    snapshot(policyVersion = 2)
                },
            )

        sweep.run()

        assertNotNull(snapshots.get(latecomer))
    }

    private fun fail(): Nothing = throw AssertionError("fetchSnapshot should not be called")

    private fun sweep(
        snapshots: InMemoryPermissionSnapshots,
        online: Set<UUID>,
        fetch: (UUID) -> PermissionSnapshot?,
    ): PermissionSnapshotRefreshSweep =
        PermissionSnapshotRefreshSweep(
            snapshots = snapshots,
            onlinePlayerIds = { online },
            fetchSnapshot = fetch,
            clock = clock,
        )

    private fun snapshot(
        policyVersion: Long = 1,
        refreshAfter: Instant = now.plusSeconds(300),
        expiresAt: Instant = now.plusSeconds(600),
        playerId: UUID = this.playerId,
    ): PermissionSnapshot =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = policyVersion,
            issuedAt = now.minusSeconds(30),
            refreshAfter = refreshAfter,
            expiresAt = expiresAt,
            allowPatterns = emptyList(),
            denyPatterns = emptyList(),
            roleKeys = emptySet(),
            roleMetadata = emptyList(),
        )
}
