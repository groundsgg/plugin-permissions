package gg.grounds.permissions

import java.time.Instant
import java.util.UUID

enum class PermissionEffect {
    ALLOW,
    DENY,
}

enum class PermissionScopeKind {
    GLOBAL,
    SERVER_TYPE,
    SERVER,
}

data class PermissionScope(val kind: PermissionScopeKind, val value: String? = null) {
    init {
        require((kind == PermissionScopeKind.GLOBAL) == (value == null)) {
            "Global scopes must not have a value and non-global scopes must have a value"
        }
    }

    companion object {
        fun global(): PermissionScope = PermissionScope(PermissionScopeKind.GLOBAL)

        fun serverType(serverType: String): PermissionScope =
            PermissionScope(PermissionScopeKind.SERVER_TYPE, serverType)

        fun server(server: String): PermissionScope =
            PermissionScope(PermissionScopeKind.SERVER, server)
    }
}

enum class PermissionGrantSource {
    ROLE,
    PLAYER,
}

data class PermissionGrant(
    val effect: PermissionEffect,
    val pattern: String,
    val scope: PermissionScope,
    val source: PermissionGrantSource,
    val expiresAt: Instant? = null,
)

data class RoleMetadata(
    val key: String,
    val name: String,
    val prefix: String? = null,
    val color: String? = null,
    val sortOrder: Int = 0,
)

data class PermissionSnapshot(
    val playerId: UUID,
    val policyVersion: Long,
    val issuedAt: Instant,
    val refreshAfter: Instant,
    val expiresAt: Instant,
    val allowPatterns: List<PermissionGrant>,
    val denyPatterns: List<PermissionGrant>,
    val roleKeys: Set<String>,
    val roleMetadata: List<RoleMetadata>,
)
