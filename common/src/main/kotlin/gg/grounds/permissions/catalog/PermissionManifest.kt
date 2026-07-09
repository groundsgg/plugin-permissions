package gg.grounds.permissions.catalog

import java.io.InputStream
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

data class PermissionManifest(val source: String, val permissions: List<PermissionManifestEntry>) {
    init {
        require(source.isNotBlank()) { "Permission manifest source must not be blank." }
        require(permissions.isNotEmpty()) {
            "Permission manifest must declare at least one permission."
        }
        require(permissions.map(PermissionManifestEntry::key).toSet().size == permissions.size) {
            "Permission manifest must not declare duplicate keys."
        }
        permissions.forEach { permission ->
            require(permission.key.isNotBlank()) { "Permission manifest keys must not be blank." }
            require(permission.label.isNotBlank()) {
                "Permission manifest labels must not be blank."
            }
            require(permission.description.isNotBlank()) {
                "Permission manifest descriptions must not be blank (key=${permission.key})."
            }
            require(permission.supportedScopes.isNotEmpty()) {
                "Permission manifest scopes must not be empty (key=${permission.key})."
            }
        }
    }

    companion object {
        const val RESOURCE_PATH = "META-INF/grounds/permissions.json"

        private val mapper: ObjectMapper =
            JsonMapper.builder()
                .addModule(KotlinModule.Builder().build())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()

        fun load(input: InputStream): PermissionManifest =
            try {
                mapper.readValue(input, PermissionManifest::class.java)
            } catch (exception: Exception) {
                throw IllegalArgumentException("Invalid permission manifest.", exception)
            }

        fun loadResource(classLoader: ClassLoader): PermissionManifest? =
            classLoader.getResourceAsStream(RESOURCE_PATH)?.use(::load)

        fun loadRequiredResource(classLoader: ClassLoader): PermissionManifest =
            requireNotNull(loadResource(classLoader)) {
                "Missing required permission manifest resource $RESOURCE_PATH"
            }
    }
}

data class PermissionManifestEntry(
    val key: String,
    val label: String,
    val description: String,
    val supportedScopes: List<PermissionManifestScope>,
)

enum class PermissionManifestScope {
    GLOBAL,
    SERVER_TYPE,
    SERVER,
}
