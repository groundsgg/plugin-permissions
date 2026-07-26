package gg.grounds.permissions.client

enum class SnapshotFailureReason {
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    INVALID_RESPONSE,
    UNAVAILABLE,
}

class SnapshotUnavailableException(
    val reason: SnapshotFailureReason,
    val statusCode: Int? = null,
    val requestId: String? = null,
    cause: Throwable? = null,
) :
    RuntimeException(
        "Permission snapshot unavailable (reason=${reason.name.lowercase()}, status=${statusCode ?: "none"}, requestId=${requestId ?: "none"})",
        cause,
    )
