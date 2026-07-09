package gg.grounds.permissions.velocity

import com.velocitypowered.api.plugin.PluginContainer
import gg.grounds.permissions.catalog.ManifestOrigin

fun discoverPermissionManifestOrigins(
    containers: Collection<PluginContainer>
): List<ManifestOrigin> =
    containers.mapNotNull { container ->
        val instance = container.instance.orElse(null) ?: return@mapNotNull null
        val classLoader = instance.javaClass.classLoader ?: return@mapNotNull null
        ManifestOrigin(
            id = container.description.id,
            version = container.description.version.orElse("unknown"),
            classLoader = classLoader,
        )
    }
