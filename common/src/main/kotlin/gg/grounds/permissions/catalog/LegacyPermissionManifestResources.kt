package gg.grounds.permissions.catalog

import java.io.FileNotFoundException
import java.net.URL
import java.net.URLClassLoader

/** Legacy versions are usable only when the originating artifact is known. */
internal object LegacyPermissionManifestResources {
    fun read(
        origin: ManifestOrigin,
        allowIsolatedLoader: Boolean,
        coveredSources: Set<String>,
        failed: (String) -> Unit,
    ): PermissionManifest? {
        val location =
            origin.artifactLocation
                ?: if (allowIsolatedLoader) {
                    (origin.classLoader as? URLClassLoader)?.urLs?.singleOrNull()
                } else null
        if (location != null) {
            try {
                val resource =
                    if (location.toExternalForm().endsWith('/')) {
                        URL(location, PermissionManifest.RESOURCE_PATH)
                    } else {
                        URL("jar:${location.toExternalForm()}!/${PermissionManifest.RESOURCE_PATH}")
                    }
                val manifest = resource.openStream().use(PermissionManifest::load)
                if (manifest.source in coveredSources) return null
                if (origin.artifactLocation != null || origin.id == manifest.source) return manifest
                failed(
                    "Unverified legacy manifest owner (source=${manifest.source}, originId=${origin.id}); publish an indexed manifest."
                )
                return null
            } catch (_: FileNotFoundException) {
                // The owning artifact has no legacy resource. A parent resource must
                // not be used as a substitute, even if its declared source ID matches.
            } catch (exception: Exception) {
                failed("Invalid owned legacy manifest (artifact=$location): ${exception.message}")
                return null
            }
        }
        try {
            val visible = PermissionManifest.loadResource(origin.classLoader) ?: return null
            if (visible.source !in coveredSources) {
                failed(
                    "Ambiguous legacy permission manifest ownership (source=${visible.source}); publish an indexed manifest."
                )
            }
        } catch (exception: Exception) {
            failed(exception.message ?: "Invalid legacy permission manifest")
        }
        return null
    }
}
