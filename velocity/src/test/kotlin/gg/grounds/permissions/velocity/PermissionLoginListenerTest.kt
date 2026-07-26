package gg.grounds.permissions.velocity

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionSnapshot
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionLoginListenerTest {
    @Test
    fun `backend success allows login and stores snapshot`() {
        val playerId = UUID.randomUUID()
        val snapshot = snapshot(playerId)
        val otherSnapshot = snapshot(UUID.randomUUID())
        val memory = InMemoryPermissionSnapshots()
        memory.put(otherSnapshot)
        val listener = listener(memory = memory, client = FakeClient { snapshot })

        val result = listener.loadSnapshot(playerId)

        assertTrue(result.allowed)
        assertEquals(snapshot, result.snapshot)
        assertEquals(snapshot, memory.get(playerId))
        assertEquals(otherSnapshot, memory.get(otherSnapshot.playerId))
    }

    @Test
    fun `runtime client failure without a valid common cache denies login`() {
        val playerId = UUID.randomUUID()
        val listener =
            listener(
                client =
                    FakeClient {
                        throw SnapshotUnavailableException(SnapshotFailureReason.UNAVAILABLE)
                    }
            )

        val result = listener.loadSnapshot(playerId)

        assertFalse(result.allowed)
    }

    @Test
    fun `passes configured context to snapshot client`() {
        val playerId = UUID.randomUUID()
        val client = FakeClient { snapshot(playerId) }
        val context = PermissionSnapshotContext(serverType = "lobby", serverId = "proxy-1")
        val listener = listener(client = client, context = context)

        listener.loadSnapshot(playerId)

        assertSame(context, client.lastContext)
    }

    private fun listener(
        memory: InMemoryPermissionSnapshots = InMemoryPermissionSnapshots(),
        client: PermissionRuntimeClient,
        context: PermissionSnapshotContext = PermissionSnapshotContext(serverType = "lobby"),
    ): PermissionLoginListener =
        PermissionLoginListener(
            logger = RecordingLogger(),
            snapshots = memory,
            client = client,
            context = context,
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

private class FakeClient(private val fetch: () -> PermissionSnapshot) : PermissionRuntimeClient {
    var lastContext: PermissionSnapshotContext? = null

    override fun fetchSnapshot(
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshot {
        lastContext = context
        return fetch()
    }

    override fun registerManifest(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ): PermissionManifestRegistrationResult =
        error("Manifest registration is not used by this test")
}
