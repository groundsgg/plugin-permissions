package gg.grounds.permissions.velocity

import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantOrigin
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionScopeKind
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import org.slf4j.Logger
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

class SnapshotDiskCache(private val logger: Logger, private val cacheDirectory: Path) {
    private val mapper: ObjectMapper =
        JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    fun read(playerId: UUID, now: Instant): PermissionSnapshot? {
        val path = pathFor(playerId)
        if (Files.notExists(path)) {
            return null
        }

        return try {
            val snapshot =
                Files.newInputStream(path).use { input ->
                    mapper.readValue<CachedPermissionSnapshot>(input).toDomain()
                }
            if (!snapshot.expiresAt.isAfter(now)) {
                logger.warn(
                    "Cached permission snapshot expired (playerId={}, expiresAt={})",
                    playerId,
                    snapshot.expiresAt,
                )
                return null
            }
            snapshot
        } catch (e: Exception) {
            logger.warn(
                "Failed to read cached permission snapshot (playerId={}, path={})",
                playerId,
                path,
                e,
            )
            null
        }
    }

    fun write(snapshot: PermissionSnapshot) {
        val path = pathFor(snapshot.playerId)
        try {
            Files.createDirectories(cacheDirectory)
            Files.newOutputStream(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                .use { output -> mapper.writeValue(output, snapshot.toCache()) }
        } catch (e: Exception) {
            logger.warn(
                "Failed to write cached permission snapshot (playerId={}, path={})",
                snapshot.playerId,
                path,
                e,
            )
        }
    }

    private fun pathFor(playerId: UUID): Path = cacheDirectory.resolve("$playerId.json")
}

private data class CachedPermissionSnapshot(
    val playerId: String,
    val policyVersion: Long,
    val issuedAt: String,
    val refreshAfter: String,
    val expiresAt: String,
    val allowPatterns: List<CachedPermissionGrant> = emptyList(),
    val denyPatterns: List<CachedPermissionGrant> = emptyList(),
    val roleKeys: Set<String> = emptySet(),
    val roleMetadata: List<CachedRoleMetadata> = emptyList(),
) {
    fun toDomain(): PermissionSnapshot =
        PermissionSnapshot(
            playerId = UUID.fromString(playerId),
            policyVersion = policyVersion,
            issuedAt = Instant.parse(issuedAt),
            refreshAfter = Instant.parse(refreshAfter),
            expiresAt = Instant.parse(expiresAt),
            allowPatterns = allowPatterns.map { it.toDomain() },
            denyPatterns = denyPatterns.map { it.toDomain() },
            roleKeys = roleKeys,
            roleMetadata = roleMetadata.map { it.toDomain() },
        )
}

private data class CachedPermissionGrant(
    val effect: PermissionEffect,
    val pattern: String,
    val scope: CachedPermissionScope,
    val source: PermissionGrantSource,
    val expiresAt: String? = null,
    val origin: PermissionGrantOrigin? = null,
) {
    fun toDomain(): PermissionGrant =
        PermissionGrant(
            effect = effect,
            pattern = pattern,
            scope = scope.toDomain(),
            source = source,
            expiresAt = expiresAt?.let(Instant::parse),
            origin = origin,
        )
}

private data class CachedPermissionScope(val kind: PermissionScopeKind, val value: String? = null) {
    fun toDomain(): PermissionScope = PermissionScope(kind, value)
}

private data class CachedRoleMetadata(
    val key: String,
    val name: String,
    val prefix: String? = null,
    val color: String? = null,
    val sortOrder: Int = 0,
) {
    fun toDomain(): RoleMetadata =
        RoleMetadata(key = key, name = name, prefix = prefix, color = color, sortOrder = sortOrder)
}

private fun PermissionSnapshot.toCache(): CachedPermissionSnapshot =
    CachedPermissionSnapshot(
        playerId = playerId.toString(),
        policyVersion = policyVersion,
        issuedAt = issuedAt.toString(),
        refreshAfter = refreshAfter.toString(),
        expiresAt = expiresAt.toString(),
        allowPatterns = allowPatterns.map { it.toCache() },
        denyPatterns = denyPatterns.map { it.toCache() },
        roleKeys = roleKeys,
        roleMetadata = roleMetadata.map { it.toCache() },
    )

private fun PermissionGrant.toCache(): CachedPermissionGrant =
    CachedPermissionGrant(
        effect = effect,
        pattern = pattern,
        scope = scope.toCache(),
        source = source,
        expiresAt = expiresAt?.toString(),
        origin = origin,
    )

private fun PermissionScope.toCache(): CachedPermissionScope = CachedPermissionScope(kind, value)

private fun RoleMetadata.toCache(): CachedRoleMetadata =
    CachedRoleMetadata(
        key = key,
        name = name,
        prefix = prefix,
        color = color,
        sortOrder = sortOrder,
    )
