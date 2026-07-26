package gg.grounds.permissions.client

import java.util.concurrent.atomic.AtomicLong

class PermissionRuntimeStatus {
    private val snapshotSuccesses = AtomicLong()
    private val snapshotFailures = AtomicLong()
    private val validCacheFallbacks = AtomicLong()
    private val failClosedDecisions = AtomicLong()

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

    fun snapshot(): PermissionRuntimeStatusSnapshot =
        PermissionRuntimeStatusSnapshot(
            snapshotSuccesses = snapshotSuccesses.get(),
            snapshotFailures = snapshotFailures.get(),
            validCacheFallbacks = validCacheFallbacks.get(),
            failClosedDecisions = failClosedDecisions.get(),
        )
}

data class PermissionRuntimeStatusSnapshot(
    val snapshotSuccesses: Long,
    val snapshotFailures: Long,
    val validCacheFallbacks: Long,
    val failClosedDecisions: Long,
)
