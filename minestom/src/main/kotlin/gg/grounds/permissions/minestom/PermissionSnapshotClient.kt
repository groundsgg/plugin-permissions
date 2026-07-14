package gg.grounds.permissions.minestom

import com.google.protobuf.Timestamp
import gg.grounds.grpc.permissions.GetPlayerSnapshotRequest
import gg.grounds.grpc.permissions.PermissionEffect as GrpcPermissionEffect
import gg.grounds.grpc.permissions.PermissionGrant as GrpcPermissionGrant
import gg.grounds.grpc.permissions.PermissionGrantOriginKind as GrpcPermissionGrantOriginKind
import gg.grounds.grpc.permissions.PermissionGrantSource as GrpcPermissionGrantSource
import gg.grounds.grpc.permissions.PermissionScope as GrpcPermissionScope
import gg.grounds.grpc.permissions.PermissionScopeKind as GrpcPermissionScopeKind
import gg.grounds.grpc.permissions.PermissionSnapshotServiceGrpc
import gg.grounds.grpc.permissions.PlayerPermissionSnapshot
import gg.grounds.grpc.permissions.RoleMetadata as GrpcRoleMetadata
import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantOrigin
import gg.grounds.permissions.PermissionGrantOriginKind
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PermissionSnapshotContext(val serverType: String? = null, val serverId: String? = null)

sealed interface PermissionSnapshotFetchResult {
    data class Success(val snapshot: PermissionSnapshot) : PermissionSnapshotFetchResult

    data class Unavailable(val reason: String) : PermissionSnapshotFetchResult
}

interface PermissionSnapshotClient : AutoCloseable {
    fun fetchSnapshot(
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshotFetchResult

    override fun close() {}
}

class GrpcPermissionSnapshotClient private constructor(private val channel: ManagedChannel) :
    PermissionSnapshotClient {
    private val stub = PermissionSnapshotServiceGrpc.newBlockingStub(channel)

    override fun fetchSnapshot(
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshotFetchResult {
        return try {
            val request =
                GetPlayerSnapshotRequest.newBuilder()
                    .setPlayerId(playerId.toString())
                    .setServerType(context.serverType.orEmpty())
                    .setServerId(context.serverId.orEmpty())
                    .build()
            PermissionSnapshotFetchResult.Success(
                stub
                    .withDeadlineAfter(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getPlayerSnapshot(request)
                    .toDomain()
            )
        } catch (e: StatusRuntimeException) {
            PermissionSnapshotFetchResult.Unavailable(e.status.toSafeReason())
        } catch (e: RuntimeException) {
            PermissionSnapshotFetchResult.Unavailable(e.message ?: e::class.java.name)
        }
    }

    override fun close() {
        channel.shutdown()
        try {
            if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                channel.shutdownNow()
                channel.awaitTermination(3, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            channel.shutdownNow()
        }
    }

    companion object {
        fun create(target: String): GrpcPermissionSnapshotClient {
            val channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build()
            return GrpcPermissionSnapshotClient(channel)
        }

        internal fun create(channel: ManagedChannel): GrpcPermissionSnapshotClient =
            GrpcPermissionSnapshotClient(channel)

        private const val DEFAULT_TIMEOUT_MS = 2000L
    }
}

private fun Status.toSafeReason(): String = code.toString()

private fun PlayerPermissionSnapshot.toDomain(): PermissionSnapshot =
    PermissionSnapshot(
        playerId = UUID.fromString(playerId),
        policyVersion = policyVersion,
        issuedAt = issuedAt.toInstant(),
        refreshAfter = refreshAfter.toInstant(),
        expiresAt = expiresAt.toInstant(),
        allowPatterns = allowPatternsList.map { it.toDomain() },
        denyPatterns = denyPatternsList.map { it.toDomain() },
        roleKeys = roleKeysList.toSet(),
        roleMetadata = roleMetadataList.map { it.toDomain() },
    )

private fun GrpcPermissionGrant.toDomain(): PermissionGrant =
    PermissionGrant(
        effect =
            when (effect) {
                GrpcPermissionEffect.PERMISSION_EFFECT_DENY -> PermissionEffect.DENY
                else -> PermissionEffect.ALLOW
            },
        pattern = pattern,
        scope = scope.toDomain(),
        source =
            when (source) {
                GrpcPermissionGrantSource.PERMISSION_GRANT_SOURCE_PLAYER ->
                    PermissionGrantSource.PLAYER
                else -> PermissionGrantSource.ROLE
            },
        expiresAt = if (hasExpiresAt()) expiresAt.toInstant() else null,
        origin = toDomainOrigin(),
    )

private fun GrpcPermissionGrant.toDomainOrigin(): PermissionGrantOrigin? {
    if (!hasOrigin()) return null

    val kind =
        when (origin.kind) {
            GrpcPermissionGrantOriginKind.PERMISSION_GRANT_ORIGIN_KIND_DEFAULT_ROLE ->
                PermissionGrantOriginKind.DEFAULT_ROLE
            GrpcPermissionGrantOriginKind.PERMISSION_GRANT_ORIGIN_KIND_DIRECT_ROLE ->
                PermissionGrantOriginKind.DIRECT_ROLE
            GrpcPermissionGrantOriginKind.PERMISSION_GRANT_ORIGIN_KIND_GROUP_MAPPING ->
                PermissionGrantOriginKind.GROUP_MAPPING
            GrpcPermissionGrantOriginKind.PERMISSION_GRANT_ORIGIN_KIND_DIRECT_PERMISSION ->
                PermissionGrantOriginKind.DIRECT_PERMISSION
            else -> return null
        }

    return PermissionGrantOrigin(
        kind = kind,
        roleKey = origin.roleKey.takeIf { it.isNotEmpty() },
        mappingId = origin.mappingId.takeIf { it.isNotEmpty() },
        inheritedPath = origin.inheritedPathList.toList(),
    )
}

private fun GrpcPermissionScope.toDomain(): PermissionScope =
    when (kind) {
        GrpcPermissionScopeKind.PERMISSION_SCOPE_KIND_SERVER -> PermissionScope.server(value)
        GrpcPermissionScopeKind.PERMISSION_SCOPE_KIND_SERVER_TYPE ->
            PermissionScope.serverType(value)
        else -> PermissionScope.global()
    }

private fun GrpcRoleMetadata.toDomain(): RoleMetadata =
    RoleMetadata(
        key = key,
        name = name,
        prefix = prefix.takeIf { it.isNotEmpty() },
        color = color.takeIf { it.isNotEmpty() },
        sortOrder = sortOrder,
    )

private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
