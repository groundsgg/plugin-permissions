package gg.grounds.permissions.velocity

import com.google.inject.Inject
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.permission.PermissionsSetupEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
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
import io.grpc.LoadBalancerRegistry
import io.grpc.NameResolverRegistry
import io.grpc.internal.DnsNameResolverProvider
import io.grpc.internal.PickFirstLoadBalancerProvider
import java.nio.file.Path
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
constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path,
) {
    private var client: PermissionSnapshotClient? = null
    private var catalogClient: PermissionCatalogClient? = null
    private var commandMeta: CommandMeta? = null
    private var refreshTask: ScheduledTask? = null
    private var permissions: Permissions? = null

    init {
        logger.info("Initialized plugin (plugin=plugin-permissions, version={})", BuildInfo.VERSION)
    }

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        registerProviders()

        val config = VelocityPermissionsConfig.fromEnvironment(System.getenv())
        val snapshotClient = GrpcPermissionSnapshotClient.create(config.grpcTarget)
        val manifestClient = GrpcPermissionCatalogClient.create(config.grpcTarget)
        client = snapshotClient
        catalogClient = manifestClient

        val snapshots = InMemoryPermissionSnapshots()
        val listener =
            PermissionLoginListener(
                logger = logger,
                snapshots = snapshots,
                cache = SnapshotDiskCache(logger, dataDirectory.resolve("snapshots")),
                client = snapshotClient,
                context = config.context,
            )
        proxy.eventManager.register(this, listener)

        val refreshSweep =
            PermissionSnapshotRefreshSweep(
                snapshots = snapshots,
                onlinePlayerIds = { proxy.allPlayers.map { it.uniqueId }.toSet() },
                fetchSnapshot = { playerId ->
                    when (val fetch = snapshotClient.fetchSnapshot(playerId, config.context)) {
                        is PermissionSnapshotFetchResult.Success -> {
                            listener.activateSnapshot(fetch.snapshot)
                            fetch.snapshot
                        }
                        is PermissionSnapshotFetchResult.Unavailable -> null
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
                            logger.warn("Permission snapshot refresh sweep failed", exception)
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
                            refreshSnapshot = listener::loadSnapshot,
                            status =
                                PermissionCommandStatus(
                                    version = BuildInfo.VERSION,
                                    grpcTarget = config.grpcTarget,
                                    context = config.context,
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
                "Registered permissions commands successfully (root=permissions, alias=perm)"
            )
        }

        proxy.scheduler
            .buildTask(this, Runnable { registerActivePermissionManifests(manifestClient, config) })
            .schedule()

        logger.info(
            "Configured permissions snapshot client (target={}, serverType={}, serverId={})",
            config.grpcTarget,
            config.context.serverType,
            config.context.serverId,
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
        client?.close()
        client = null
        catalogClient?.close()
        catalogClient = null
        permissions = null
    }

    private fun registerProviders() {
        NameResolverRegistry.getDefaultRegistry().register(DnsNameResolverProvider())
        LoadBalancerRegistry.getDefaultRegistry().register(PickFirstLoadBalancerProvider())
    }

    private fun loadCommandPermissions(): PermissionCommandPermissions? =
        try {
            PermissionCommandPermissions.fromManifest(
                PermissionManifest.loadRequiredResource(javaClass.classLoader)
            )
        } catch (exception: IllegalArgumentException) {
            logger.warn(
                "Skipped permissions command registration (originId=plugin-permissions, reason={})",
                exception.message ?: exception::class.java.simpleName,
            )
            null
        }

    private fun registerActivePermissionManifests(
        manifestClient: PermissionCatalogClient,
        config: VelocityPermissionsConfig,
    ) {
        val collection =
            PermissionManifestCollector()
                .collect(discoverPermissionManifestOrigins(proxy.pluginManager.plugins))
        collection.failures.forEach { failure ->
            logger.warn(
                "Skipped malformed permission manifest (originId={}, originVersion={}, reason={})",
                failure.origin.id,
                failure.origin.version,
                failure.reason,
            )
        }
        val registration =
            PermissionManifestRegistrar(manifestClient, config.context)
                .register(collection.manifests)
        registration.registered.forEach { collected ->
            logger.info(
                "Registered permission catalog manifest successfully (originId={}, source={}, version={}, permissionCount={})",
                collected.origin.id,
                collected.manifest.source,
                collected.origin.version,
                collected.manifest.permissions.size,
            )
        }
        registration.failures.forEach { failure ->
            logger.warn(
                "Failed to register permission catalog manifest (originId={}, source={}, attempts={}, reason={})",
                failure.collected.origin.id,
                failure.collected.manifest.source,
                failure.attempts,
                failure.reason,
            )
        }
    }
}

data class VelocityPermissionsConfig(
    val grpcTarget: String,
    val context: PermissionSnapshotContext,
    val refreshIntervalSeconds: Long,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): VelocityPermissionsConfig {
            val target =
                environment["PERMISSIONS_GRPC_TARGET"]?.takeIf { it.isNotBlank() }
                    ?: error("Missing required environment variable PERMISSIONS_GRPC_TARGET")
            return VelocityPermissionsConfig(
                grpcTarget = target,
                context =
                    PermissionSnapshotContext(
                        serverType =
                            environment["GROUNDS_PERMISSION_SERVER_TYPE"]?.takeIf {
                                it.isNotBlank()
                            },
                        serverId =
                            environment["GROUNDS_PERMISSION_SERVER_ID"]?.takeIf { it.isNotBlank() },
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
    when {
        serverId != null && serverType != null -> PermissionCheckScope.server(serverId, serverType)
        serverId != null -> PermissionCheckScope.serverOnly(serverId)
        serverType != null -> PermissionCheckScope.serverType(serverType)
        else -> PermissionCheckScope.global()
    }
