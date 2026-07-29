package gg.grounds.permissions.minestom

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.invalidation.NatsPermissionSnapshotInvalidationSubscriber
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationConfig
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationCoordinator
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import net.minestom.server.MinecraftServer
import org.slf4j.Logger

internal fun interface MinestomInvalidationSubscriberFactory {
    fun subscribe(
        config: PermissionSnapshotInvalidationConfig,
        logger: Logger,
        handler: (UUID) -> Unit,
    ): AutoCloseable
}

internal class MinestomPermissionSnapshotInvalidations
private constructor(
    private val subject: String,
    private val subscriber: AutoCloseable,
    private val executor: ExecutorService,
    private val logger: Logger,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            subscriber.close()
        } catch (_: Exception) {
            logger.warn(
                "Failed to close permission snapshot invalidation subscriber " +
                    "(runtime=minestom, subject={}, reason=subscriber_close_failed)",
                subject,
            )
        }
        try {
            executor.shutdownNow()
        } catch (_: RuntimeException) {
            logger.warn(
                "Failed to close permission snapshot invalidation executor " +
                    "(runtime=minestom, subject={}, reason=executor_close_failed)",
                subject,
            )
        }
    }

    companion object {
        fun start(
            config: PermissionSnapshotInvalidationConfig?,
            snapshots: InMemoryPermissionSnapshots,
            runtimeClient: PermissionRuntimeClient,
            context: PermissionSnapshotContext,
            logger: Logger,
        ): MinestomPermissionSnapshotInvalidations? =
            start(
                config = config,
                snapshots = snapshots,
                runtimeClient = runtimeClient,
                context = context,
                isOnline = { playerId ->
                    MinecraftServer.getConnectionManager().onlinePlayers.any { player ->
                        player.uuid == playerId
                    }
                },
                logger = logger,
            )

        internal fun start(
            config: PermissionSnapshotInvalidationConfig?,
            snapshots: InMemoryPermissionSnapshots,
            runtimeClient: PermissionRuntimeClient,
            context: PermissionSnapshotContext,
            isOnline: (UUID) -> Boolean,
            logger: Logger,
            subscriberFactory: MinestomInvalidationSubscriberFactory = DEFAULT_SUBSCRIBER_FACTORY,
            executorFactory: () -> ExecutorService = DEFAULT_EXECUTOR_FACTORY,
        ): MinestomPermissionSnapshotInvalidations? {
            config ?: return null
            val executor =
                try {
                    executorFactory()
                } catch (_: RuntimeException) {
                    logger.warn(
                        "Failed to start permission snapshot invalidation executor " +
                            "(runtime=minestom, subject={}, reason=executor_start_failed)",
                        config.subject,
                    )
                    return null
                }
            val coordinator =
                PermissionSnapshotInvalidationCoordinator(
                    snapshots = snapshots,
                    isOnline = isOnline,
                    fetchSnapshot = { playerId -> runtimeClient.fetchSnapshot(playerId, context) },
                    executor = executor,
                    logger = logger,
                )
            val subscriber =
                try {
                    subscriberFactory.subscribe(config, logger, coordinator::invalidate)
                } catch (_: RuntimeException) {
                    executor.shutdownNow()
                    logger.warn(
                        "Failed to start permission snapshot invalidation subscriber " +
                            "(runtime=minestom, subject={}, reason=subscriber_start_failed)",
                        config.subject,
                    )
                    return null
                }
            return MinestomPermissionSnapshotInvalidations(
                subject = config.subject,
                subscriber = subscriber,
                executor = executor,
                logger = logger,
            )
        }

        private val DEFAULT_EXECUTOR_FACTORY: () -> ExecutorService = {
            Executors.newVirtualThreadPerTaskExecutor()
        }
        private val DEFAULT_SUBSCRIBER_FACTORY =
            MinestomInvalidationSubscriberFactory { config, logger, handler ->
                NatsPermissionSnapshotInvalidationSubscriber(config, logger).also {
                    it.start(handler)
                }
            }
    }
}
