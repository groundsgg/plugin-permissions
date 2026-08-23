package gg.grounds.permissions.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PaperPermissionsConfigTest {
    @Test
    fun `omitted server type remains nullable and explicit value is preserved`() {
        val base =
            mapOf(
                "PERMISSIONS_SERVICE_URL" to "http://permissions:8080",
                "PERMISSIONS_TOKEN_FILE" to "/var/run/token",
            )
        assertNull(requireNotNull(PaperPermissionsConfig.fromEnvironment(base)).context.serverType)
        assertEquals(
            "buildserver",
            requireNotNull(
                    PaperPermissionsConfig.fromEnvironment(
                        base + ("GROUNDS_PERMISSION_SERVER_TYPE" to "buildserver")
                    )
                )
                .context
                .serverType,
        )
    }
}
