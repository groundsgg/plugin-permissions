package gg.grounds.permissions.minestom

import java.net.URI
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MinestomPermissionsConfigTest {
    @Test
    fun `disables permissions integration when both REST settings are absent`() {
        assertNull(
            MinestomPermissionsConfig.fromEnvironment(
                environment = emptyMap(),
                fallbackServerType = "minestom",
            )
        )
    }

    @Test
    fun `parses complete REST configuration and runtime context`() {
        val config =
            MinestomPermissionsConfig.fromEnvironment(
                environment =
                    mapOf(
                        "PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080",
                        "PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token",
                        "GROUNDS_PERMISSION_SERVER_TYPE" to "lobby",
                        "GROUNDS_PERMISSION_SERVER_ID" to "lobby-1",
                        "GROUNDS_PERMISSION_ENVIRONMENT" to "stage",
                        "PERMISSIONS_REFRESH_INTERVAL_SECONDS" to "30",
                    ),
                fallbackServerType = "minestom",
            )

        requireNotNull(config)
        assertEquals(URI("http://service-permissions-runtime:8080"), config.service.serviceUri)
        assertEquals(
            Path.of("/var/run/secrets/grounds/permissions-token"),
            config.service.tokenFile,
        )
        assertEquals("lobby", config.context.serverType)
        assertEquals("lobby-1", config.context.serverId)
        assertEquals("stage", config.context.environment)
        assertEquals(30, config.refreshIntervalSeconds)
    }

    @Test
    fun `fails startup when only one REST setting is present`() {
        listOf(
                mapOf("PERMISSIONS_SERVICE_URL" to "http://service-permissions-runtime:8080"),
                mapOf("PERMISSIONS_TOKEN_FILE" to "/var/run/secrets/grounds/permissions-token"),
            )
            .forEach { environment ->
                assertThrows(IllegalStateException::class.java) {
                    MinestomPermissionsConfig.fromEnvironment(environment, "minestom")
                }
            }
    }
}
