package gg.grounds.permissions.catalog

data class ManifestOrigin(val id: String, val version: String, val classLoader: ClassLoader)

data class CollectedPermissionManifest(val origin: ManifestOrigin, val manifest: PermissionManifest)

data class PermissionManifestCollectionFailure(val origin: ManifestOrigin, val reason: String)

data class PermissionManifestCollection(
    val manifests: List<CollectedPermissionManifest>,
    val failures: List<PermissionManifestCollectionFailure>,
)

class PermissionManifestCollector {
    fun collect(origins: Iterable<ManifestOrigin>): PermissionManifestCollection {
        val manifests = mutableListOf<CollectedPermissionManifest>()
        val failures = mutableListOf<PermissionManifestCollectionFailure>()

        origins.forEach { origin ->
            try {
                val manifest = PermissionManifest.loadResource(origin.classLoader) ?: return@forEach
                manifests += CollectedPermissionManifest(origin, manifest)
            } catch (exception: IllegalArgumentException) {
                failures +=
                    PermissionManifestCollectionFailure(
                        origin = origin,
                        reason = exception.message ?: exception::class.java.simpleName,
                    )
            }
        }

        return PermissionManifestCollection(manifests = manifests, failures = failures)
    }
}
