package gg.grounds.permissions.catalog

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IndexedPermissionManifestResourcesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `collects multiple indexed manifests from a shaded host`() {
        jar("host", indexed("plugin-chat", "2.6.1") + indexed("game-bedwars", "1.2.3")).use { loader
            ->
            val result = collect(loader)
            assertEquals(
                setOf("plugin-chat", "game-bedwars"),
                result.manifests.map { it.manifest.source }.toSet(),
            )
            assertEquals(
                mapOf("plugin-chat" to "2.6.1", "game-bedwars" to "1.2.3"),
                result.manifests.associate { it.manifest.source to it.sourceVersion },
            )
            assertTrue(result.failures.isEmpty())
        }
    }

    @Test
    fun `collects each index resource and deduplicates identical declarations`() {
        val first = writeJar("first", indexed("plugin-chat", "2.6.1"))
        val second = writeJar("second", indexed("plugin-notifications", "1.0.0"))
        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            val result =
                PermissionManifestCollector()
                    .collect(
                        listOf(
                            ManifestOrigin("a", "wrong", loader),
                            ManifestOrigin("b", "also-wrong", loader),
                        )
                    )
            assertEquals(2, result.manifests.size)
            assertTrue(result.failures.isEmpty())
        }
    }

    @Test
    fun `conflicting source versions skip the source and retain unrelated manifests`() {
        val first = writeJar("first", indexed("plugin-chat", "2.6.1"))
        val second =
            writeJar("second", indexed("plugin-chat", "2.7.0") + indexed("game-bedwars", "1.0.0"))
        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            val result = collect(loader)
            assertEquals(listOf("game-bedwars"), result.manifests.map { it.manifest.source })
            assertTrue(result.failures.any { it.reason.contains("conflict", ignoreCase = true) })
        }
    }

    @Test
    fun `rejects unsafe or missing resources while retaining valid sources`() {
        val entries = indexed("plugin-chat", "2.6.1").toMutableMap()
        entries[INDEX] =
            entries.getValue(INDEX) +
                "../permissions.json\nMETA-INF/grounds/permissions/missing.json\n"
        jar("invalid", entries).use { loader ->
            val result = collect(loader)
            assertEquals(listOf("plugin-chat"), result.manifests.map { it.manifest.source })
            assertEquals(2, result.failures.size)
        }
    }

    @Test
    fun `rejects source path mismatch and unexpanded versions`() {
        val entries = indexed("plugin-chat", "\${version}").toMutableMap()
        entries[INDEX] = entries.getValue(INDEX) + "META-INF/grounds/permissions/wrong.json\n"
        entries["META-INF/grounds/permissions/wrong.json"] = manifest("another-source", "1.0.0")
        jar("mismatch", entries).use { loader ->
            val result = collect(loader)
            assertTrue(result.manifests.isEmpty())
            assertEquals(2, result.failures.size)
        }
    }

    @Test
    fun `does not attribute a shared legacy manifest to unrelated origins`() {
        jar("legacy", mapOf(PermissionManifest.RESOURCE_PATH to manifest("plugin-chat", "2.6.1")))
            .use { loader ->
                val result =
                    PermissionManifestCollector()
                        .collect(
                            listOf(
                                ManifestOrigin("grounds.agones", "3.0.0", loader),
                                ManifestOrigin("grounds.chat", "2.6.1", loader),
                            )
                        )
                assertTrue(result.manifests.isEmpty())
                assertTrue(result.failures.isNotEmpty())
            }
    }

    @Test
    fun `equal indexed and attributable legacy declarations deduplicate by effective version`() {
        jar("indexed", indexed("plugin-chat", "2.7.0")).use { indexed ->
            val old = manifest("plugin-chat", "2.7.0").replace("\"sourceVersion\":\"2.7.0\",", "")
            jar("legacy", mapOf(PermissionManifest.RESOURCE_PATH to old)).use { legacy ->
                val result =
                    PermissionManifestCollector()
                        .collect(
                            listOf(
                                ManifestOrigin("indexed", "wrong", indexed),
                                ManifestOrigin("plugin-chat", "2.7.0", legacy),
                            )
                        )
                assertEquals(1, result.manifests.size)
                assertEquals("2.7.0", result.manifests.single().sourceVersion)
                assertTrue(result.failures.isEmpty())
            }
        }
    }

    @Test
    fun `matching source id alone cannot prove stream only ownership`() {
        val loader =
            object : ClassLoader(null) {
                override fun getResourceAsStream(name: String) =
                    if (name == PermissionManifest.RESOURCE_PATH)
                        manifest("plugin-chat", "2.7.0").byteInputStream()
                    else null
            }
        val result = collectOrigin("plugin-chat", "wrong-version", loader)
        assertTrue(result.manifests.isEmpty())
        assertTrue(result.failures.isNotEmpty())
    }

    @Test
    fun `a single shaded host jar does not establish the legacy plugin version`() {
        jar("host", mapOf(PermissionManifest.RESOURCE_PATH to manifest("plugin-chat", "2.7.0")))
            .use { loader ->
                val result = collectOrigin("host", "wrong-host-version", loader)
                assertTrue(result.manifests.isEmpty())
                assertTrue(result.failures.isNotEmpty())
            }
    }

    @Test
    fun `an unrelated parent index does not hide an owned legacy plugin`() {
        jar("parent", indexed("plugin-chat", "2.7.0")).use { parent ->
            val legacy =
                writeJar(
                    "child",
                    mapOf(PermissionManifest.RESOURCE_PATH to manifest("plugin-agones", "3.1.0")),
                )
            URLClassLoader(arrayOf(legacy.toUri().toURL()), parent).use { child ->
                val result =
                    PermissionManifestCollector()
                        .collect(
                            listOf(ManifestOrigin("agones", "3.1.0", child, legacy.toUri().toURL()))
                        )
                assertEquals(
                    setOf("plugin-chat", "plugin-agones"),
                    result.manifests.map { it.manifest.source }.toSet(),
                )
                assertTrue(result.failures.isEmpty())
            }
        }
    }

    @Test
    fun `a single provider cannot borrow a version for another jar in a multi URL loader`() {
        val legacy =
            writeJar(
                "chat",
                mapOf(PermissionManifest.RESOURCE_PATH to manifest("plugin-chat", "2.7.0")),
            )
        val other = writeJar("agones", emptyMap())
        URLClassLoader(arrayOf(legacy.toUri().toURL(), other.toUri().toURL()), null).use { loader ->
            val result = collectOrigin("grounds.agones", "3.1.0", loader)
            assertTrue(result.manifests.isEmpty())
            assertTrue(result.failures.isNotEmpty())
        }
    }

    @Test
    fun `loads the owning artifact legacy resource when a parent has a different legacy resource`() {
        jar("parent", mapOf(PermissionManifest.RESOURCE_PATH to manifest("plugin-chat", "2.7.0")))
            .use { parent ->
                val legacy =
                    writeJar(
                        "child",
                        mapOf(
                            PermissionManifest.RESOURCE_PATH to manifest("plugin-agones", "3.1.0")
                        ),
                    )
                URLClassLoader(arrayOf(legacy.toUri().toURL()), parent).use { child ->
                    val result =
                        PermissionManifestCollector()
                            .collect(
                                listOf(
                                    ManifestOrigin("agones", "3.1.0", child, legacy.toUri().toURL())
                                )
                            )
                    assertEquals("plugin-agones", result.manifests.single().manifest.source)
                    assertEquals("3.1.0", result.manifests.single().sourceVersion)
                }
            }
    }

    @Test
    fun `does not borrow a parent legacy manifest even when the source id matches`() {
        jar("parent", mapOf(PermissionManifest.RESOURCE_PATH to manifest("plugin-chat", "2.6.1")))
            .use { parent ->
                val childJar = writeJar("child", emptyMap())
                URLClassLoader(arrayOf(childJar.toUri().toURL()), parent).use { child ->
                    val result = collectOrigin("plugin-chat", "99.0.0", child)
                    assertTrue(result.manifests.isEmpty())
                    assertTrue(result.failures.isNotEmpty())
                }
            }
    }

    private fun collectOrigin(id: String, version: String, loader: ClassLoader) =
        PermissionManifestCollector().collect(listOf(ManifestOrigin(id, version, loader)))

    private fun collect(loader: ClassLoader) =
        PermissionManifestCollector()
            .collect(listOf(ManifestOrigin("host", "wrong-host-version", loader)))

    private fun indexed(source: String, version: String): Map<String, String> =
        mapOf(
            INDEX to "META-INF/grounds/permissions/$source.json\n",
            "META-INF/grounds/permissions/$source.json" to manifest(source, version),
        )

    private fun manifest(source: String, version: String) =
        """{"source":"$source","sourceVersion":"$version","permissions":[{"key":"$source.use","label":"Use plugin","description":"Allows using the plugin.","supportedScopes":["GLOBAL"]}]}"""

    private fun jar(name: String, entries: Map<String, String>) =
        URLClassLoader(arrayOf(writeJar(name, entries).toUri().toURL()), null)

    private fun writeJar(name: String, entries: Map<String, String>): Path {
        val path = directory.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(JarEntry(name))
                // Combine source indexes explicitly, just as Shadow append does.
                val text =
                    if (name == INDEX)
                        entries.keys
                            .filter { it.startsWith("META-INF/grounds/permissions/") }
                            .joinToString("\n", postfix = "\n") +
                            content
                                .lines()
                                .filter { it.startsWith("../") || it.endsWith("missing.json") }
                                .joinToString("\n", postfix = "\n")
                    else content
                output.write(text.toByteArray())
                output.closeEntry()
            }
        }
        return path
    }

    companion object {
        private const val INDEX = "META-INF/grounds/permissions.index"
    }
}
