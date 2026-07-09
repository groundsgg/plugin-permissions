package gg.grounds.permissions.minestom

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MinestomPermissionSnapshotLoaderTest {
    @Test
    fun `backend success allows login and stores snapshot`() {
        val playerId = UUID.randomUUID()
        val snapshot = snapshot(playerId)
        val snapshots = InMemoryPermissionSnapshots()
        val loader =
            loader(
                snapshots = snapshots,
                client = FakeClient(PermissionSnapshotFetchResult.Success(snapshot)),
            )

        val result = loader.loadSnapshot(playerId, "Alex")

        assertTrue(result.allowed)
        assertEquals(snapshot, result.snapshot)
        assertEquals(snapshot, snapshots.get(playerId))
    }

    @Test
    fun `backend failure with valid memory cache allows login`() {
        val playerId = UUID.randomUUID()
        val snapshot = snapshot(playerId)
        val snapshots = InMemoryPermissionSnapshots(mapOf(playerId to snapshot))
        val loader =
            loader(
                snapshots = snapshots,
                client = FakeClient(PermissionSnapshotFetchResult.Unavailable("unavailable")),
            )

        val result = loader.loadSnapshot(playerId, "Alex")

        assertTrue(result.allowed)
        assertEquals(snapshot, result.snapshot)
    }

    @Test
    fun `backend failure without cache denies login`() {
        val playerId = UUID.randomUUID()
        val loader =
            loader(client = FakeClient(PermissionSnapshotFetchResult.Unavailable("unavailable")))

        val result = loader.loadSnapshot(playerId, "Alex")

        assertFalse(result.allowed)
    }

    @Test
    fun `backend failure with expired cache denies login`() {
        val playerId = UUID.randomUUID()
        val snapshots =
            InMemoryPermissionSnapshots(mapOf(playerId to snapshot(playerId, NOW.minusSeconds(1))))
        val loader =
            loader(
                snapshots = snapshots,
                client = FakeClient(PermissionSnapshotFetchResult.Unavailable("unavailable")),
            )

        val result = loader.loadSnapshot(playerId, "Alex")

        assertFalse(result.allowed)
    }

    @Test
    fun `passes configured context to snapshot client`() {
        val playerId = UUID.randomUUID()
        val client = FakeClient(PermissionSnapshotFetchResult.Success(snapshot(playerId)))
        val context = PermissionSnapshotContext(serverType = "arena", serverId = "arena-1")
        val loader = loader(client = client, context = context)

        loader.loadSnapshot(playerId, "Alex")

        assertSame(context, client.lastContext)
    }

    private fun loader(
        snapshots: InMemoryPermissionSnapshots = InMemoryPermissionSnapshots(),
        client: PermissionSnapshotClient,
        context: PermissionSnapshotContext = PermissionSnapshotContext(serverType = "arena"),
    ): MinestomPermissionSnapshotLoader =
        MinestomPermissionSnapshotLoader(
            logger = RecordingLogger(),
            snapshots = snapshots,
            client = client,
            context = context,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun snapshot(
        playerId: UUID,
        expiresAt: Instant = NOW.plusSeconds(3600),
    ): PermissionSnapshot =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = 1,
            issuedAt = NOW.minusSeconds(30),
            refreshAfter = NOW.plusSeconds(300),
            expiresAt = expiresAt,
            allowPatterns = emptyList(),
            denyPatterns = emptyList(),
            roleKeys = emptySet(),
            roleMetadata = emptyList(),
        )

    companion object {
        private val NOW: Instant = Instant.parse("2026-06-28T12:00:00Z")
    }
}

private class FakeClient(private val result: PermissionSnapshotFetchResult) :
    PermissionSnapshotClient {
    var lastContext: PermissionSnapshotContext? = null

    override fun fetchSnapshot(
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshotFetchResult {
        lastContext = context
        return result
    }
}
