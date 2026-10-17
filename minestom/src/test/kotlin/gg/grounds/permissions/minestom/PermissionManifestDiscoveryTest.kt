package gg.grounds.permissions.minestom

import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.runtime.ActiveGroundsModuleProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PermissionManifestDiscoveryTest {
    @TempDir lateinit var resourceDirectory: Path

    @Test
    fun `discovers a host manifest without active module providers`(@TempDir directory: Path) {
        val resource = directory.resolve("META-INF/grounds/permissions/game-bedwars.json")
        Files.createDirectories(resource.parent)
        Files.writeString(
            resource,
            """{"source":"game-bedwars","sourceVersion":"1.2.3","permissions":[{"key":"anticheat.broadcast","label":"Receive alerts","description":"Receive anticheat violation alerts.","supportedScopes":["GLOBAL"]}]}""",
        )
        Files.writeString(
            directory.resolve("META-INF/grounds/permissions.index"),
            "META-INF/grounds/permissions/game-bedwars.json\n",
        )
        URLClassLoader(arrayOf(directory.toUri().toURL()), null).use { loader ->
            val collection = collectActivePermissionManifests(emptyList(), loader)
            assertEquals("game-bedwars", collection.manifests.single().manifest.source)
            assertEquals("1.2.3", collection.manifests.single().sourceVersion)
        }
    }

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

    private fun validManifestClassLoader(source: String): ClassLoader {
        val root = resourceDirectory.resolve(source)
        val resource = root.resolve(PermissionManifest.RESOURCE_PATH)
        Files.createDirectories(resource.parent)
        Files.writeString(
            resource,
            """{"source":"$source","permissions":[{"key":"grounds.$source.read","label":"Read","description":"Allows reading.","supportedScopes":["GLOBAL"]}]}""",
        )
        return URLClassLoader(arrayOf(root.toUri().toURL()), null)
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
