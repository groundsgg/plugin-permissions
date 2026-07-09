package gg.grounds.permissions.velocity

import gg.grounds.permissions.catalog.CollectedPermissionManifest
import gg.grounds.permissions.catalog.ManifestOrigin
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.catalog.PermissionManifestEntry
import gg.grounds.permissions.catalog.PermissionManifestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionManifestRegistrarTest {
    @Test
    fun `retries unavailable registration five times before reporting the final failure`() {
        val client = UnavailableCatalogClient("catalog unavailable")
        val sleeps = mutableListOf<Long>()
        val collected =
            CollectedPermissionManifest(
                origin = ManifestOrigin("test-plugin", "1.0.0", javaClass.classLoader),
                manifest =
                    PermissionManifest(
                        source = "test-plugin",
                        permissions =
                            listOf(
                                PermissionManifestEntry(
                                    key = "grounds.command.test",
                                    label = "Use test command",
                                    description = "Allows using the test command.",
                                    supportedScopes = listOf(PermissionManifestScope.GLOBAL),
                                )
                            ),
                    ),
            )

        val report =
            PermissionManifestRegistrar(
                    client = client,
                    context = PermissionSnapshotContext(serverType = "proxy", serverId = "proxy-1"),
                    sleep = sleeps::add,
                )
                .register(listOf(collected))

        assertTrue(report.registered.isEmpty())
        assertEquals(5, client.attempts)
        assertEquals(listOf(250L, 500L, 750L, 1_000L), sleeps)
        assertEquals(1, report.failures.size)
        assertEquals(collected, report.failures.single().collected)
        assertEquals(5, report.failures.single().attempts)
        assertEquals("catalog unavailable", report.failures.single().reason)
    }

    private class UnavailableCatalogClient(private val reason: String) : PermissionCatalogClient {
        var attempts = 0

        override fun register(
            manifest: PermissionManifest,
            sourceVersion: String,
            context: PermissionSnapshotContext,
        ): PermissionManifestRegistrationResult {
            attempts += 1
            return PermissionManifestRegistrationResult.Unavailable(reason)
        }
    }
}
