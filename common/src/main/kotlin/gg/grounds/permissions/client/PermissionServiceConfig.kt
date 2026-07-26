package gg.grounds.permissions.client

import java.net.URI
import java.nio.file.Path

data class PermissionServiceConfig(val serviceUri: URI, val tokenFile: Path) {
    init {
        require(serviceUri.isAbsolute && serviceUri.host != null) {
            "Permission service URL must be absolute"
        }
        require(serviceUri.scheme.lowercase() in SUPPORTED_SCHEMES) {
            "Permission service URL must use HTTP or HTTPS"
        }
        require(serviceUri.userInfo == null) {
            "Permission service URL must not contain user information"
        }
        require(serviceUri.rawQuery == null) { "Permission service URL must not contain a query" }
        require(serviceUri.rawFragment == null) {
            "Permission service URL must not contain a fragment"
        }
        require(tokenFile.toString().isNotBlank()) { "Workload token file must not be blank" }
    }

    companion object {
        private val SUPPORTED_SCHEMES = setOf("http", "https")

        fun parse(serviceUrl: String, tokenFile: String): PermissionServiceConfig {
            require(serviceUrl.isNotBlank()) { "Permission service URL must not be blank" }
            require(tokenFile.isNotBlank()) { "Workload token file must not be blank" }
            return PermissionServiceConfig(
                serviceUri = URI.create(serviceUrl.trim()),
                tokenFile = Path.of(tokenFile.trim()),
            )
        }
    }
}
