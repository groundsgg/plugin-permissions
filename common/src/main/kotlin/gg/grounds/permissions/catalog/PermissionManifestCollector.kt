package gg.grounds.permissions.catalog

import java.net.URL

data class ManifestOrigin(
    val id: String,
    val version: String,
    val classLoader: ClassLoader,
    val artifactLocation: URL? = null,
)

data class CollectedPermissionManifest(
    val origin: ManifestOrigin,
    val manifest: PermissionManifest,
    val sourceVersion: String = manifest.sourceVersion ?: origin.version,
)

data class PermissionManifestCollectionFailure(val origin: ManifestOrigin, val reason: String)

data class PermissionManifestCollection(
    val manifests: List<CollectedPermissionManifest>,
    val failures: List<PermissionManifestCollectionFailure>,
)

class PermissionManifestCollector {
    fun collect(origins: Iterable<ManifestOrigin>): PermissionManifestCollection {
        val manifests = mutableListOf<CollectedPermissionManifest>()
        val failures = mutableListOf<PermissionManifestCollectionFailure>()

        origins
            .groupBy { it.classLoader }
            .values
            .forEach { loaderOrigins ->
                val origin = loaderOrigins.first()
                fun failed(reason: String) {
                    failures += PermissionManifestCollectionFailure(origin, reason)
                }
                val indexed =
                    IndexedPermissionManifestResources.read(origin.classLoader, ::failed).orEmpty()
                manifests += indexed.map { CollectedPermissionManifest(origin, it) }
                val coveredSources = indexed.map { it.source }.toSet()
                loaderOrigins.forEach { legacyOrigin ->
                    val legacy =
                        LegacyPermissionManifestResources.read(
                            legacyOrigin,
                            allowIsolatedLoader = loaderOrigins.size == 1,
                            coveredSources = coveredSources,
                            failed = { reason ->
                                failures +=
                                    PermissionManifestCollectionFailure(legacyOrigin, reason)
                            },
                        )
                    if (legacy != null) {
                        manifests +=
                            CollectedPermissionManifest(legacyOrigin, legacy, legacyOrigin.version)
                    }
                }
            }

        val unique =
            manifests
                .groupBy { it.manifest.source }
                .flatMap { (source, entries) ->
                    val declarations =
                        entries.distinctBy { it.manifest.copy(sourceVersion = it.sourceVersion) }
                    if (declarations.size > 1) {
                        failures +=
                            PermissionManifestCollectionFailure(
                                entries.first().origin,
                                "Conflicting permission manifests for source=$source",
                            )
                        emptyList()
                    } else {
                        listOf(entries.first())
                    }
                }
        return PermissionManifestCollection(manifests = unique, failures = failures)
    }
}
