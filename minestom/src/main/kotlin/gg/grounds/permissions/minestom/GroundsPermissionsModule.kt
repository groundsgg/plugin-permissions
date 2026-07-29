package gg.grounds.permissions.minestom

import gg.grounds.modules.register
import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionCheckScope
import gg.grounds.permissions.PermissionSnapshotRefreshSweep
import gg.grounds.permissions.Permissions
import gg.grounds.permissions.SnapshotPermissions
import gg.grounds.permissions.client.HttpPermissionRuntimeClient
import gg.grounds.permissions.client.ManifestRegistrationScheduler
import gg.grounds.permissions.client.PermissionRuntimeClient
import gg.grounds.permissions.client.PermissionRuntimeStatus
import gg.grounds.permissions.client.PermissionServiceConfig
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.client.SnapshotUnavailableException
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationConfig
import gg.grounds.permissions.invalidation.PermissionSnapshotInvalidationLifecycle
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal fun interface MinestomPermissionRuntimeClientFactory {
    fun create(
        config: PermissionServiceConfig,
        status: PermissionRuntimeStatus,
    ): PermissionRuntimeClient
}

internal fun interface MinestomPermissionSnapshotInvalidationStarter {
    fun start(
        config: PermissionSnapshotInvalidationConfig?,
        snapshots: InMemoryPermissionSnapshots,
        runtimeClient: PermissionRuntimeClient,
        context: PermissionSnapshotContext,
        logger: Logger,
    ): AutoCloseable?
}

internal interface MinestomPermissionRuntimePlatform {
    fun onlinePlayerIds(): Set<UUID>

    fun addEventNode(node: EventNode<Event>)

    fun removeEventNode(node: EventNode<Event>)
}

class GroundsPermissionsModule
internal constructor(
    private val environmentProvider: () -> Map<String, String>,
    private val runtimeClientFactory: MinestomPermissionRuntimeClientFactory,
    private val snapshotInvalidationStarter: MinestomPermissionSnapshotInvalidationStarter,
    private val runtimePlatform: MinestomPermissionRuntimePlatform,
    private val clock: Clock = Clock.systemUTC(),
) : GroundsModule {
    constructor() :
        this(
            System::getenv,
            DEFAULT_RUNTIME_CLIENT_FACTORY,
            DEFAULT_SNAPSHOT_INVALIDATION_STARTER,
            DEFAULT_RUNTIME_PLATFORM,
            Clock.systemUTC(),
        )

    constructor(
        clock: Clock
    ) : this(
        System::getenv,
        DEFAULT_RUNTIME_CLIENT_FACTORY,
        DEFAULT_SNAPSHOT_INVALIDATION_STARTER,
        DEFAULT_RUNTIME_PLATFORM,
        clock,
    )

    private val logger: Logger = LoggerFactory.getLogger(GroundsPermissionsModule::class.java)
    private val snapshots = InMemoryPermissionSnapshots()
    private var manifestScheduler: ManifestRegistrationScheduler? = null
    private var refreshExecutor: ScheduledExecutorService? = null
    private var eventNode: EventNode<Event>? = null
    private val snapshotInvalidationLifecycle =
        PermissionSnapshotInvalidationLifecycle(runtime = "minestom", logger = logger)

    override val id: String = MODULE_ID

    override fun install(ctx: GroundsServerContext) {
        stop()
        snapshotInvalidationLifecycle.replace { installRuntime(ctx) }
    }

    private fun installRuntime(ctx: GroundsServerContext): AutoCloseable? {
        val config =
            MinestomPermissionsConfig.fromEnvironment(
                environment = environmentProvider(),
                fallbackServerType = ctx.serverType.name.lowercase(),
            )
                ?: run {
                    logger.info(
                        "Permissions module disabled (serverType={}, reason=not_configured)",
                        ctx.serverType.name.lowercase(),
                    )
                    return null
                }
        val runtimeStatus = PermissionRuntimeStatus()
        val runtimeClient = runtimeClientFactory.create(config.service, runtimeStatus)
        val manifestScheduler =
            ManifestRegistrationScheduler(client = runtimeClient, status = runtimeStatus)
        val permissions =
            SnapshotPermissions(
                snapshots = snapshots,
                defaultScope = config.context.toCheckScope(),
                clock = clock,
            )
        val loader =
            MinestomPermissionSnapshotLoader(
                logger = logger,
                snapshots = snapshots,
                client = runtimeClient,
                context = config.context,
            )
        val refreshSweep =
            PermissionSnapshotRefreshSweep(
                snapshots = snapshots,
                onlinePlayerIds = runtimePlatform::onlinePlayerIds,
                fetchSnapshot = { playerId ->
                    try {
                        runtimeClient.fetchSnapshot(playerId, config.context)
                    } catch (exception: SnapshotUnavailableException) {
                        null
                    } catch (exception: RuntimeException) {
                        logger.error(
                            "Permission snapshot refresh failed (playerId={}, exceptionType={})",
                            playerId,
                            exception::class.java.name,
                        )
                        null
                    }
                },
                clock = clock,
            )
        val refreshExecutor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "grounds-permissions-refresh").apply { isDaemon = true }
            }
        refreshExecutor.scheduleAtFixedRate(
            {
                try {
                    refreshSweep.run()
                } catch (exception: RuntimeException) {
                    logger.error(
                        "Permission snapshot refresh sweep failed (serverType={}, serverId={}, exceptionType={})",
                        config.context.serverType,
                        config.context.serverId ?: "none",
                        exception::class.java.name,
                    )
                }
            },
            config.refreshIntervalSeconds,
            config.refreshIntervalSeconds,
            TimeUnit.SECONDS,
        )

        ctx.services.register<Permissions>(permissions)

        val node = ctx.eventNode("grounds-permissions")
        PermissionPlayerListener(loader).register(node)
        runtimePlatform.addEventNode(node)
        eventNode = node
        this.manifestScheduler = manifestScheduler
        this.refreshExecutor = refreshExecutor

        ctx.onShutdown { stop() }

        registerActivePermissionManifests(
            activeProviders = ctx.activeModuleProviders,
            manifestScheduler = manifestScheduler,
            context = config.context,
        )

        val snapshotInvalidations =
            snapshotInvalidationStarter.start(
                config = config.snapshotInvalidations,
                snapshots = snapshots,
                runtimeClient = runtimeClient,
                context = config.context,
                logger = logger,
            )

        logger.info(
            "Permissions module installed successfully (serverType={}, serverId={}, serviceUrl={})",
            config.context.serverType,
            config.context.serverId ?: "none",
            config.service.serviceUri,
        )
        return snapshotInvalidations
    }

    override fun stop() {
        snapshotInvalidationLifecycle.close()
        eventNode?.let(runtimePlatform::removeEventNode)
        eventNode = null
        manifestScheduler?.close()
        manifestScheduler = null
        refreshExecutor?.shutdownNow()
        refreshExecutor = null
    }

    private fun registerActivePermissionManifests(
        activeProviders: Iterable<gg.grounds.runtime.ActiveGroundsModuleProvider>,
        manifestScheduler: ManifestRegistrationScheduler,
        context: PermissionSnapshotContext,
    ) {
        val collection = collectActivePermissionManifests(activeProviders)
        collection.failures.forEach { failure ->
            logger.warn(
                "Permission manifest skipped (originId={}, originVersion={}, reason={})",
                failure.origin.id,
                failure.origin.version,
                failure.reason,
            )
        }
        collection.manifests.forEach { collected ->
            manifestScheduler.register(
                manifest = collected.manifest,
                sourceVersion = collected.origin.version,
                context = context,
            )
        }
    }

    companion object {
        const val MODULE_ID: String = "grounds.permissions"

        private val DEFAULT_RUNTIME_CLIENT_FACTORY =
            MinestomPermissionRuntimeClientFactory { config, status ->
                HttpPermissionRuntimeClient(config = config, status = status)
            }
        private val DEFAULT_SNAPSHOT_INVALIDATION_STARTER =
            MinestomPermissionSnapshotInvalidationStarter {
                config,
                snapshots,
                runtimeClient,
                context,
                logger ->
                MinestomPermissionSnapshotInvalidations.start(
                    config = config,
                    snapshots = snapshots,
                    runtimeClient = runtimeClient,
                    context = context,
                    logger = logger,
                )
            }
        private val DEFAULT_RUNTIME_PLATFORM =
            object : MinestomPermissionRuntimePlatform {
                override fun onlinePlayerIds(): Set<UUID> =
                    MinecraftServer.getConnectionManager().onlinePlayers.map { it.uuid }.toSet()

                override fun addEventNode(node: EventNode<Event>) {
                    MinecraftServer.getGlobalEventHandler().addChild(node)
                }

                override fun removeEventNode(node: EventNode<Event>) {
                    MinecraftServer.getGlobalEventHandler().removeChild(node)
                }
            }
    }
}

data class MinestomPermissionsConfig(
    val service: PermissionServiceConfig,
    val context: PermissionSnapshotContext,
    val refreshIntervalSeconds: Long,
    val snapshotInvalidations: PermissionSnapshotInvalidationConfig? = null,
) {
    companion object {
        fun fromEnvironment(
            environment: Map<String, String>,
            fallbackServerType: String,
        ): MinestomPermissionsConfig? {
            val serviceUrl = environment["PERMISSIONS_SERVICE_URL"]?.takeIf { it.isNotBlank() }
            val tokenFile = environment["PERMISSIONS_TOKEN_FILE"]?.takeIf { it.isNotBlank() }
            if (serviceUrl == null && tokenFile == null) return null
            val requiredServiceUrl =
                serviceUrl ?: error("Missing required environment variable PERMISSIONS_SERVICE_URL")
            val requiredTokenFile =
                tokenFile ?: error("Missing required environment variable PERMISSIONS_TOKEN_FILE")
            return MinestomPermissionsConfig(
                service = PermissionServiceConfig.parse(requiredServiceUrl, requiredTokenFile),
                context =
                    PermissionSnapshotContext(
                        serverType =
                            environment["GROUNDS_PERMISSION_SERVER_TYPE"]?.takeIf {
                                it.isNotBlank()
                            } ?: fallbackServerType,
                        serverId =
                            environment["GROUNDS_PERMISSION_SERVER_ID"]?.takeIf { it.isNotBlank() },
                        environment =
                            environment["GROUNDS_PERMISSION_ENVIRONMENT"]?.takeIf {
                                it.isNotBlank()
                            },
                    ),
                refreshIntervalSeconds =
                    environment["PERMISSIONS_REFRESH_INTERVAL_SECONDS"]
                        ?.takeIf { it.isNotBlank() }
                        ?.toLong() ?: DEFAULT_REFRESH_INTERVAL_SECONDS,
                snapshotInvalidations =
                    PermissionSnapshotInvalidationConfig.fromEnvironment(environment),
            )
        }

        private const val DEFAULT_REFRESH_INTERVAL_SECONDS = 60L
    }
}

fun PermissionSnapshotContext.toCheckScope(): PermissionCheckScope =
    PermissionCheckScope(serverType = serverType, server = serverId, environment = environment)
