package gg.grounds.permissions.velocity

import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantOrigin
import gg.grounds.permissions.PermissionGrantOriginKind
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level

class SnapshotDiskCacheTest {
    @TempDir lateinit var tempDir: java.nio.file.Path

    @Test
    fun `writes JSON and reads it after restart`() {
        val logger = RecordingLogger()
        val snapshot = snapshot()

        SnapshotDiskCache(logger, tempDir).write(snapshot)
        val loaded = SnapshotDiskCache(logger, tempDir).read(snapshot.playerId, NOW)

        assertEquals(snapshot, loaded)
        assertTrue(
            Files.readString(tempDir.resolve("${snapshot.playerId}.json")).contains("policyVersion")
        )
    }

    @Test
    fun `rejects hard expired snapshots`() {
        val logger = RecordingLogger()
        val snapshot = snapshot(expiresAt = NOW.minusSeconds(1))
        val cache = SnapshotDiskCache(logger, tempDir)

        cache.write(snapshot)

        assertNull(cache.read(snapshot.playerId, NOW))
        assertTrue(
            logger.events.any { it.level == Level.WARN && it.args.contains(snapshot.playerId) }
        )
    }

    @Test
    fun `returns no snapshot and logs player when cache is corrupt`() {
        val logger = RecordingLogger()
        val playerId = UUID.randomUUID()
        Files.createDirectories(tempDir)
        Files.writeString(tempDir.resolve("$playerId.json"), "{broken")

        val loaded = SnapshotDiskCache(logger, tempDir).read(playerId, NOW)

        assertNull(loaded)
        assertTrue(
            logger.events.any {
                it.level == Level.WARN &&
                    it.message ==
                        "Failed to read cached permission snapshot (playerId={}, path={})" &&
                    it.args.contains(playerId)
            }
        )
    }

    private fun snapshot(
        playerId: UUID = UUID.randomUUID(),
        expiresAt: Instant = NOW.plusSeconds(3600),
    ): PermissionSnapshot =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = 42,
            issuedAt = NOW.minusSeconds(60),
            refreshAfter = NOW.plusSeconds(300),
            expiresAt = expiresAt,
            allowPatterns =
                listOf(
                    PermissionGrant(
                        effect = PermissionEffect.ALLOW,
                        pattern = "grounds.chat",
                        scope = PermissionScope.global(),
                        source = PermissionGrantSource.ROLE,
                        origin =
                            PermissionGrantOrigin(
                                kind = PermissionGrantOriginKind.GROUP_MAPPING,
                                roleKey = "default",
                                mappingId = "mapping-1",
                                inheritedPath = listOf("member", "default"),
                            ),
                    )
                ),
            denyPatterns = emptyList(),
            roleKeys = setOf("default"),
            roleMetadata = listOf(RoleMetadata(key = "default", name = "Default")),
        )

    companion object {
        private val NOW: Instant = Instant.parse("2026-06-28T12:00:00Z")
    }
}
