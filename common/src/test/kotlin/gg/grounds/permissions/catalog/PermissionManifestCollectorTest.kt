package gg.grounds.permissions.catalog

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionManifestCollectorTest {
    @Test
    fun `loads the established manifest shape`() {
        val manifest =
            PermissionManifest.load(
                """
                {
                  "source": "plugin-agones",
                  "permissions": [
                    {
                      "key": "grounds.command.agones",
                      "label": "Use Agones command",
                      "description": "Allows using the Agones command.",
                      "supportedScopes": ["GLOBAL", "SERVER_TYPE", "SERVER"]
                    }
                  ]
                }
                """
                    .trimIndent()
                    .byteInputStream()
            )

        assertEquals("plugin-agones", manifest.source)
        assertEquals("grounds.command.agones", manifest.permissions.single().key)
        assertEquals(
            listOf(
                PermissionManifestScope.GLOBAL,
                PermissionManifestScope.SERVER_TYPE,
                PermissionManifestScope.SERVER,
            ),
            manifest.permissions.single().supportedScopes,
        )
    }

    @Test
    fun `skips origins without a manifest resource`() {
        val collection =
            PermissionManifestCollector()
                .collect(listOf(ManifestOrigin("missing-plugin", "1.0.0", ResourceClassLoader())))

        assertTrue(collection.manifests.isEmpty())
        assertTrue(collection.failures.isEmpty())
    }

    @Test
    fun `reports duplicate permission keys without collecting the manifest`() {
        val collection =
            PermissionManifestCollector()
                .collect(
                    listOf(
                        ManifestOrigin(
                            "duplicate-plugin",
                            "1.0.0",
                            ResourceClassLoader(
                                """
                                {
                                  "source": "duplicate-plugin",
                                  "permissions": [
                                    { "key": "grounds.command.duplicate", "label": "Duplicate", "description": "Allows duplicate command.", "supportedScopes": ["GLOBAL"] },
                                    { "key": "grounds.command.duplicate", "label": "Duplicate again", "description": "Allows duplicate command again.", "supportedScopes": ["GLOBAL"] }
                                  ]
                                }
                                """
                                    .trimIndent()
                            ),
                        )
                    )
                )

        assertTrue(collection.manifests.isEmpty())
        assertEquals("duplicate-plugin", collection.failures.single().origin.id)
    }

    @Test
    fun `reports manifests with missing or blank sources`() {
        val collection =
            PermissionManifestCollector()
                .collect(
                    listOf(
                        ManifestOrigin(
                            "missing-source",
                            "1.0.0",
                            ResourceClassLoader(
                                """
                                {
                                  "permissions": [
                                    { "key": "grounds.command.missing-source", "label": "Missing source", "description": "Requires a source.", "supportedScopes": ["GLOBAL"] }
                                  ]
                                }
                                """
                                    .trimIndent()
                            ),
                        ),
                        ManifestOrigin(
                            "blank-source",
                            "1.0.0",
                            ResourceClassLoader(
                                """
                                {
                                  "source": "   ",
                                  "permissions": [
                                    { "key": "grounds.command.blank-source", "label": "Blank source", "description": "Requires a source.", "supportedScopes": ["GLOBAL"] }
                                  ]
                                }
                                """
                                    .trimIndent()
                            ),
                        ),
                    )
                )

        assertTrue(collection.manifests.isEmpty())
        assertEquals(
            listOf("missing-source", "blank-source"),
            collection.failures.map { it.origin.id },
        )
    }

    @Test
    fun `reports manifests with blank required permission entry fields`() {
        val collection =
            PermissionManifestCollector()
                .collect(
                    listOf("key", "label", "description").map { field ->
                        ManifestOrigin(
                            "blank-$field",
                            "1.0.0",
                            ResourceClassLoader(
                                """
                                {
                                  "source": "blank-$field",
                                  "permissions": [
                                    {
                                      "key": "${if (field == "key") " " else "grounds.command.blank-$field"}",
                                      "label": "${if (field == "label") " " else "Use command"}",
                                      "description": "${if (field == "description") " " else "Allows using the command."}",
                                      "supportedScopes": ["GLOBAL"]
                                    }
                                  ]
                                }
                                """
                                    .trimIndent()
                            ),
                        )
                    }
                )

        assertTrue(collection.manifests.isEmpty())
        assertEquals(
            listOf("blank-key", "blank-label", "blank-description"),
            collection.failures.map { it.origin.id },
        )
    }

    @Test
    fun `rejects unsupported scopes`() {
        assertThrows(IllegalArgumentException::class.java) {
            PermissionManifest.load(
                """
                {
                  "source": "invalid-plugin",
                  "permissions": [
                    { "key": "grounds.command.invalid", "label": "Invalid", "description": "Allows invalid command.", "supportedScopes": ["PROJECT"] }
                  ]
                }
                """
                    .trimIndent()
                    .byteInputStream()
            )
        }
    }

    private class ResourceClassLoader(manifest: String? = null) : ClassLoader() {
        private val bytes = manifest?.encodeToByteArray()

        override fun getResourceAsStream(name: String): InputStream? =
            if (name == PermissionManifest.RESOURCE_PATH && bytes != null) {
                ByteArrayInputStream(bytes)
            } else {
                null
            }
    }
}
