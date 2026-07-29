package gg.grounds.permissions.invalidation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.Logger

class NatsPermissionSnapshotInvalidationSubscriberTest {
    private val playerId = UUID.fromString("0f287625-2442-4f55-b928-d2f53fbdf575")
    private val config =
        PermissionSnapshotInvalidationConfig(
            natsUrl = "nats://localhost:4222",
            tokenFile = null,
            subject = "permissions.snapshot.invalidated",
        )

    @Test
    fun `initial connection failure schedules a retry without escaping start`() {
        val scheduler = FakeRetryScheduler()
        val factory = FakeConnectionFactory(failuresBeforeSuccess = 1)
        val subscriber = subscriber(factory, scheduler)

        subscriber.start {}
        scheduler.runNext()

        assertEquals(listOf(Duration.ofSeconds(5)), scheduler.delays)
        scheduler.runNext()
        assertEquals(2, factory.connectCount)
    }

    @Test
    fun `a valid payload invokes the handler`() {
        val scheduler = FakeRetryScheduler()
        val factory = FakeConnectionFactory()
        val received = mutableListOf<UUID>()
        val subscriber = subscriber(factory, scheduler)

        subscriber.start(received::add)
        scheduler.runNext()
        factory.connection.deliver("""{"schemaVersion":1,"playerId":"$playerId"}""".toByteArray())

        assertEquals(listOf(playerId), received)
    }

    @Test
    fun `a rejected payload logs only its stable reason`() {
        val scheduler = FakeRetryScheduler()
        val factory = FakeConnectionFactory()
        val logger = RecordingLogger()
        val subscriber = subscriber(factory, scheduler, logger)

        subscriber.start {}
        scheduler.runNext()
        factory.connection.deliver("secret raw payload".toByteArray())

        assertEquals(
            listOf("Rejected permission snapshot invalidation (reason=malformed_json)"),
            logger.renderedMessages().filter {
                it.startsWith("Rejected permission snapshot invalidation")
            },
        )
        assertFalse(logger.renderedMessages().any { it.contains("secret raw payload") })
    }

    @Test
    fun `close cancels pending retries and closes an active connection`() {
        val scheduler = FakeRetryScheduler()
        val failingFactory = FakeConnectionFactory(failuresBeforeSuccess = Int.MAX_VALUE)
        val retryingSubscriber = subscriber(failingFactory, scheduler)
        retryingSubscriber.start {}
        scheduler.runNext()

        retryingSubscriber.close()
        scheduler.runAll()

        assertEquals(1, failingFactory.connectCount)
        assertTrue(scheduler.closed)

        val activeScheduler = FakeRetryScheduler()
        val activeFactory = FakeConnectionFactory()
        val activeSubscriber = subscriber(activeFactory, activeScheduler)
        activeSubscriber.start {}
        activeScheduler.runNext()

        activeSubscriber.close()

        assertTrue(activeFactory.connection.closed)
    }

    @Test
    fun `subscribes once to the core subject without queue or durable configuration`() {
        val scheduler = FakeRetryScheduler()
        val factory = FakeConnectionFactory()
        val subscriber = subscriber(factory, scheduler)

        subscriber.start {}
        scheduler.runNext()

        assertEquals(listOf("permissions.snapshot.invalidated"), factory.connection.subjects)
    }

    @Test
    fun `configured token is reread for every authentication attempt`(@TempDir tempDir: Path) {
        val tokenFile = tempDir.resolve("token")
        Files.writeString(tokenFile, "first-token\n")
        val supplier = FileNatsTokenSupplier(tokenFile)

        assertEquals("first-token", String(supplier.get()))
        Files.writeString(tokenFile, "rotated-token\n")
        assertEquals("rotated-token", String(supplier.get()))
    }

    @Test
    fun `jnats uses infinite reconnects and rereads the configured token`(@TempDir tempDir: Path) {
        val tokenFile = tempDir.resolve("token")
        Files.writeString(tokenFile, "first-token\n")
        val options =
            JnatsPermissionSnapshotInvalidationConnectionFactory()
                .buildOptions(
                    config.copy(
                        credentials = PermissionSnapshotInvalidationCredentials.TokenFile(tokenFile)
                    ),
                    FileNatsTokenSupplier(tokenFile),
                )

        assertEquals(-1, options.maxReconnect)
        assertEquals("first-token", String(options.tokenChars))
        Files.writeString(tokenFile, "rotated-token\n")
        assertEquals("rotated-token", String(options.tokenChars))
    }

    @Test
    fun `an unreadable configured token never downgrades to anonymous access`(
        @TempDir tempDir: Path
    ) {
        val missingToken = tempDir.resolve("missing-token")
        val scheduler = FakeRetryScheduler()
        val factory = FakeConnectionFactory()
        val subscriber =
            subscriber(
                factory,
                scheduler,
                subscriberConfig =
                    config.copy(
                        credentials =
                            PermissionSnapshotInvalidationCredentials.TokenFile(missingToken)
                    ),
            )

        subscriber.start {}
        scheduler.runNext()

        assertEquals(0, factory.connectCount)
        assertEquals(listOf(Duration.ofSeconds(5)), scheduler.delays)
    }

    @Test
    fun `a blank configured token retries without attempting anonymous connection`() {
        val scheduler = FakeRetryScheduler()
        val factory = FakeConnectionFactory()
        val logger = RecordingLogger()
        val subscriber =
            subscriber(
                factory,
                scheduler,
                logger,
                subscriberConfig =
                    requireNotNull(
                        PermissionSnapshotInvalidationConfig.fromEnvironment(
                            mapOf(
                                "NATS_URL" to "nats://localhost:4222",
                                "GROUNDS_TOKEN_FILE" to "\t ",
                            )
                        )
                    ),
            )

        subscriber.start {}
        scheduler.runNext()

        assertEquals(0, factory.connectCount)
        assertEquals(listOf(Duration.ofSeconds(5)), scheduler.delays)
        assertTrue(
            logger
                .renderedMessages()
                .contains(
                    "Failed to subscribe to permission snapshot invalidations " +
                        "(subject=permissions.snapshot.invalidated, reason=invalid_token_file_configuration)"
                )
        )
    }

    @Test
    fun `close racing with initial scheduling does not leak scheduler rejection`() {
        lateinit var subscriber: NatsPermissionSnapshotInvalidationSubscriber
        val scheduler = RejectingExecuteScheduler { subscriber.close() }
        subscriber = subscriber(FakeConnectionFactory(), scheduler)

        assertDoesNotThrow { subscriber.start {} }
    }

    @Test
    fun `close racing with retry scheduling does not leak scheduler rejection`() {
        lateinit var subscriber: NatsPermissionSnapshotInvalidationSubscriber
        val scheduler =
            FakeRetryScheduler(
                onSchedule = {
                    subscriber.close()
                    throw RejectedExecutionException("scheduler closed")
                }
            )
        subscriber = subscriber(FakeConnectionFactory(failuresBeforeSuccess = 1), scheduler)
        subscriber.start {}

        assertDoesNotThrow { scheduler.runNext() }
    }

    @Test
    fun `non lifecycle scheduler failure is logged without escaping start`() {
        val logger = RecordingLogger()
        val scheduler = RejectingExecuteScheduler()
        val subscriber = subscriber(FakeConnectionFactory(), scheduler, logger)

        assertDoesNotThrow { subscriber.start {} }

        assertTrue(
            logger
                .renderedMessages()
                .contains(
                    "Failed to schedule permission snapshot invalidation subscription " +
                        "(subject=permissions.snapshot.invalidated, reason=subscription_scheduling_failed)"
                )
        )
    }

    @Test
    fun `shutdown during subscription does not log a factual success`() {
        lateinit var subscriber: NatsPermissionSnapshotInvalidationSubscriber
        val logger = RecordingLogger()
        val scheduler = FakeRetryScheduler()
        val factory = FakeConnectionFactory()
        factory.connection.onSubscribe = { subscriber.close() }
        subscriber = subscriber(factory, scheduler, logger)

        subscriber.start {}
        scheduler.runNext()

        assertFalse(
            logger.renderedMessages().any {
                it.startsWith("Subscribed to permission snapshot invalidations successfully")
            }
        )
    }

    private fun subscriber(
        factory: FakeConnectionFactory,
        scheduler: PermissionSnapshotInvalidationRetryScheduler,
        logger: Logger = RecordingLogger(),
        subscriberConfig: PermissionSnapshotInvalidationConfig = config,
    ): NatsPermissionSnapshotInvalidationSubscriber =
        NatsPermissionSnapshotInvalidationSubscriber(
            config = subscriberConfig,
            connectionFactory = factory,
            retryScheduler = scheduler,
            logger = logger,
        )

    private class FakeConnectionFactory(private val failuresBeforeSuccess: Int = 0) :
        PermissionSnapshotInvalidationConnectionFactory {
        var connectCount = 0
        val connection = FakeConnection()

        override fun connect(
            config: PermissionSnapshotInvalidationConfig,
            tokenSupplier: NatsTokenSupplier?,
        ): PermissionSnapshotInvalidationConnection {
            connectCount++
            if (connectCount <= failuresBeforeSuccess) {
                error("connection unavailable")
            }
            return connection
        }
    }

    private class FakeConnection : PermissionSnapshotInvalidationConnection {
        val subjects = mutableListOf<String>()
        var closed = false
        var onSubscribe: () -> Unit = {}
        private var handler: ((ByteArray) -> Unit)? = null

        override fun subscribe(subject: String, handler: (ByteArray) -> Unit) {
            subjects += subject
            this.handler = handler
            onSubscribe()
        }

        fun deliver(payload: ByteArray) {
            handler?.invoke(payload)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeRetryScheduler(private val onSchedule: () -> Unit = {}) :
        PermissionSnapshotInvalidationRetryScheduler {
        val delays = mutableListOf<Duration>()
        var closed = false
        private val tasks = ArrayDeque<() -> Unit>()

        override fun execute(task: () -> Unit) {
            tasks.addLast(task)
        }

        override fun schedule(delay: Duration, task: () -> Unit) {
            onSchedule()
            delays += delay
            tasks.addLast(task)
        }

        fun runNext() {
            tasks.removeFirstOrNull()?.invoke()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }

        override fun close() {
            closed = true
            tasks.clear()
        }
    }

    private class RejectingExecuteScheduler(private val beforeReject: () -> Unit = {}) :
        PermissionSnapshotInvalidationRetryScheduler {
        override fun execute(task: () -> Unit) {
            beforeReject()
            throw RejectedExecutionException("scheduler rejected task")
        }

        override fun schedule(delay: Duration, task: () -> Unit) {
            throw AssertionError("retry scheduling is not expected")
        }

        override fun close() = Unit
    }

    private class RecordingLogger : Logger by org.slf4j.helpers.NOPLogger.NOP_LOGGER {
        val debugEvents = mutableListOf<Pair<String, Array<out Any?>>>()
        val warnEvents = mutableListOf<Pair<String, Array<out Any?>>>()

        override fun debug(message: String) {
            debugEvents += message to emptyArray()
        }

        override fun debug(format: String, vararg arguments: Any?) {
            debugEvents += format to arguments
        }

        override fun debug(format: String, argument: Any?) {
            debugEvents += format to arrayOf(argument)
        }

        override fun debug(format: String, firstArgument: Any?, secondArgument: Any?) {
            debugEvents += format to arrayOf(firstArgument, secondArgument)
        }

        override fun warn(format: String, vararg arguments: Any?) {
            warnEvents += format to arguments
        }

        override fun warn(format: String, argument: Any?) {
            warnEvents += format to arrayOf(argument)
        }

        override fun warn(format: String, firstArgument: Any?, secondArgument: Any?) {
            warnEvents += format to arrayOf(firstArgument, secondArgument)
        }

        fun renderedMessages(): List<String> =
            (debugEvents + warnEvents).map { (message, arguments) ->
                arguments.fold(message) { rendered, value -> rendered.replaceFirst("{}", "$value") }
            }
    }
}
