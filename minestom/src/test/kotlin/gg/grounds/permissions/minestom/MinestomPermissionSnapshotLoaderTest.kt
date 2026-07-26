package gg.grounds.permissions.minestom

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

class MinestomPermissionSnapshotLoaderTest {
    @Test
    fun `backend success allows login and stores snapshot`() {
        val playerId = UUID.randomUUID()
        val snapshot = snapshot(playerId)
        val snapshots = InMemoryPermissionSnapshots()
        val loader = loader(snapshots = snapshots, client = FakeClient { snapshot })

        val result = loader.loadSnapshot(playerId)

        assertTrue(result.allowed)
        assertEquals(snapshot, result.snapshot)
        assertEquals(snapshot, snapshots.get(playerId))
    }

    @Test
    fun `runtime client failure without a valid common cache denies login`() {
        val playerId = UUID.randomUUID()
        val loader =
            loader(
                client =
                    FakeClient {
                        throw SnapshotUnavailableException(SnapshotFailureReason.UNAVAILABLE)
                    }
            )

        val result = loader.loadSnapshot(playerId)

        assertFalse(result.allowed)
    }

    @Test
    fun `passes configured context to snapshot client`() {
        val playerId = UUID.randomUUID()
        val client = FakeClient { snapshot(playerId) }
        val context = PermissionSnapshotContext(serverType = "arena", serverId = "arena-1")
        val loader = loader(client = client, context = context)

        loader.loadSnapshot(playerId)

        assertSame(context, client.lastContext)
    }

    private fun loader(
        snapshots: InMemoryPermissionSnapshots = InMemoryPermissionSnapshots(),
        client: PermissionRuntimeClient,
        context: PermissionSnapshotContext = PermissionSnapshotContext(serverType = "arena"),
    ): MinestomPermissionSnapshotLoader =
        MinestomPermissionSnapshotLoader(
            logger = RecordingLogger(),
            snapshots = snapshots,
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
