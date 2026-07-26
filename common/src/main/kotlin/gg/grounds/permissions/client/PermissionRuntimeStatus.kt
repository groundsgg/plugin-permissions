package gg.grounds.permissions.client

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PermissionRuntimeStatus {
    private val snapshotSuccesses = AtomicLong()
    private val snapshotFailures = AtomicLong()
    private val validCacheFallbacks = AtomicLong()
    private val failClosedDecisions = AtomicLong()
    private val manifestRetries = AtomicLong()
    private val lastManifestSuccessAt = AtomicReference<Instant?>(null)
    private val terminalManifestFailures = AtomicLong()
    private val lastTerminalManifestFailure = AtomicReference<ManifestTerminalFailureStatus?>(null)

    internal fun recordSnapshotSuccess() {
        snapshotSuccesses.incrementAndGet()
    }

    internal fun recordSnapshotFailure() {
        snapshotFailures.incrementAndGet()
    }

    internal fun recordValidCacheFallback() {
        validCacheFallbacks.incrementAndGet()
    }

    internal fun recordFailClosedDecision() {
        failClosedDecisions.incrementAndGet()
    }

    internal fun recordManifestRetry() {
        manifestRetries.incrementAndGet()
    }

    internal fun recordManifestSuccess(at: Instant) {
        lastManifestSuccessAt.set(at)
    }

    internal fun recordTerminalManifestFailure(failure: ManifestTerminalFailureStatus) {
        terminalManifestFailures.incrementAndGet()
        lastTerminalManifestFailure.set(failure)
    }

    fun snapshot(): PermissionRuntimeStatusSnapshot =
        PermissionRuntimeStatusSnapshot(
            snapshotSuccesses = snapshotSuccesses.get(),
            snapshotFailures = snapshotFailures.get(),
            validCacheFallbacks = validCacheFallbacks.get(),
            failClosedDecisions = failClosedDecisions.get(),
            manifestRetries = manifestRetries.get(),
            lastManifestSuccessAt = lastManifestSuccessAt.get(),
            terminalManifestFailures = terminalManifestFailures.get(),
            lastTerminalManifestFailure = lastTerminalManifestFailure.get(),
        )
}

data class PermissionRuntimeStatusSnapshot(
    val snapshotSuccesses: Long,
    val snapshotFailures: Long,
    val validCacheFallbacks: Long,
    val failClosedDecisions: Long,
    val manifestRetries: Long = 0,
    val lastManifestSuccessAt: Instant? = null,
    val terminalManifestFailures: Long = 0,
    val lastTerminalManifestFailure: ManifestTerminalFailureStatus? = null,
)

data class ManifestTerminalFailureStatus(
    val source: String,
    val statusCode: Int?,
    val requestId: String?,
    val failedAt: Instant,
)
