package gg.grounds.permissions.velocity

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionCheckScope
import gg.grounds.permissions.Permissions
import gg.grounds.permissions.client.PermissionRuntimeStatusSnapshot
import gg.grounds.permissions.client.PermissionSnapshotContext
import java.net.URI
import java.util.UUID

class PermissionCommandService(
    private val snapshots: InMemoryPermissionSnapshots,
    private val permissions: Permissions,
    private val refreshSnapshot: (UUID, String) -> PermissionLoginResult,
    private val status: PermissionCommandStatus,
) {
    fun status(): PermissionCommandResult {
        val runtime = status.runtimeStatus()
        val lastFailure = runtime.lastTerminalManifestFailure
        return PermissionCommandResult.Success(
            listOf(
                "version=${status.version}",
                "serviceUrl=${status.serviceUrl}",
                "serverType=${status.context.serverType ?: "none"}",
                "serverId=${status.context.serverId ?: "none"}",
                "snapshotSuccesses=${runtime.snapshotSuccesses}",
                "snapshotFailures=${runtime.snapshotFailures}",
                "validCacheFallbacks=${runtime.validCacheFallbacks}",
                "failClosedDecisions=${runtime.failClosedDecisions}",
                "manifestRetries=${runtime.manifestRetries}",
                "lastManifestSuccessAt=${runtime.lastManifestSuccessAt ?: "none"}",
                "terminalManifestFailures=${runtime.terminalManifestFailures}",
                "lastManifestFailureSource=${lastFailure?.source ?: "none"}",
                "lastManifestFailureStatus=${lastFailure?.statusCode ?: "none"}",
                "lastManifestFailureRequestId=${lastFailure?.requestId ?: "none"}",
                "lastManifestFailureAt=${lastFailure?.failedAt ?: "none"}",
            )
        )
    }

    fun info(playerId: UUID): PermissionCommandResult {
        val snapshot = snapshots.get(playerId) ?: return noSnapshot()
        return PermissionCommandResult.Success(
            listOf(
                "playerId=$playerId",
                "policyVersion=${snapshot.policyVersion}",
                "roles=${snapshot.roleKeys.sorted().joinToString(",")}",
                "allow=${snapshot.allowPatterns.joinToString(",") { it.pattern }}",
                "deny=${snapshot.denyPatterns.joinToString(",") { it.pattern }}",
            )
        )
    }

    fun check(
        playerId: UUID,
        permission: String,
        scope: PermissionCheckScopeArgument,
    ): PermissionCommandResult {
        if (snapshots.get(playerId) == null) {
            return noSnapshot()
        }
        return PermissionCommandResult.Success(
            listOf("allowed=${permissions.hasPermission(playerId, permission, scope.toDomain())}")
        )
    }

    fun refresh(playerId: UUID, username: String): PermissionCommandResult {
        val result = refreshSnapshot(playerId, username)
        if (!result.allowed || result.snapshot == null) {
            return PermissionCommandResult.Failure(result.message)
        }

        snapshots.put(result.snapshot)
        return PermissionCommandResult.Success(
            listOf("policyVersion=${result.snapshot.policyVersion}")
        )
    }

    private fun noSnapshot(): PermissionCommandResult.Failure =
        PermissionCommandResult.Failure("No permission snapshot is loaded for this player.")
}

data class PermissionCommandStatus(
    val version: String,
    val serviceUrl: URI,
    val context: PermissionSnapshotContext,
    val runtimeStatus: () -> PermissionRuntimeStatusSnapshot,
)

sealed interface PermissionCommandResult {
    data class Success(val lines: List<String>) : PermissionCommandResult

    data class Failure(val message: String) : PermissionCommandResult
}

data class PermissionCheckScopeArgument(
    val serverType: String? = null,
    val server: String? = null,
) {
    fun toDomain(): PermissionCheckScope =
        when {
            server != null && serverType != null -> PermissionCheckScope.server(server, serverType)
            server != null -> PermissionCheckScope.serverOnly(server)
            serverType != null -> PermissionCheckScope.serverType(serverType)
            else -> PermissionCheckScope.global()
        }

    companion object {
        fun global(): PermissionCheckScopeArgument = PermissionCheckScopeArgument()

        fun parse(value: String): PermissionCheckScopeArgument =
            requireNotNull(parseOrNull(value)) { "Invalid permission scope: $value" }

        fun parseOrNull(value: String): PermissionCheckScopeArgument? =
            when {
                value == "global" -> global()
                value.startsWith("server-type=") ->
                    value
                        .removePrefix("server-type=")
                        .takeIf { it.isNotBlank() }
                        ?.let(::PermissionCheckScopeArgument)
                value.startsWith("server=") ->
                    value
                        .removePrefix("server=")
                        .takeIf { it.isNotBlank() }
                        ?.let { server -> PermissionCheckScopeArgument(server = server) }
                else -> null
            }
    }
}
