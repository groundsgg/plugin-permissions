package gg.grounds.permissions.minestom

import gg.grounds.grpc.permissions.PermissionCatalogServiceGrpc
import gg.grounds.grpc.permissions.PermissionManifestEntry as GrpcPermissionManifestEntry
import gg.grounds.grpc.permissions.PermissionScopeKind
import gg.grounds.grpc.permissions.RegisterPermissionManifestRequest
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.catalog.PermissionManifestEntry
import gg.grounds.permissions.catalog.PermissionManifestScope
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.util.concurrent.TimeUnit

sealed interface PermissionManifestRegistrationResult {
    data object Accepted : PermissionManifestRegistrationResult

    data class Unavailable(val reason: String) : PermissionManifestRegistrationResult
}

interface PermissionCatalogClient : AutoCloseable {
    fun register(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ): PermissionManifestRegistrationResult

    override fun close() {}
}

class GrpcPermissionCatalogClient private constructor(private val channel: ManagedChannel) :
    PermissionCatalogClient {
    private val stub = PermissionCatalogServiceGrpc.newBlockingStub(channel)

    override fun register(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ): PermissionManifestRegistrationResult =
        try {
            val reply =
                stub
                    .withDeadlineAfter(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .registerPermissionManifest(
                        RegisterPermissionManifestRequest.newBuilder()
                            .setSource(manifest.source)
                            .setSourceVersion(sourceVersion)
                            .setServerType(context.serverType.orEmpty())
                            .setServerId(context.serverId.orEmpty())
                            .addAllPermissions(
                                manifest.permissions.map(PermissionManifestEntry::toGrpc)
                            )
                            .build()
                    )
            if (reply.accepted) {
                PermissionManifestRegistrationResult.Accepted
            } else {
                PermissionManifestRegistrationResult.Unavailable(
                    reply.message.ifBlank { "rejected" }
                )
            }
        } catch (exception: StatusRuntimeException) {
            PermissionManifestRegistrationResult.Unavailable(exception.status.toSafeReason())
        } catch (exception: RuntimeException) {
            PermissionManifestRegistrationResult.Unavailable(
                exception.message ?: exception::class.java.name
            )
        }

    override fun close() {
        channel.shutdown()
        try {
            if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                channel.shutdownNow()
                channel.awaitTermination(3, TimeUnit.SECONDS)
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            channel.shutdownNow()
        }
    }

    companion object {
        fun create(target: String): GrpcPermissionCatalogClient =
            GrpcPermissionCatalogClient(
                ManagedChannelBuilder.forTarget(target).usePlaintext().build()
            )

        private const val DEFAULT_TIMEOUT_MS = 2000L
    }
}

private fun PermissionManifestEntry.toGrpc(): GrpcPermissionManifestEntry =
    GrpcPermissionManifestEntry.newBuilder()
        .setKey(key)
        .setLabel(label)
        .setDescription(description)
        .addAllSupportedScopes(supportedScopes.map(PermissionManifestScope::toGrpc))
        .build()

private fun PermissionManifestScope.toGrpc(): PermissionScopeKind =
    when (this) {
        PermissionManifestScope.GLOBAL -> PermissionScopeKind.PERMISSION_SCOPE_KIND_GLOBAL
        PermissionManifestScope.SERVER_TYPE -> PermissionScopeKind.PERMISSION_SCOPE_KIND_SERVER_TYPE
        PermissionManifestScope.SERVER -> PermissionScopeKind.PERMISSION_SCOPE_KIND_SERVER
    }

private fun Status.toSafeReason(): String = code.toString()
