package gg.grounds.permissions.paper

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionCheckScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.PermissionSnapshotRefreshSweep
import gg.grounds.permissions.Permissions
import gg.grounds.permissions.SnapshotPermissions
import gg.grounds.permissions.client.HttpPermissionRuntimeClient
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionRuntimeStatus
import gg.grounds.permissions.client.PermissionServiceConfig
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.client.SnapshotUnavailableException
import gg.grounds.permissions.invalidation.NatsPermissionSnapshotInvalidationSubscriber
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationConfig
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationCoordinator
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationLifecycle
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal fun interface PaperPermissionRuntimeClientFactory {
    fun create(
        config: PermissionServiceConfig,
        status: PermissionRuntimeStatus,
    ): PermissionRuntimeClient
}

internal fun interface PaperPermissionInvalidationStarter {
    fun start(
        config: PermissionSnapshotInvalidationConfig?,
        snapshots: InMemoryPermissionSnapshots,
        client: PermissionRuntimeClient,
        context: PermissionSnapshotContext,
        isOnline: (UUID) -> Boolean,
        logger: Logger,
        onSnapshotRefreshed: (UUID) -> Unit,
    ): AutoCloseable?
}

internal interface PaperPermissionPlatform {
    fun onlinePlayerIds(): Set<UUID>

    fun onlinePlayers(): Set<PaperPermissionPlayer>

    fun validateInjection()

    fun registerPreLogin(handler: (UUID) -> PermissionLoginResult): AutoCloseable

    fun registerPlayerLogin(handler: (PaperPermissionPlayer) -> PermissionLoginResult): AutoCloseable

    fun registerPlayerLoginRollback(handler: (PaperPermissionPlayer) -> Unit): AutoCloseable

    fun registerQuit(handler: (PaperPermissionPlayer) -> Unit): AutoCloseable

    fun scheduleRefresh(intervalSeconds: Long, task: () -> Unit): AutoCloseable

    fun publish(permissions: Permissions)

    fun unpublish()

    fun injectPermissions(player: PaperPermissionPlayer, permissions: Permissions)

    fun refreshPermissions(playerId: UUID)

    fun retirePermissions(player: PaperPermissionPlayer)

    fun runOnServerThread(task: () -> Unit)

    fun restoreAllPermissions()
}

internal interface PaperPermissionPlayer {
    val playerId: UUID

    val session: Any
}

internal class PaperPermissionsRuntime
internal constructor(
    private val environmentProvider: () -> Map<String, String>,
    private val runtimeClientFactory: PaperPermissionRuntimeClientFactory,
    private val invalidationStarter: PaperPermissionInvalidationStarter,
    private val platform: PaperPermissionPlatform,
    private val logger: Logger = LoggerFactory.getLogger(PaperPermissionsRuntime::class.java),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    constructor(
        platform: PaperPermissionPlatform
    ) : this(System::getenv, DEFAULT_RUNTIME_CLIENT_FACTORY, DEFAULT_INVALIDATION_STARTER, platform)

    private val snapshots = InMemoryPermissionSnapshots()
    private val invalidationLifecycle = PermissionSnapshotInvalidationLifecycle("paper", logger)
    private var preLoginRegistration: AutoCloseable? = null
    private var quitRegistration: AutoCloseable? = null
    private var loginRegistration: AutoCloseable? = null
    private var loginRollbackRegistration: AutoCloseable? = null
    private var refreshRegistration: AutoCloseable? = null
    private var published = false
    private var permissions: Permissions? = null
    private val activeSessions = mutableMapOf<UUID, PaperPermissionPlayer>()

    fun start() {
        stop()
        invalidationLifecycle.replace { install() }
    }

    private fun install(): AutoCloseable? {
        val config = PaperPermissionsConfig.fromEnvironment(environmentProvider()) ?: return null
        val client = runtimeClientFactory.create(config.service, PermissionRuntimeStatus())
        val permissions = SnapshotPermissions(snapshots, config.context.toCheckScope(), clock)
        val loader = PaperPermissionSnapshotLoader(logger, snapshots, client, config.context)
        val refreshSweep =
            PermissionSnapshotRefreshSweep(
                snapshots = snapshots,
                onlinePlayerIds = platform::onlinePlayerIds,
                fetchSnapshot = { playerId -> fetchForRefresh(client, playerId, config.context) },
                clock = clock,
                onSnapshotRefreshed = ::refreshCommandsOnServerThread,
            )

        platform.validateInjection()
        platform.publish(permissions)
        this.permissions = permissions
        published = true
        preLoginRegistration = platform.registerPreLogin(loader::loadSnapshot)
        loginRegistration = platform.registerPlayerLogin(::injectOnLogin)
        loginRollbackRegistration = platform.registerPlayerLoginRollback(::rollbackLogin)
        quitRegistration =
            platform.registerQuit { player ->
                retireSession(player)
            }
        refreshRegistration =
            platform.scheduleRefresh(config.refreshIntervalSeconds) { refreshSweep.run() }
        platform.onlinePlayers().forEach { player ->
            if (snapshots.get(player.playerId) == null) loader.loadSnapshot(player.playerId)
            injectOnLogin(player)
        }
        return invalidationStarter.start(
            config.snapshotInvalidations,
            snapshots,
            client,
            config.context,
            { playerId -> platform.onlinePlayerIds().contains(playerId) },
            logger,
            ::refreshCommandsOnServerThread,
        )
    }

    private fun fetchForRefresh(
        client: PermissionRuntimeClient,
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshot? =
        try {
            client.fetchSnapshot(playerId, context)
        } catch (_: SnapshotUnavailableException) {
            null
        } catch (exception: RuntimeException) {
            logger.error("Permission snapshot refresh failed (playerId={})", playerId, exception)
            null
        }

    fun stop() {
        invalidationLifecycle.close()
        preLoginRegistration.closeQuietly()
        preLoginRegistration = null
        loginRegistration.closeQuietly()
        loginRegistration = null
        loginRollbackRegistration.closeQuietly()
        loginRollbackRegistration = null
        quitRegistration.closeQuietly()
        quitRegistration = null
        refreshRegistration.closeQuietly()
        refreshRegistration = null
        platform.runOnServerThread(platform::restoreAllPermissions)
        activeSessions.clear()
        if (published) platform.unpublish()
        published = false
        permissions = null
    }

    override fun close() = stop()

    internal fun snapshotForTest(playerId: UUID): PermissionSnapshot? = snapshots.get(playerId)

    private fun injectOnLogin(player: PaperPermissionPlayer): PermissionLoginResult {
        val permissions = permissions ?: return PermissionLoginResult.denied()
        if (snapshots.get(player.playerId) == null) return PermissionLoginResult.denied()
        return try {
            platform.injectPermissions(player, permissions)
            activeSessions[player.playerId] = player
            PermissionLoginResult.allowed(snapshots.get(player.playerId)!!)
        } catch (exception: RuntimeException) {
            logger.error("Permission injection failed (playerId={})", player.playerId, exception)
            PermissionLoginResult.denied()
        }
    }

    private fun refreshCommandsOnServerThread(playerId: UUID) {
        platform.runOnServerThread { platform.refreshPermissions(playerId) }
    }

    private fun rollbackLogin(player: PaperPermissionPlayer) = retireSession(player)

    private fun retireSession(player: PaperPermissionPlayer) {
        platform.retirePermissions(player)
        if (activeSessions[player.playerId]?.session !== player.session) return
        activeSessions.remove(player.playerId)
        snapshots.merge(emptyMap()) { candidateId, _ -> candidateId == player.playerId }
    }

    companion object {
        private val DEFAULT_RUNTIME_CLIENT_FACTORY =
            PaperPermissionRuntimeClientFactory { config, status ->
                HttpPermissionRuntimeClient(config = config, status = status)
            }
        private val DEFAULT_INVALIDATION_STARTER =
            PaperPermissionInvalidationStarter {
                config,
                snapshots,
                client,
                context,
                isOnline,
                logger,
                onSnapshotRefreshed ->
                PaperPermissionSnapshotInvalidations.start(
                    config,
                    snapshots,
                    client,
                    context,
                    isOnline,
                    logger,
                    onSnapshotRefreshed,
                )
            }
    }
}

private fun AutoCloseable?.closeQuietly() {
    try {
        this?.close()
    } catch (_: Exception) {
        // Platform shutdown must continue even if a hook cannot be removed.
    }
}

class PaperPermissionSnapshotLoader(
    private val logger: Logger,
    private val snapshots: InMemoryPermissionSnapshots,
    private val client: PermissionRuntimeClient,
    private val context: PermissionSnapshotContext,
) {
    fun loadSnapshot(playerId: UUID): PermissionLoginResult =
        try {
            client
                .fetchSnapshot(playerId, context)
                .also(snapshots::put)
                .let(PermissionLoginResult::allowed)
        } catch (exception: SnapshotUnavailableException) {
            logger.warn(
                "Permission snapshot unavailable (playerId={}, reason={})",
                playerId,
                exception.reason,
            )
            PermissionLoginResult.denied()
        } catch (exception: RuntimeException) {
            logger.error("Permission snapshot load failed (playerId={})", playerId, exception)
            PermissionLoginResult.denied()
        }
}

data class PermissionLoginResult(val allowed: Boolean, val message: String) {
    companion object {
        fun allowed(snapshot: PermissionSnapshot) = PermissionLoginResult(true, "")

        fun denied() =
            PermissionLoginResult(
                false,
                "Permissions are currently unavailable. Please try again later.",
            )
    }
}

data class PaperPermissionsConfig(
    val service: PermissionServiceConfig,
    val context: PermissionSnapshotContext,
    val refreshIntervalSeconds: Long,
    val snapshotInvalidations: PermissionSnapshotInvalidationConfig?,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): PaperPermissionsConfig? {
            val serviceUrl = environment["PERMISSIONS_SERVICE_URL"]?.takeIf(String::isNotBlank)
            val tokenFile = environment["PERMISSIONS_TOKEN_FILE"]?.takeIf(String::isNotBlank)
            if (serviceUrl == null && tokenFile == null) return null
            return PaperPermissionsConfig(
                PermissionServiceConfig.parse(
                    serviceUrl
                        ?: error("Missing required environment variable PERMISSIONS_SERVICE_URL"),
                    tokenFile
                        ?: error("Missing required environment variable PERMISSIONS_TOKEN_FILE"),
                ),
                PermissionSnapshotContext(
                    environment["GROUNDS_PERMISSION_SERVER_TYPE"]?.takeIf(String::isNotBlank),
                    environment["GROUNDS_PERMISSION_SERVER_ID"]?.takeIf(String::isNotBlank),
                    environment["GROUNDS_PERMISSION_ENVIRONMENT"]?.takeIf(String::isNotBlank),
                ),
                environment["PERMISSIONS_REFRESH_INTERVAL_SECONDS"]
                    ?.takeIf(String::isNotBlank)
                    ?.toLong() ?: DEFAULT_REFRESH_INTERVAL_SECONDS,
                PermissionSnapshotInvalidationConfig.fromEnvironment(environment),
            )
        }

        private const val DEFAULT_REFRESH_INTERVAL_SECONDS = 60L
    }
}

private fun PermissionSnapshotContext.toCheckScope() =
    PermissionCheckScope(serverType = serverType, server = serverId, environment = environment)

internal class PaperPermissionSnapshotInvalidations
private constructor(private val subscriber: AutoCloseable, private val executor: ExecutorService) :
    AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                subscriber.close()
            } finally {
                executor.shutdownNow()
            }
        }
    }

    companion object {
        fun start(
            config: PermissionSnapshotInvalidationConfig?,
            snapshots: InMemoryPermissionSnapshots,
            client: PermissionRuntimeClient,
            context: PermissionSnapshotContext,
            isOnline: (UUID) -> Boolean,
            logger: Logger,
            onSnapshotRefreshed: (UUID) -> Unit,
        ): PaperPermissionSnapshotInvalidations? {
            config ?: return null
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val coordinator =
                PermissionSnapshotInvalidationCoordinator(
                    snapshots,
                    isOnline,
                    { playerId -> client.fetchSnapshot(playerId, context) },
                    executor,
                    logger,
                    onSnapshotRefreshed,
                )
            return try {
                PaperPermissionSnapshotInvalidations(
                    NatsPermissionSnapshotInvalidationSubscriber(config, logger).also {
                        it.start(coordinator::invalidate)
                    },
                    executor,
                )
            } catch (exception: RuntimeException) {
                executor.shutdownNow()
                logger.warn(
                    "Permission snapshot invalidation subscriber could not start",
                    exception,
                )
                null
            }
        }
    }
}
