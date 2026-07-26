package gg.grounds.permissions.client

import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SnapshotCacheTest {
    private val playerId = UUID.fromString("c5115183-46e6-4458-b15a-c89643c1a91e")
    private val now = Instant.parse("2026-07-26T18:00:00Z")

    @Test
    fun `returns a cached snapshot only while it is unexpired`() {
        val cache = SnapshotCache()
        val snapshot = snapshot(expiresAt = now.plusSeconds(1))
        cache.put(snapshot)

        assertEquals(snapshot, cache.valid(playerId, now))
        assertNull(cache.valid(playerId, snapshot.expiresAt))
        assertNull(cache.valid(playerId, now))
    }

    @Test
    fun `stores an immutable copy of snapshot collections`() {
        val allowPatterns = mutableListOf<PermissionGrant>()
        val roleKeys = mutableSetOf("member")
        val roleMetadata = mutableListOf(RoleMetadata(key = "member", name = "Member"))
        val snapshot =
            snapshot()
                .copy(
                    allowPatterns = allowPatterns,
                    roleKeys = roleKeys,
                    roleMetadata = roleMetadata,
                )
        val cache = SnapshotCache()
        cache.put(snapshot)

        allowPatterns.clear()
        roleKeys.clear()
        roleMetadata.clear()

        assertEquals(setOf("member"), cache.valid(playerId, now)?.roleKeys)
        assertEquals(
            listOf(RoleMetadata(key = "member", name = "Member")),
            cache.valid(playerId, now)?.roleMetadata,
        )
    }

    private fun snapshot(expiresAt: Instant = now.plusSeconds(300)): PermissionSnapshot =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = 4,
            issuedAt = now.minusSeconds(30),
            refreshAfter = now.plusSeconds(30),
            expiresAt = expiresAt,
            allowPatterns = emptyList(),
            denyPatterns = emptyList(),
            roleKeys = emptySet(),
            roleMetadata = emptyList(),
        )
}
