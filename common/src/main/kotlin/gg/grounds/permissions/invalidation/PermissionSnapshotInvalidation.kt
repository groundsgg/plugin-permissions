package gg.grounds.permissions.invalidation

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

const val PERMISSION_SNAPSHOT_INVALIDATION_SCHEMA_VERSION = 1
const val DEFAULT_PERMISSION_SNAPSHOT_INVALIDATION_SUBJECT = "permissions.snapshot.invalidated"

data class PermissionSnapshotInvalidation(val schemaVersion: Int, val playerId: UUID)

sealed interface PermissionSnapshotInvalidationCredentials {
    data object Anonymous : PermissionSnapshotInvalidationCredentials

    data class TokenFile(val path: Path) : PermissionSnapshotInvalidationCredentials

    data object InvalidTokenFile : PermissionSnapshotInvalidationCredentials
}

data class PermissionSnapshotInvalidationConfig(
    val natsUrl: String,
    val credentials: PermissionSnapshotInvalidationCredentials,
    val subject: String,
) {
    constructor(
        natsUrl: String,
        tokenFile: Path?,
        subject: String,
    ) : this(
        natsUrl,
        tokenFile?.let(PermissionSnapshotInvalidationCredentials::TokenFile)
            ?: PermissionSnapshotInvalidationCredentials.Anonymous,
        subject,
    )

    val tokenFile: Path?
        get() = (credentials as? PermissionSnapshotInvalidationCredentials.TokenFile)?.path

    companion object {
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv()
        ): PermissionSnapshotInvalidationConfig? {
            val natsUrl = environment["NATS_URL"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val credentials = credentialsFromEnvironment(environment)
            val subject =
                environment["PERMISSIONS_SNAPSHOT_INVALIDATIONS_SUBJECT"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty) ?: DEFAULT_PERMISSION_SNAPSHOT_INVALIDATION_SUBJECT
            return PermissionSnapshotInvalidationConfig(natsUrl, credentials, subject)
        }

        private fun credentialsFromEnvironment(
            environment: Map<String, String>
        ): PermissionSnapshotInvalidationCredentials {
            if (!environment.containsKey("GROUNDS_TOKEN_FILE")) {
                return PermissionSnapshotInvalidationCredentials.Anonymous
            }
            val configuredPath =
                environment["GROUNDS_TOKEN_FILE"]?.trim()?.takeIf(String::isNotEmpty)
                    ?: return PermissionSnapshotInvalidationCredentials.InvalidTokenFile
            return try {
                PermissionSnapshotInvalidationCredentials.TokenFile(Path.of(configuredPath))
            } catch (_: InvalidPathException) {
                PermissionSnapshotInvalidationCredentials.InvalidTokenFile
            }
        }
    }
}

class PermissionSnapshotInvalidationCodec {
    private val mapper =
        JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    fun decode(payload: ByteArray): PermissionSnapshotInvalidation? = decodeResult(payload).event

    fun rejectionReason(payload: ByteArray): String? = decodeResult(payload).reason

    internal fun decodeResult(payload: ByteArray): PermissionSnapshotInvalidationDecodeResult {
        val root =
            try {
                mapper.readTree(payload)
            } catch (_: Exception) {
                return PermissionSnapshotInvalidationDecodeResult.rejected("malformed_json")
            }
        if (root == null || !root.isObject) {
            return PermissionSnapshotInvalidationDecodeResult.rejected("malformed_json")
        }
        if (root.propertyNames().toSet() != REQUIRED_FIELDS) {
            return PermissionSnapshotInvalidationDecodeResult.rejected("unexpected_fields")
        }

        val schemaVersion =
            root["schemaVersion"].strictIntValue()
                ?: return PermissionSnapshotInvalidationDecodeResult.rejected(
                    "malformed_schema_version"
                )
        if (schemaVersion != PERMISSION_SNAPSHOT_INVALIDATION_SCHEMA_VERSION) {
            return PermissionSnapshotInvalidationDecodeResult.rejected("unsupported_schema_version")
        }

        val playerIdNode = root["playerId"]
        if (playerIdNode == null || !playerIdNode.isString) {
            return PermissionSnapshotInvalidationDecodeResult.rejected("malformed_player_id")
        }
        val playerIdText = playerIdNode.stringValue()
        val playerId =
            try {
                UUID.fromString(playerIdText)
            } catch (_: IllegalArgumentException) {
                return PermissionSnapshotInvalidationDecodeResult.rejected("malformed_player_id")
            }
        if (!playerId.toString().equals(playerIdText, ignoreCase = true)) {
            return PermissionSnapshotInvalidationDecodeResult.rejected("malformed_player_id")
        }
        return PermissionSnapshotInvalidationDecodeResult.accepted(
            PermissionSnapshotInvalidation(schemaVersion, playerId)
        )
    }

    private fun JsonNode?.strictIntValue(): Int? = if (this != null && isInt) intValue() else null

    private companion object {
        val REQUIRED_FIELDS = setOf("schemaVersion", "playerId")
    }
}

internal data class PermissionSnapshotInvalidationDecodeResult(
    val event: PermissionSnapshotInvalidation?,
    val reason: String?,
) {
    companion object {
        fun accepted(event: PermissionSnapshotInvalidation) =
            PermissionSnapshotInvalidationDecodeResult(event, null)

        fun rejected(reason: String) = PermissionSnapshotInvalidationDecodeResult(null, reason)
    }
}
