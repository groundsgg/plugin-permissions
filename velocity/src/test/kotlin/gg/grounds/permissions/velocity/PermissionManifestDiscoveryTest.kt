package gg.grounds.permissions.velocity

import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.plugin.PluginDescription
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.catalog.PermissionManifestCollector
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PermissionManifestDiscoveryTest {
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
        val client = RecordingCatalogClient()

        PermissionManifestRegistrar(
                client = client,
                context = PermissionSnapshotContext(serverType = "proxy", serverId = "proxy-1"),
                sleep = {},
            )
            .register(manifests)

        assertEquals(listOf("first-plugin", "second-plugin"), manifests.map { it.manifest.source })
        assertEquals(listOf("1.0.0", "2.0.0"), manifests.map { it.origin.version })
        assertEquals(listOf("first-plugin", "second-plugin"), client.registeredSources)
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

    private class ManifestClassLoader(private val source: String) : ClassLoader() {
        override fun getResourceAsStream(name: String): InputStream? =
            if (name == PermissionManifest.RESOURCE_PATH) {
                ByteArrayInputStream(
                    """
                    {
                      "source": "${source}",
                      "permissions": [
                        { "key": "grounds.command.${source}", "label": "Use command", "description": "Allows using the command.", "supportedScopes": ["GLOBAL"] }
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
}
