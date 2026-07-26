package gg.grounds.permissions.velocity

import com.google.inject.Inject
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.permission.PermissionsSetupEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import gg.grounds.BuildInfo
import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionCheckScope
import gg.grounds.permissions.PermissionSnapshotRefreshSweep
import gg.grounds.permissions.Permissions
import gg.grounds.permissions.SnapshotPermissions
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.catalog.PermissionManifestCollector
import gg.grounds.permissions.client.HttpPermissionRuntimeClient
import gg.grounds.permissions.client.ManifestRegistrationScheduler
import gg.grounds.permissions.client.PermissionRuntimeStatus
import gg.grounds.permissions.client.PermissionServiceConfig
import gg.grounds.permissions.client.PermissionSnapshotContext
import gg.grounds.permissions.client.SnapshotUnavailableException
import java.util.concurrent.TimeUnit
import org.slf4j.Logger

@Plugin(
    id = "plugin-permissions",
    name = "Grounds Permissions Plugin",
    version = BuildInfo.VERSION,
    description = "Loads and caches Minecraft network permission snapshots",
    authors = ["Grounds Development Team and contributors"],
    url = "https://github.com/groundsgg/plugin-permissions",
)
class GroundsPermissionsPlugin
@Inject
constructor(private val proxy: ProxyServer, private val logger: Logger) {
    private var manifestScheduler: ManifestRegistrationScheduler? = null
    private var commandMeta: CommandMeta? = null
    private var refreshTask: ScheduledTask? = null
    private var permissions: Permissions? = null

    init {
        logger.info(
            "Permissions plugin initialized successfully (plugin=plugin-permissions, version={})",
            BuildInfo.VERSION,
        )
    }

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val config =
            VelocityPermissionsConfig.fromEnvironment(System.getenv())
                ?: run {
                    logger.info(
                        "Permissions plugin disabled (plugin=plugin-permissions, reason=not_configured)"
                    )
                    return
                }
        val runtimeStatus = PermissionRuntimeStatus()
        val runtimeClient =
            HttpPermissionRuntimeClient(config = config.service, status = runtimeStatus)
        val manifestScheduler =
            ManifestRegistrationScheduler(client = runtimeClient, status = runtimeStatus)
        this.manifestScheduler = manifestScheduler

        val snapshots = InMemoryPermissionSnapshots()
        val listener =
            PermissionLoginListener(
                logger = logger,
                snapshots = snapshots,
                client = runtimeClient,
                context = config.context,
            )
        proxy.eventManager.register(this, listener)

        val refreshSweep =
            PermissionSnapshotRefreshSweep(
                snapshots = snapshots,
                onlinePlayerIds = { proxy.allPlayers.map { it.uniqueId }.toSet() },
                fetchSnapshot = { playerId ->
                    try {
                        runtimeClient
                            .fetchSnapshot(playerId, config.context)
                            .also(listener::activateSnapshot)
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
            )
        refreshTask =
            proxy.scheduler
                .buildTask(
                    this,
                    Runnable {
                        try {
                            refreshSweep.run()
                        } catch (exception: RuntimeException) {
                            logger.error(
                                "Permission snapshot refresh sweep failed (serverType={}, serverId={}, exceptionType={})",
                                config.context.serverType ?: "none",
                                config.context.serverId ?: "none",
                                exception::class.java.name,
                            )
                        }
                    },
                )
                .repeat(config.refreshIntervalSeconds, TimeUnit.SECONDS)
                .schedule()

        val permissions =
            SnapshotPermissions(snapshots, defaultScope = config.context.toCheckScope())
        this.permissions = permissions
        loadCommandPermissions()?.let { commandPermissions ->
            val router =
                PermissionCommandRouter(
                    service =
                        PermissionCommandService(
                            snapshots = snapshots,
                            permissions = permissions,
                            refreshSnapshot = { playerId, _ -> listener.loadSnapshot(playerId) },
                            status =
                                PermissionCommandStatus(
                                    version = BuildInfo.VERSION,
                                    serviceUrl = config.service.serviceUri,
                                    context = config.context,
                                    runtimeStatus = runtimeStatus::snapshot,
                                ),
                        ),
                    findOnlinePlayer = { identifier ->
                        VelocityPermissionsCommand.findOnlinePlayer(proxy, identifier)
                    },
                    onlinePlayers = { VelocityPermissionsCommand.onlinePlayers(proxy) },
                    defaultScope =
                        PermissionCheckScopeArgument(
                            serverType = config.context.serverType,
                            server = config.context.serverId,
                        ),
                )
            val command =
                VelocityPermissionsCommand(
                    plugin = this,
                    proxy = proxy,
                    router = router,
                    commandPermissions = commandPermissions,
                    isAuthorized = { source, permission ->
                        VelocityPermissionsCommand.isPlayerAuthorized(
                            source,
                            permissions,
                            permission,
                        )
                    },
                )
            val commandMeta =
                proxy.commandManager.metaBuilder("permissions").aliases("perm").plugin(this).build()
            proxy.commandManager.register(commandMeta, command)
            this.commandMeta = commandMeta

            logger.info(
                "Permissions commands registered successfully (root=permissions, alias=perm)"
            )
        }

        registerActivePermissionManifests(manifestScheduler, config)

        logger.info(
            "Permissions plugin configured successfully (serviceUrl={}, serverType={}, serverId={})",
            config.service.serviceUri,
            config.context.serverType ?: "none",
            config.context.serverId ?: "none",
        )
    }

    @Subscribe
    fun onPermissionsSetup(event: PermissionsSetupEvent) {
        permissions?.let { event.provider = SnapshotPermissionProvider(it, event.provider) }
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        commandMeta?.let(proxy.commandManager::unregister)
        commandMeta = null
        refreshTask?.cancel()
        refreshTask = null
        manifestScheduler?.close()
        manifestScheduler = null
        permissions = null
    }

    private fun loadCommandPermissions(): PermissionCommandPermissions? =
        try {
            PermissionCommandPermissions.fromManifest(
                PermissionManifest.loadRequiredResource(javaClass.classLoader)
            )
        } catch (exception: IllegalArgumentException) {
            logger.warn(
                "Permissions command registration skipped (originId=plugin-permissions, reason={})",
                exception.message ?: exception::class.java.simpleName,
            )
            null
        }

    private fun registerActivePermissionManifests(
        manifestScheduler: ManifestRegistrationScheduler,
        config: VelocityPermissionsConfig,
    ) {
        val collection =
            PermissionManifestCollector()
                .collect(discoverPermissionManifestOrigins(proxy.pluginManager.plugins))
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
                context = config.context,
            )
        }
    }
}

data class VelocityPermissionsConfig(
    val service: PermissionServiceConfig,
    val context: PermissionSnapshotContext,
    val refreshIntervalSeconds: Long,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): VelocityPermissionsConfig? {
            val serviceUrl = environment["PERMISSIONS_SERVICE_URL"]?.takeIf { it.isNotBlank() }
            val tokenFile = environment["PERMISSIONS_TOKEN_FILE"]?.takeIf { it.isNotBlank() }
            if (serviceUrl == null && tokenFile == null) return null
            val requiredServiceUrl =
                serviceUrl ?: error("Missing required environment variable PERMISSIONS_SERVICE_URL")
            val requiredTokenFile =
                tokenFile ?: error("Missing required environment variable PERMISSIONS_TOKEN_FILE")
            return VelocityPermissionsConfig(
                service = PermissionServiceConfig.parse(requiredServiceUrl, requiredTokenFile),
                context =
                    PermissionSnapshotContext(
                        serverType =
                            environment["GROUNDS_PERMISSION_SERVER_TYPE"]?.takeIf {
                                it.isNotBlank()
                            },
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
            )
        }

        private const val DEFAULT_REFRESH_INTERVAL_SECONDS = 60L
    }
}

fun PermissionSnapshotContext.toCheckScope(): PermissionCheckScope =
    PermissionCheckScope(serverType = serverType, server = serverId, environment = environment)
