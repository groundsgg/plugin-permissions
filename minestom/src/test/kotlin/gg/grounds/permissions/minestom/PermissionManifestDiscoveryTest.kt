package gg.grounds.permissions.minestom

import gg.grounds.permissions.catalog.ManifestOrigin
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.runtime.ActiveGroundsModuleProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionManifestDiscoveryTest {
    @Test
    fun `collects and registers manifests from active providers while skipping missing and malformed resources`() {
        val activeProviders =
            listOf(
                provider("active-one", "1.0.0", validManifestClassLoader("active-one")),
                provider("without-manifest", "1.0.0", ClassLoader.getSystemClassLoader()),
                provider("malformed", "1.0.0", malformedManifestClassLoader()),
                provider("active-two", "2.0.0", validManifestClassLoader("active-two")),
            )
        val client = RecordingCatalogClient()
        val collection = collectActivePermissionManifests(activeProviders)

        assertEquals(
            listOf("active-one", "active-two"),
            collection.manifests.map { it.manifest.source },
        )
        assertEquals(listOf("malformed"), collection.failures.map { it.origin.id })

        assertDoesNotThrow {
            MinestomPermissionManifestRegistrar(
                    client = client,
                    context = PermissionSnapshotContext(serverType = "arena", serverId = "arena-1"),
                    sleep = {},
                )
                .register(collection.manifests)
        }

        assertEquals(listOf("active-one", "active-two"), client.registeredSources)
    }

    @Test
    fun `retries catalog failures five times and reports the final failure without throwing`() {
        val client = UnavailableCatalogClient("unavailable")
        val sleeps = mutableListOf<Long>()
        val manifest =
            PermissionManifest(
                source = "active-module",
                permissions =
                    listOf(
                        gg.grounds.permissions.catalog.PermissionManifestEntry(
                            key = "grounds.permissions.read",
                            label = "Read permissions",
                            description = "Allows reading permission state.",
                            supportedScopes =
                                listOf(
                                    gg.grounds.permissions.catalog.PermissionManifestScope.GLOBAL
                                ),
                        )
                    ),
            )

        val report =
            assertDoesNotThrow<MinestomPermissionManifestRegistrationReport> {
                MinestomPermissionManifestRegistrar(
                        client = client,
                        context = PermissionSnapshotContext(serverType = "arena"),
                        sleep = sleeps::add,
                    )
                    .register(
                        listOf(
                            gg.grounds.permissions.catalog.CollectedPermissionManifest(
                                origin =
                                    ManifestOrigin(
                                        id = "active-module",
                                        version = "1.0.0",
                                        classLoader = javaClass.classLoader,
                                    ),
                                manifest = manifest,
                            )
                        )
                    )
            }

        assertEquals(5, client.attempts)
        assertEquals(listOf(250L, 500L, 750L, 1_000L), sleeps)
        assertTrue(report.registered.isEmpty())
        assertEquals(1, report.failures.size)
        assertEquals("unavailable", report.failures.single().reason)
    }

    private fun provider(
        id: String,
        version: String,
        classLoader: ClassLoader,
    ): ActiveGroundsModuleProvider =
        ActiveGroundsModuleProvider(id = id, version = version, classLoader = classLoader)

    private fun validManifestClassLoader(source: String): ClassLoader =
        object : ClassLoader() {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name == PermissionManifest.RESOURCE_PATH) {
                    ByteArrayInputStream(
                        """
                        {
                          "source": "$source",
                          "permissions": [
                            { "key": "grounds.$source.read", "label": "Read", "description": "Allows reading.", "supportedScopes": ["GLOBAL"] }
                          ]
                        }
                        """
                            .trimIndent()
                            .encodeToByteArray()
                    )
                } else {
                    null
                }
        }

    private fun malformedManifestClassLoader(): ClassLoader =
        object : ClassLoader() {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name == PermissionManifest.RESOURCE_PATH) {
                    ByteArrayInputStream("not-json".encodeToByteArray())
                } else {
                    null
                }
        }

    private class RecordingCatalogClient : PermissionCatalogClient {
        val registeredSources = mutableListOf<String>()

        override fun register(
            manifest: PermissionManifest,
            sourceVersion: String,
            context: PermissionSnapshotContext,
        ): PermissionManifestRegistrationResult {
            registeredSources += manifest.source
            return PermissionManifestRegistrationResult.Accepted
        }
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
