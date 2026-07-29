package gg.grounds.permissions.invalidation

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PermissionSnapshotInvalidationTest {
    private val playerId = UUID.fromString("0f287625-2442-4f55-b928-d2f53fbdf575")
    private val codec = PermissionSnapshotInvalidationCodec()

    @Test
    fun `decodes the exact version one payload`() {
        val payload = """{"schemaVersion":1,"playerId":"0f287625-2442-4f55-b928-d2f53fbdf575"}"""

        assertEquals(
            PermissionSnapshotInvalidation(1, playerId),
            codec.decode(payload.toByteArray()),
        )
    }

    @Test
    fun `rejects malformed json with a stable reason`() {
        assertNull(codec.decode("{".toByteArray()))
        assertEquals("malformed_json", codec.rejectionReason("{".toByteArray()))
    }

    @Test
    fun `rejects unsupported schemas with a stable reason`() {
        val payload = """{"schemaVersion":2,"playerId":"0f287625-2442-4f55-b928-d2f53fbdf575"}"""

        assertNull(codec.decode(payload.toByteArray()))
        assertEquals("unsupported_schema_version", codec.rejectionReason(payload.toByteArray()))
    }

    @Test
    fun `rejects malformed player ids with a stable reason`() {
        val payload = """{"schemaVersion":1,"playerId":"not-a-uuid"}"""

        assertNull(codec.decode(payload.toByteArray()))
        assertEquals("malformed_player_id", codec.rejectionReason(payload.toByteArray()))
    }

    @Test
    fun `rejects noncanonical player ids accepted by the uuid parser`() {
        val payload = """{"schemaVersion":1,"playerId":"0-0-0-0-0"}"""

        assertNull(codec.decode(payload.toByteArray()))
        assertEquals("malformed_player_id", codec.rejectionReason(payload.toByteArray()))
    }

    @Test
    fun `rejects payloads with fields outside the version one contract`() {
        val payload =
            """{"schemaVersion":1,"playerId":"0f287625-2442-4f55-b928-d2f53fbdf575","role":"admin"}"""

        assertNull(codec.decode(payload.toByteArray()))
        assertEquals("unexpected_fields", codec.rejectionReason(payload.toByteArray()))
    }

    @Test
    fun `disables invalidations when nats url is absent`() {
        assertNull(PermissionSnapshotInvalidationConfig.fromEnvironment(emptyMap()))
        assertNull(PermissionSnapshotInvalidationConfig.fromEnvironment(mapOf("NATS_URL" to "   ")))
    }

    @Test
    fun `reads optional token file and default subject from environment`() {
        assertEquals(
            PermissionSnapshotInvalidationConfig(
                natsUrl = "nats://nats.internal:4222",
                tokenFile = Path.of("/var/run/secrets/grounds/token"),
                subject = "permissions.snapshot.invalidated",
            ),
            PermissionSnapshotInvalidationConfig.fromEnvironment(
                mapOf(
                    "NATS_URL" to "nats://nats.internal:4222",
                    "GROUNDS_TOKEN_FILE" to "/var/run/secrets/grounds/token",
                )
            ),
        )
    }

    @Test
    fun `uses an explicit subject override`() {
        assertEquals(
            "custom.permissions.invalidated",
            PermissionSnapshotInvalidationConfig.fromEnvironment(
                    mapOf(
                        "NATS_URL" to "nats://localhost:4222",
                        "PERMISSIONS_SNAPSHOT_INVALIDATIONS_SUBJECT" to
                            "custom.permissions.invalidated",
                    )
                )
                ?.subject,
        )
    }
}
