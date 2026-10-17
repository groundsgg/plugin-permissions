package gg.grounds.permissions.catalog

import java.util.Collections

/** Reads every index, including resources combined by a shaded host. */
internal object IndexedPermissionManifestResources {
    const val INDEX_PATH = "META-INF/grounds/permissions.index"
    private val resourcePath =
        Regex("META-INF/grounds/permissions/([a-zA-Z0-9][a-zA-Z0-9._-]*)\\.json")

    fun read(classLoader: ClassLoader, failed: (String) -> Unit): List<PermissionManifest>? {
        val indexes =
            try {
                Collections.list(classLoader.getResources(INDEX_PATH))
            } catch (exception: Exception) {
                failed("Cannot enumerate permission indexes: ${exception.message}")
                return emptyList()
            }
        if (indexes.isEmpty()) return null
        val paths = linkedSetOf<String>()
        indexes.forEach { index ->
            try {
                index.openStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    reader
                        .lineSequence()
                        .map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith('#') }
                        .forEach(paths::add)
                }
            } catch (exception: Exception) {
                failed("Cannot read permission index $index: ${exception.message}")
            }
        }
        return paths.flatMap { path ->
            val source = resourcePath.matchEntire(path)?.groupValues?.get(1)
            if (source == null || source.contains("..")) {
                failed("Unsafe permission manifest resource path: $path")
                return@flatMap emptyList()
            }
            try {
                val resources = Collections.list(classLoader.getResources(path))
                require(resources.isNotEmpty()) { "Missing indexed permission manifest: $path" }
                resources.mapNotNull { resource ->
                    try {
                        val manifest = resource.openStream().use(PermissionManifest::load)
                        require(manifest.source == source) {
                            "Permission manifest source/path mismatch: $path"
                        }
                        val version = manifest.sourceVersion
                        require(
                            !version.isNullOrBlank() &&
                                !version.contains("\${") &&
                                !version.contains('@')
                        ) {
                            "Missing or unexpanded sourceVersion in $path"
                        }
                        manifest
                    } catch (exception: Exception) {
                        failed(
                            "Invalid indexed permission manifest $resource: ${exception.message}"
                        )
                        null
                    }
                }
            } catch (exception: Exception) {
                failed(exception.message ?: "Cannot read permission manifest $path")
                emptyList()
            }
        }
    }
}
