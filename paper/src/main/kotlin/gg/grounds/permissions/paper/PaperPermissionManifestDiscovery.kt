package gg.grounds.permissions.paper

import gg.grounds.permissions.catalog.ManifestOrigin
import org.bukkit.plugin.Plugin

internal fun discoverPaperPermissionManifestOrigins(
    plugins: Collection<Plugin>
): List<ManifestOrigin> =
    plugins.map { plugin ->
        ManifestOrigin(
            id = plugin.name,
            version = plugin.pluginMeta.version,
            classLoader = plugin.javaClass.classLoader,
            artifactLocation = plugin.javaClass.protectionDomain?.codeSource?.location,
        )
    }
