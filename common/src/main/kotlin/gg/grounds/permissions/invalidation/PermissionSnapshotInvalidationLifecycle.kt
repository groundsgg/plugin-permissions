package gg.grounds.permissions.invalidation

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.Logger

class PermissionSnapshotInvalidationLifecycle(
    private val runtime: String,
    private val logger: Logger,
) : AutoCloseable {
    private val replacementLock = ReentrantLock()
    private val stateLock = Any()
    private var generation = 0L
    private var active: AutoCloseable? = null

    fun replace(factory: () -> AutoCloseable?) {
        val (replacementGeneration, previous) =
            synchronized(stateLock) {
                generation++
                generation to active.also { active = null }
            }
        replacementLock.withLock {
            closeQuietly(previous)
            if (synchronized(stateLock) { generation != replacementGeneration }) return

            val replacement = factory() ?: return
            val stale =
                synchronized(stateLock) {
                    if (generation == replacementGeneration) {
                        active = replacement
                        null
                    } else {
                        replacement
                    }
                }
            closeQuietly(stale)
        }
    }

    override fun close() {
        val previous =
            synchronized(stateLock) {
                generation++
                active.also { active = null }
            }
        closeQuietly(previous)
    }

    private fun closeQuietly(handle: AutoCloseable?) {
        try {
            handle?.close()
        } catch (_: Exception) {
            logger.warn(
                "Failed to close permission snapshot invalidation lifecycle handle " +
                    "(runtime={}, reason=handle_close_failed)",
                runtime,
            )
        }
    }
}
