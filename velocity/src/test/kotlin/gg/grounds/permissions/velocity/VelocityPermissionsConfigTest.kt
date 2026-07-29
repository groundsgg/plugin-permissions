package gg.grounds.permissions.velocity

import java.net.URI
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VelocityPermissionsConfigTest {
    @Test
    fun `disables permissions integration when both REST settings are absent`() {
        assertNull(VelocityPermissionsConfig.fromEnvironment(emptyMap()))
    }

    @Test
    fun `parses complete REST configuration and runtime context`() {
        val config =
            VelocityPermissionsConfig.fromEnvironment(
                mapOf(
                    "PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080",
                    "PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token",
                    "GROUNDS_PERMISSION_SERVER_TYPE" to "velocity",
                    "GROUNDS_PERMISSION_SERVER_ID" to "proxy-1",
                    "GROUNDS_PERMISSION_ENVIRONMENT" to "stage",
                    "PERMISSIONS_REFRESH_INTERVAL_SECONDS" to "30",
                    "NATS_URL" to "nats://nats.nats.svc.cluster.local:4222",
                    "GROUNDS_TOKEN_FILE" to "/var/run/secrets/grounds/token",
                )
            )

        requireNotNull(config)
        assertEquals(URI("http://service-permissions-runtime:8080"), config.service.serviceUri)
        assertEquals(
            Path.of("/var/run/secrets/grounds/permissions-token"),
            config.service.tokenFile,
        )
        assertEquals("velocity", config.context.serverType)
        assertEquals("proxy-1", config.context.serverId)
        assertEquals("stage", config.context.environment)
        assertEquals(30, config.refreshIntervalSeconds)
        assertNotNull(config.snapshotInvalidations)
        assertEquals(
            "nats://nats.nats.svc.cluster.local:4222",
            config.snapshotInvalidations?.natsUrl,
        )
        assertEquals(
            Path.of("/var/run/secrets/grounds/token"),
            config.snapshotInvalidations?.tokenFile,
        )
    }

    @Test
    fun `keeps REST integration enabled when invalidation transport is absent`() {
        val config =
            VelocityPermissionsConfig.fromEnvironment(
                mapOf(
                    "PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080",
                    "PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token",
                )
            )

        requireNotNull(config)
        assertNull(config.snapshotInvalidations)
    }

    @Test
    fun `fails startup when only one REST setting is present`() {
        listOf(
                mapOf("PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080"),
                mapOf("PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token"),
            )
            .forEach { environment ->
                assertThrows(IllegalStateException::class.java) {
                    VelocityPermissionsConfig.fromEnvironment(environment)
                }
            }
    }
}
