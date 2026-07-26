package gg.grounds.permissions.client

import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionScopeKind
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.catalog.PermissionManifest
import java.time.Instant
import java.util.UUID

internal data class RuntimePermissionSnapshotDto(
    val playerId: String? = null,
    val policyVersion: Long? = null,
    val issuedAt: String? = null,
    val refreshAfter: String? = null,
    val expiresAt: String? = null,
    val allowPatterns: List<RuntimePermissionGrantDto>? = null,
    val denyPatterns: List<RuntimePermissionGrantDto>? = null,
    val roleKeys: Set<String>? = null,
    val roleMetadata: List<RuntimeRoleMetadataDto>? = null,
) {
    fun toDomain(expectedPlayerId: UUID): PermissionSnapshot {
        val responsePlayerId = UUID.fromString(required(playerId, "playerId"))
        require(responsePlayerId == expectedPlayerId) {
            "Permission snapshot playerId does not match the requested player"
        }
        return PermissionSnapshot(
            playerId = responsePlayerId,
            policyVersion = requireNotNull(policyVersion) { "policyVersion is required" },
            issuedAt = Instant.parse(required(issuedAt, "issuedAt")),
            refreshAfter = Instant.parse(required(refreshAfter, "refreshAfter")),
            expiresAt = Instant.parse(required(expiresAt, "expiresAt")),
            allowPatterns =
                requireNotNull(allowPatterns) { "allowPatterns is required" }
                    .map(RuntimePermissionGrantDto::toDomain),
            denyPatterns =
                requireNotNull(denyPatterns) { "denyPatterns is required" }
                    .map(RuntimePermissionGrantDto::toDomain),
            roleKeys = requireNotNull(roleKeys) { "roleKeys is required" }.toSet(),
            roleMetadata =
                requireNotNull(roleMetadata) { "roleMetadata is required" }
                    .map(RuntimeRoleMetadataDto::toDomain),
        )
    }
}

internal data class RuntimePermissionGrantDto(
    val effect: String? = null,
    val pattern: String? = null,
    val scope: RuntimePermissionScopeDto? = null,
    val source: String? = null,
    val expiresAt: String? = null,
) {
    fun toDomain(): PermissionGrant =
        PermissionGrant(
            effect = PermissionEffect.valueOf(required(effect, "effect")),
            pattern = required(pattern, "pattern"),
            scope = requireNotNull(scope) { "scope is required" }.toDomain(),
            source = PermissionGrantSource.valueOf(required(source, "source")),
            expiresAt = expiresAt?.let(Instant::parse),
        )
}

internal data class RuntimePermissionScopeDto(val kind: String? = null, val value: String? = null) {
    fun toDomain(): PermissionScope =
        PermissionScope(kind = PermissionScopeKind.valueOf(required(kind, "scope.kind")), value)
}

internal data class RuntimeRoleMetadataDto(
    val key: String? = null,
    val name: String? = null,
    val prefix: String? = null,
    val color: String? = null,
    val sortOrder: Int? = null,
) {
    fun toDomain(): RoleMetadata =
        RoleMetadata(
            key = required(key, "roleMetadata.key"),
            name = required(name, "roleMetadata.name"),
            prefix = prefix,
            color = color,
            sortOrder = requireNotNull(sortOrder) { "roleMetadata.sortOrder is required" },
        )
}

private fun required(value: String?, field: String): String =
    requireNotNull(value) { "$field is required" }
        .also { require(it.isNotBlank()) { "$field must not be blank" } }

internal data class RuntimeManifestRequestDto(
    val sourceVersion: String,
    val serverType: String?,
    val serverId: String?,
    val permissions: List<RuntimeManifestPermissionDto>,
)

internal data class RuntimeManifestPermissionDto(
    val key: String,
    val label: String,
    val description: String,
    val supportedScopes: List<String>,
)

internal fun PermissionManifest.toRuntimeRequest(
    sourceVersion: String,
    context: PermissionSnapshotContext,
): RuntimeManifestRequestDto =
    RuntimeManifestRequestDto(
        sourceVersion = sourceVersion,
        serverType = context.serverType,
        serverId = context.serverId,
        permissions =
            permissions.map { permission ->
                RuntimeManifestPermissionDto(
                    key = permission.key,
                    label = permission.label,
                    description = permission.description,
                    supportedScopes = permission.supportedScopes.map { it.name },
                )
            },
    )
