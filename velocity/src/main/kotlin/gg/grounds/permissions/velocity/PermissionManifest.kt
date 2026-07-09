package gg.grounds.permissions.velocity

import java.io.InputStream
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

data class PermissionManifest(val permissions: List<PermissionManifestEntry>) {
    init {
        require(permissions.isNotEmpty()) {
            "Permission manifest must declare at least one permission."
        }
        require(permissions.map(PermissionManifestEntry::key).toSet().size == permissions.size) {
            "Permission manifest must not declare duplicate keys."
        }
    }

    companion object {
        private const val RESOURCE_NAME = "META-INF/grounds-permissions.json"
        private val mapper: ObjectMapper =
            JsonMapper.builder()
                .addModule(KotlinModule.Builder().build())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()

        fun loadResource(): PermissionManifest {
            val input =
                PermissionManifest::class.java.classLoader.getResourceAsStream(RESOURCE_NAME)
                    ?: error("Missing required permission manifest resource $RESOURCE_NAME")
            return input.use(::load)
        }

        fun load(input: InputStream): PermissionManifest =
            mapper.readValue(input, PermissionManifest::class.java).validated()
    }

    private fun validated(): PermissionManifest {
        permissions.forEach { permission ->
            require(permission.key.isNotBlank()) { "Permission manifest keys must not be blank." }
            require(permission.label.isNotBlank()) {
                "Permission manifest labels must not be blank."
            }
            require(permission.supportedScopes.isNotEmpty()) {
                "Permission manifest scopes must not be empty (key=${permission.key})."
            }
        }
        return this
    }
}

data class PermissionManifestEntry(
    val key: String,
    val label: String,
    val description: String = "",
    val supportedScopes: List<PermissionManifestScope>,
)

enum class PermissionManifestScope {
    GLOBAL,
    SERVER_TYPE,
    SERVER,
}
