package gg.grounds.permissions.catalog

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** Optional integration gate against final JARs and independently supplied release expectations. */
class PackagedPermissionManifestTest {
    @Test
    fun `collects all definitions and producing versions from packaged hosts`() {
        val configuration = System.getenv("PERMISSION_TEST_EXPECTATIONS")
        assumeTrue(
            !configuration.isNullOrBlank(),
            "Set PERMISSION_TEST_EXPECTATIONS to a JSON expectation file",
        )
        val expectations =
            Files.newInputStream(Path.of(requireNotNull(configuration))).use {
                JsonMapper.builder().build().readValue(it, Map::class.java)
            }
        require(expectations.isNotEmpty()) { "Expected at least one host artifact" }
        expectations.forEach { (artifact, versions) ->
            require(artifact is String && versions is Map<*, *>)
            val expected =
                versions.entries.associate { (source, version) ->
                    require(source is String && version is String)
                    source to version
                }
            URLClassLoader(arrayOf(Path.of(artifact).toUri().toURL()), null).use { loader ->
                val collection =
                    PermissionManifestCollector()
                        .collect(listOf(ManifestOrigin("host", "wrong-host-version", loader)))
                assertTrue(collection.failures.isEmpty(), collection.failures.toString())
                assertEquals(
                    expected,
                    collection.manifests.associate { it.manifest.source to it.sourceVersion },
                    artifact,
                )
            }
        }
    }
}
