package gg.grounds.permissions.velocity

import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.plugin.PluginDescription
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.catalog.PermissionManifestCollector
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PermissionManifestDiscoveryTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `collects manifests from active plugin instances and skips plugins without one`() {
        val first = manifestInstance("first-plugin")
        val second = manifestInstance("second-plugin")
        val containers =
            listOf(
                pluginContainer("first-plugin", "1.0.0", first),
                pluginContainer("without-instance", "1.0.0", null),
                pluginContainer("second-plugin", "2.0.0", second),
            )
        val origins = discoverPermissionManifestOrigins(containers)
        val manifests = PermissionManifestCollector().collect(origins).manifests

        assertEquals(listOf("first-plugin", "second-plugin"), manifests.map { it.manifest.source })
        assertEquals(listOf("1.0.0", "2.0.0"), manifests.map { it.origin.version })
    }

    private fun pluginContainer(id: String, version: String, instance: Any?): PluginContainer {
        val description =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PluginDescription::class.java)) {
                _,
                method,
                _ ->
                when (method.name) {
                    "getId" -> id
                    "getVersion" -> Optional.of(version)
                    else -> error("Unexpected plugin description method: ${method.name}")
                }
            } as PluginDescription
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(PluginContainer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getDescription" -> description
                "getInstance" -> Optional.ofNullable(instance)
                else -> error("Unexpected plugin container method: ${method.name}")
            }
        } as PluginContainer
    }

    private fun manifestInstance(source: String): Any =
        Proxy.newProxyInstance(ManifestClassLoader(source), arrayOf(Runnable::class.java)) {
            _,
            method,
            _ ->
            when (method.name) {
                "run" -> Unit
                else -> error("Unexpected plugin instance method: ${method.name}")
            }
        }

    private fun ManifestClassLoader(source: String): ClassLoader {
        val root = directory.resolve(source)
        val resource = root.resolve(PermissionManifest.RESOURCE_PATH)
        Files.createDirectories(resource.parent)
        Files.writeString(
            resource,
            """{"source":"$source","permissions":[{"key":"grounds.$source.use","label":"Use command","description":"Allows use.","supportedScopes":["GLOBAL"]}]}""",
        )
        return URLClassLoader(arrayOf(root.toUri().toURL()), null)
    }
}
