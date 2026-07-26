package gg.grounds.permissions.client

import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.catalog.PermissionManifest
import java.util.UUID

interface PermissionRuntimeClient {
    fun fetchSnapshot(playerId: UUID, context: PermissionSnapshotContext): PermissionSnapshot

    fun registerManifest(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ): PermissionManifestRegistrationResult
}

sealed interface PermissionManifestRegistrationResult {
    data object Accepted : PermissionManifestRegistrationResult

    data class Unavailable(val reason: String) : PermissionManifestRegistrationResult
}
