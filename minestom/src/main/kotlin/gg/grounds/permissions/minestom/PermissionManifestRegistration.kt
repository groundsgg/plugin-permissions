package gg.grounds.permissions.minestom

import gg.grounds.permissions.catalog.ManifestOrigin
import gg.grounds.permissions.catalog.PermissionManifestCollection
import gg.grounds.permissions.catalog.PermissionManifestCollector
import gg.grounds.runtime.ActiveGroundsModuleProvider

fun collectActivePermissionManifests(
    activeProviders: Iterable<ActiveGroundsModuleProvider>
): PermissionManifestCollection =
    PermissionManifestCollector()
        .collect(
            activeProviders.map { provider ->
                ManifestOrigin(
                    id = provider.id,
                    version = provider.version,
                    classLoader = provider.classLoader,
                )
            }
        )
