package gg.grounds.permissions.client

import java.net.URI
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PermissionServiceConfigTest {
    @Test
    fun `accepts absolute HTTP and HTTPS service URLs`() {
        val tokenFile = Path.of("/var/run/secrets/grounds/permissions-token")

        assertEquals(
            PermissionServiceConfig(URI("http://service-permissions-runtime:8080"), tokenFile),
            PermissionServiceConfig.parse(
                "http://service-permissions-runtime:8080",
                tokenFile.toString(),
            ),
        )
        assertEquals(
            PermissionServiceConfig(URI("https://permissions.internal/runtime"), tokenFile),
            PermissionServiceConfig.parse(
                "https://permissions.internal/runtime",
                tokenFile.toString(),
            ),
        )
    }

    @Test
    fun `rejects service URLs that cannot be used as credential-safe absolute bases`() {
        val invalidUrls =
            listOf(
                "permissions.internal:8080",
                "ftp://permissions.internal",
                "http://user:secret@permissions.internal",
                "http://permissions.internal?token=secret",
                "http://permissions.internal#runtime",
            )

        invalidUrls.forEach { serviceUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                PermissionServiceConfig.parse(serviceUrl, "/var/run/secrets/permissions-token")
            }
        }
    }

    @Test
    fun `rejects blank configuration values`() {
        assertThrows(IllegalArgumentException::class.java) {
            PermissionServiceConfig.parse(" ", "/var/run/secrets/permissions-token")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionServiceConfig.parse("https://permissions.internal", " ")
        }
    }
}
