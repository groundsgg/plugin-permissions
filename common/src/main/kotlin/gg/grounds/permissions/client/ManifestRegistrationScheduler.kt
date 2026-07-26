package gg.grounds.permissions.client

import gg.grounds.permissions.catalog.PermissionManifest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToLong

fun interface ScheduledRegistration {
    fun cancel()
}

interface RegistrationDelayScheduler : AutoCloseable {
    fun schedule(delay: Duration, task: () -> Unit): ScheduledRegistration

    override fun close() {}
}

class ManifestRegistrationScheduler(
    private val client: PermissionRuntimeClient,
    private val delayScheduler: RegistrationDelayScheduler = ExecutorRegistrationDelayScheduler(),
    private val status: PermissionRuntimeStatus = PermissionRuntimeStatus(),
    private val clock: Clock = Clock.systemUTC(),
    private val jitterSource: () -> Double = Math::random,
    private val jitterRatio: Double = DEFAULT_JITTER_RATIO,
) : AutoCloseable {
    private val registrations = ConcurrentHashMap<String, RegistrationState>()
    private val closed = AtomicBoolean()
    private val logger = System.getLogger(ManifestRegistrationScheduler::class.java.name)

    init {
        require(jitterRatio in 0.0..1.0) { "Manifest retry jitter ratio must be between 0 and 1" }
    }

    fun register(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ) {
        check(!closed.get()) { "Manifest registration scheduler is closed" }
        val state = RegistrationState(manifest, sourceVersion, context)
        if (registrations.putIfAbsent(manifest.source, state) == null) {
            schedule(state, Duration.ZERO)
        }
    }

    private fun execute(state: RegistrationState) {
        if (closed.get()) return
        when (
            val result =
                try {
                    client.registerManifest(state.manifest, state.sourceVersion, state.context)
                } catch (exception: RuntimeException) {
                    PermissionManifestRegistrationResult.TerminalFailure("client_error")
                }
        ) {
            PermissionManifestRegistrationResult.Accepted -> {
                registrations.remove(state.manifest.source, state)
                status.recordManifestSuccess(clock.instant())
                logger.log(
                    System.Logger.Level.INFO,
                    "Permission manifest registered successfully (source=${state.manifest.source}, permissionCount=${state.manifest.permissions.size})",
                )
            }
            is PermissionManifestRegistrationResult.RetryableFailure -> {
                if (closed.get()) return
                state.retryCount++
                status.recordManifestRetry()
                val retryDelay = retryDelay(state.retryCount)
                logger.log(
                    System.Logger.Level.WARNING,
                    "Permission manifest registration scheduled (source=${state.manifest.source}, retryInMs=${retryDelay.toMillis()}, reason=${result.reason}, requestId=${result.requestId ?: "none"})",
                )
                schedule(state, retryDelay)
            }
            is PermissionManifestRegistrationResult.TerminalFailure -> {
                registrations.remove(state.manifest.source, state)
                status.recordTerminalManifestFailure(
                    ManifestTerminalFailureStatus(
                        source = state.manifest.source,
                        statusCode = result.statusCode,
                        requestId = result.requestId,
                        failedAt = clock.instant(),
                    )
                )
                logger.log(
                    System.Logger.Level.ERROR,
                    "Permission manifest registration failed (source=${state.manifest.source}, status=${result.statusCode ?: "none"}, retryable=false, reason=${result.reason}, requestId=${result.requestId ?: "none"})",
                )
            }
        }
    }

    private fun schedule(state: RegistrationState, delay: Duration) {
        state.scheduled = delayScheduler.schedule(delay) { execute(state) }
    }

    private fun retryDelay(retryCount: Int): Duration {
        val exponent = min(retryCount - 1, MAX_EXPONENT)
        val baseMillis =
            min(MAX_RETRY_DELAY.toMillis(), INITIAL_RETRY_DELAY.toMillis() shl exponent)
        val boundedJitter = jitterSource().coerceIn(0.0, 1.0)
        val factor = 1.0 - jitterRatio + (2.0 * jitterRatio * boundedJitter)
        return Duration.ofMillis(
            min(MAX_RETRY_DELAY.toMillis(), (baseMillis * factor).roundToLong())
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        registrations.values.forEach { it.scheduled?.cancel() }
        registrations.clear()
        delayScheduler.close()
    }

    private class RegistrationState(
        val manifest: PermissionManifest,
        val sourceVersion: String,
        val context: PermissionSnapshotContext,
        var retryCount: Int = 0,
        @Volatile var scheduled: ScheduledRegistration? = null,
    )

    private companion object {
        val INITIAL_RETRY_DELAY: Duration = Duration.ofSeconds(1)
        val MAX_RETRY_DELAY: Duration = Duration.ofSeconds(60)
        const val MAX_EXPONENT = 6
        const val DEFAULT_JITTER_RATIO = 0.2
    }
}

private class ExecutorRegistrationDelayScheduler(
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "permissions-manifest-registration").apply { isDaemon = true }
        }
) : RegistrationDelayScheduler {
    override fun schedule(delay: Duration, task: () -> Unit): ScheduledRegistration {
        val future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS)
        return ScheduledRegistration { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
