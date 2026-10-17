package gg.grounds.permissions.paper

import gg.grounds.permissions.catalog.PermissionManifestCollector
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperPermissionManifestDiscoveryTest {
    @Test
    fun `registers a plugin whose Bukkit name differs from its manifest source`(
        @TempDir root: Path
    ) {
        val resource = root.resolve("META-INF/grounds/permissions/plugin-scene-editor.json")
        Files.createDirectories(resource.parent)
        Files.writeString(
            resource,
            """{"source":"plugin-scene-editor","sourceVersion":"0.6.3","permissions":[{"key":"grounds.scene","label":"Edit scenes","description":"Use scene editing.","supportedScopes":["GLOBAL"]}]}""",
        )
        Files.writeString(
            root.resolve("META-INF/grounds/permissions.index"),
            "META-INF/grounds/permissions/plugin-scene-editor.json\n",
        )
        URLClassLoader(arrayOf(root.toUri().toURL()), Plugin::class.java.classLoader).use { loader
            ->
            val metadata =
                PluginDescriptionFile("GroundsSceneEditor", "0.6.3", "example.ScenePlugin")
            val plugin =
                Proxy.newProxyInstance(loader, arrayOf(Plugin::class.java)) { _, method, _ ->
                    when (method.name) {
                        "getName" -> "GroundsSceneEditor"
                        "getPluginMeta" -> metadata
                        else -> error("Unexpected plugin method: ${method.name}")
                    }
                } as Plugin
            val origins = discoverPaperPermissionManifestOrigins(listOf(plugin))
            val collection = PermissionManifestCollector().collect(origins)
            assertEquals("plugin-scene-editor", collection.manifests.single().manifest.source)
            assertEquals("0.6.3", collection.manifests.single().sourceVersion)
        }
    }
}
