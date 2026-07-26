package gg.grounds.permissions.minestom

import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.runtime.ActiveGroundsModuleProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PermissionManifestDiscoveryTest {
    @Test
    fun `collects manifests from active providers while skipping missing and malformed resources`() {
        val activeProviders =
            listOf(
                provider("active-one", "1.0.0", validManifestClassLoader("active-one")),
                provider("without-manifest", "1.0.0", ClassLoader.getSystemClassLoader()),
                provider("malformed", "1.0.0", malformedManifestClassLoader()),
                provider("active-two", "2.0.0", validManifestClassLoader("active-two")),
            )
        val collection = collectActivePermissionManifests(activeProviders)

        assertEquals(
            listOf("active-one", "active-two"),
            collection.manifests.map { it.manifest.source },
        )
        assertEquals(listOf("malformed"), collection.failures.map { it.origin.id })
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
}
