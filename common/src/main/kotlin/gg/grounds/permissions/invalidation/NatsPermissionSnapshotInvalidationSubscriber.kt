package gg.grounds.permissions.invalidation

import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Nats
import io.nats.client.Options
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.Logger

class NatsPermissionSnapshotInvalidationSubscriber
private constructor(
    private val config: PermissionSnapshotInvalidationConfig,
    private val logger: Logger,
    private val codec: PermissionSnapshotInvalidationCodec,
    private val connectionFactory: PermissionSnapshotInvalidationConnectionFactory =
        JnatsPermissionSnapshotInvalidationConnectionFactory(),
    private val retryScheduler: PermissionSnapshotInvalidationRetryScheduler =
        ExecutorPermissionSnapshotInvalidationRetryScheduler(),
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : AutoCloseable {
    constructor(
        config: PermissionSnapshotInvalidationConfig,
        logger: Logger,
        codec: PermissionSnapshotInvalidationCodec = PermissionSnapshotInvalidationCodec(),
    ) : this(config, logger, codec, marker = Unit)

    internal constructor(
        config: PermissionSnapshotInvalidationConfig,
        connectionFactory: PermissionSnapshotInvalidationConnectionFactory,
        retryScheduler: PermissionSnapshotInvalidationRetryScheduler,
        logger: Logger,
        codec: PermissionSnapshotInvalidationCodec = PermissionSnapshotInvalidationCodec(),
    ) : this(config, logger, codec, connectionFactory, retryScheduler, Unit)

    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    @Volatile private var connection: PermissionSnapshotInvalidationConnection? = null

    fun start(handler: (UUID) -> Unit) {
        if (closed.get() || !started.compareAndSet(false, true)) return
        retryScheduler.execute { connect(handler) }
    }

    private fun connect(handler: (UUID) -> Unit) {
        if (closed.get()) return

        var openedConnection: PermissionSnapshotInvalidationConnection? = null
        try {
            val tokenSupplier = config.tokenFile?.let(::FileNatsTokenSupplier)
            tokenSupplier?.get()?.fill('\u0000')
            openedConnection = connectionFactory.connect(config, tokenSupplier)
            openedConnection.subscribe(config.subject) { payload -> consume(payload, handler) }
            connection = openedConnection
            if (closed.get()) {
                if (connection === openedConnection) {
                    connection = null
                    openedConnection.close()
                }
                return
            }
            logger.debug(
                "Subscribed to permission snapshot invalidations successfully (subject={})",
                config.subject,
            )
        } catch (_: Exception) {
            closeQuietly(openedConnection)
            logger.warn(
                "Failed to subscribe to permission snapshot invalidations " +
                    "(subject={}, reason=nats_connection_failed)",
                config.subject,
            )
            retry(handler)
        }
    }

    private fun consume(payload: ByteArray, handler: (UUID) -> Unit) {
        val decoded = codec.decodeResult(payload)
        val event = decoded.event
        if (event == null) {
            logger.debug(
                "Rejected permission snapshot invalidation " +
                    "(reason=${decoded.reason ?: "malformed_event"})"
            )
            return
        }

        try {
            handler(event.playerId)
        } catch (_: RuntimeException) {
            logger.warn(
                "Failed to handle permission snapshot invalidation " +
                    "(playerId={}, reason=handler_failed)",
                event.playerId,
            )
        }
    }

    private fun retry(handler: (UUID) -> Unit) {
        if (closed.get()) return
        retryScheduler.schedule(INITIAL_RETRY_DELAY) { if (!closed.get()) connect(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        retryScheduler.close()
        closeQuietly(connection)
        connection = null
    }

    private fun closeQuietly(connection: PermissionSnapshotInvalidationConnection?) {
        try {
            connection?.close()
        } catch (_: Exception) {
            logger.warn(
                "Failed to close permission snapshot invalidation connection " +
                    "(subject={}, reason=nats_close_failed)",
                config.subject,
            )
        }
    }

    private companion object {
        val INITIAL_RETRY_DELAY: Duration = Duration.ofSeconds(5)
    }
}

internal fun interface NatsTokenSupplier {
    fun get(): CharArray
}

internal class FileNatsTokenSupplier(private val tokenFile: Path) : NatsTokenSupplier {
    override fun get(): CharArray {
        val token = Files.readString(tokenFile).trim()
        require(token.isNotEmpty()) { "Configured NATS token file is empty" }
        return token.toCharArray()
    }
}

internal interface PermissionSnapshotInvalidationConnectionFactory {
    fun connect(
        config: PermissionSnapshotInvalidationConfig,
        tokenSupplier: NatsTokenSupplier?,
    ): PermissionSnapshotInvalidationConnection
}

internal interface PermissionSnapshotInvalidationConnection : AutoCloseable {
    fun subscribe(subject: String, handler: (ByteArray) -> Unit)
}

internal class JnatsPermissionSnapshotInvalidationConnectionFactory :
    PermissionSnapshotInvalidationConnectionFactory {
    override fun connect(
        config: PermissionSnapshotInvalidationConfig,
        tokenSupplier: NatsTokenSupplier?,
    ): PermissionSnapshotInvalidationConnection {
        return JnatsPermissionSnapshotInvalidationConnection(
            Nats.connect(buildOptions(config, tokenSupplier))
        )
    }

    internal fun buildOptions(
        config: PermissionSnapshotInvalidationConfig,
        tokenSupplier: NatsTokenSupplier?,
    ): Options =
        Options.Builder()
            .server(config.natsUrl)
            .maxReconnects(-1)
            .apply { tokenSupplier?.let { supplier -> tokenSupplier(supplier::get) } }
            .build()
}

private class JnatsPermissionSnapshotInvalidationConnection(private val connection: Connection) :
    PermissionSnapshotInvalidationConnection {
    @Volatile private var dispatcher: Dispatcher? = null

    override fun subscribe(subject: String, handler: (ByteArray) -> Unit) {
        check(dispatcher == null) { "Permission snapshot invalidation subscription already exists" }
        dispatcher =
            connection
                .createDispatcher { message -> handler(message.data) }
                .apply { subscribe(subject) }
    }

    override fun close() {
        dispatcher?.let(connection::closeDispatcher)
        connection.close()
    }
}

internal interface PermissionSnapshotInvalidationRetryScheduler : AutoCloseable {
    fun execute(task: () -> Unit)

    fun schedule(delay: Duration, task: () -> Unit)
}

private class ExecutorPermissionSnapshotInvalidationRetryScheduler(
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "permission-snapshot-invalidation").apply { isDaemon = true }
        }
) : PermissionSnapshotInvalidationRetryScheduler {
    override fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    override fun schedule(delay: Duration, task: () -> Unit) {
        executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
