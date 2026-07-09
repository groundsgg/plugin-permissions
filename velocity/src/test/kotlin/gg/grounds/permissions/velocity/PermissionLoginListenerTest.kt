package gg.grounds.permissions.velocity

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
import org.junit.jupiter.api.io.TempDir

class PermissionLoginListenerTest {
    @TempDir lateinit var tempDir: java.nio.file.Path

    @Test
    fun `backend success allows login and stores snapshot`() {
        val playerId = UUID.randomUUID()
        val snapshot = snapshot(playerId)
        val otherSnapshot = snapshot(UUID.randomUUID())
        val memory = InMemoryPermissionSnapshots()
        memory.put(otherSnapshot)
        val listener =
            listener(
                memory = memory,
                client = FakeClient(PermissionSnapshotFetchResult.Success(snapshot)),
            )

        val result = listener.loadSnapshot(playerId, "Alex")

        assertTrue(result.allowed)
        assertEquals(snapshot, result.snapshot)
        assertEquals(snapshot, memory.get(playerId))
        assertEquals(otherSnapshot, memory.get(otherSnapshot.playerId))
    }

    @Test
    fun `backend failure with valid cache allows login`() {
        val playerId = UUID.randomUUID()
        val snapshot = snapshot(playerId)
        val memory = InMemoryPermissionSnapshots()
        val cache = SnapshotDiskCache(RecordingLogger(), tempDir)
        cache.write(snapshot)
        val listener =
            listener(
                memory = memory,
                cache = cache,
                client = FakeClient(PermissionSnapshotFetchResult.Unavailable("unavailable")),
            )

        val result = listener.loadSnapshot(playerId, "Alex")

        assertTrue(result.allowed)
        assertEquals(snapshot, result.snapshot)
        assertEquals(snapshot, memory.get(playerId))
    }

    @Test
    fun `backend failure without cache denies login`() {
        val playerId = UUID.randomUUID()
        val listener =
            listener(client = FakeClient(PermissionSnapshotFetchResult.Unavailable("unavailable")))

        val result = listener.loadSnapshot(playerId, "Alex")

        assertFalse(result.allowed)
    }

    @Test
    fun `backend failure with expired cache denies login`() {
        val playerId = UUID.randomUUID()
        val cache = SnapshotDiskCache(RecordingLogger(), tempDir)
        cache.write(snapshot(playerId, expiresAt = NOW.minusSeconds(1)))
        val listener =
            listener(
                cache = cache,
                client = FakeClient(PermissionSnapshotFetchResult.Unavailable("unavailable")),
            )

        val result = listener.loadSnapshot(playerId, "Alex")

        assertFalse(result.allowed)
    }

    @Test
    fun `passes configured context to snapshot client`() {
        val playerId = UUID.randomUUID()
        val client = FakeClient(PermissionSnapshotFetchResult.Success(snapshot(playerId)))
        val context = PermissionSnapshotContext(serverType = "lobby", serverId = "proxy-1")
        val listener = listener(client = client, context = context)

        listener.loadSnapshot(playerId, "Alex")

        assertSame(context, client.lastContext)
    }

    private fun listener(
        memory: InMemoryPermissionSnapshots = InMemoryPermissionSnapshots(),
        cache: SnapshotDiskCache = SnapshotDiskCache(RecordingLogger(), tempDir),
        client: PermissionSnapshotClient,
        context: PermissionSnapshotContext = PermissionSnapshotContext(serverType = "lobby"),
    ): PermissionLoginListener =
        PermissionLoginListener(
            logger = RecordingLogger(),
            snapshots = memory,
            cache = cache,
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
