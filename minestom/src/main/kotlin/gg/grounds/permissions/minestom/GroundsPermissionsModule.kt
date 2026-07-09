package gg.grounds.permissions.minestom

import gg.grounds.modules.register
import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionCheckScope
import gg.grounds.permissions.Permissions
import gg.grounds.permissions.SnapshotPermissions
import gg.grounds.permissions.catalog.CollectedPermissionManifest
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GroundsPermissionsModule(private val clock: Clock = Clock.systemUTC()) : GroundsModule {
    private val logger: Logger = LoggerFactory.getLogger(GroundsPermissionsModule::class.java)
    private val snapshots = InMemoryPermissionSnapshots()
    private var client: PermissionSnapshotClient? = null
    private var catalogClient: PermissionCatalogClient? = null
    private var catalogExecutor: ExecutorService? = null
    private var eventNode: EventNode<Event>? = null

    override val id: String = MODULE_ID

    override fun install(ctx: GroundsServerContext) {
        stop()

        val config =
            MinestomPermissionsConfig.fromEnvironment(
                environment = System.getenv(),
                fallbackServerType = ctx.serverType.name.lowercase(),
            )
        val snapshotClient = GrpcPermissionSnapshotClient.create(config.grpcTarget)
        val manifestClient = GrpcPermissionCatalogClient.create(config.grpcTarget)
        val manifestExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "grounds-permissions-manifest-catalog").apply { isDaemon = true }
            }
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
                client = snapshotClient,
                context = config.context,
                clock = clock,
            )

        ctx.services.register<Permissions>(permissions)

        val node = ctx.eventNode("grounds-permissions")
        PermissionPlayerListener(loader).register(node)
        MinecraftServer.getGlobalEventHandler().addChild(node)
        eventNode = node
        client = snapshotClient
        catalogClient = manifestClient
        catalogExecutor = manifestExecutor

        ctx.onShutdown { stop() }

        registerActivePermissionManifests(
            activeProviders = ctx.activeModuleProviders,
            manifestClient = manifestClient,
            manifestExecutor = manifestExecutor,
            context = config.context,
        )

        logger.info(
            "Installed permissions module (serverType={}, serverId={}, target={})",
            config.context.serverType,
            config.context.serverId,
            config.grpcTarget,
        )
    }

    override fun stop() {
        eventNode?.let(MinecraftServer.getGlobalEventHandler()::removeChild)
        eventNode = null
        catalogExecutor?.shutdownNow()
        catalogExecutor = null
        client?.close()
        client = null
        catalogClient?.close()
        catalogClient = null
    }

    private fun registerActivePermissionManifests(
        activeProviders: Iterable<gg.grounds.runtime.ActiveGroundsModuleProvider>,
        manifestClient: PermissionCatalogClient,
        manifestExecutor: ExecutorService,
        context: PermissionSnapshotContext,
    ) {
        try {
            manifestExecutor.execute {
                val collection = collectActivePermissionManifests(activeProviders)
                collection.failures.forEach { failure ->
                    logger.warn(
                        "Skipped malformed permission manifest (originId={}, originVersion={}, reason={})",
                        failure.origin.id,
                        failure.origin.version,
                        failure.reason,
                    )
                }
                val registration =
                    MinestomPermissionManifestRegistrar(manifestClient, context)
                        .register(collection.manifests)
                registration.registered.forEach(::logManifestRegistration)
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
        } catch (exception: RejectedExecutionException) {
            logger.warn(
                "Skipped permission catalog registration (reason={})",
                exception::class.java.simpleName,
            )
        }
    }

    private fun logManifestRegistration(collected: CollectedPermissionManifest) {
        logger.info(
            "Registered permission catalog manifest successfully (originId={}, source={}, version={}, permissionCount={})",
            collected.origin.id,
            collected.manifest.source,
            collected.origin.version,
            collected.manifest.permissions.size,
        )
    }

    companion object {
        const val MODULE_ID: String = "grounds.permissions"
    }
}

data class MinestomPermissionsConfig(
    val grpcTarget: String,
    val context: PermissionSnapshotContext,
) {
    companion object {
        fun fromEnvironment(
            environment: Map<String, String>,
            fallbackServerType: String,
        ): MinestomPermissionsConfig {
            val target =
                environment["PERMISSIONS_GRPC_TARGET"]?.takeIf { it.isNotBlank() }
                    ?: error("Missing required environment variable PERMISSIONS_GRPC_TARGET")
            return MinestomPermissionsConfig(
                grpcTarget = target,
                context =
                    PermissionSnapshotContext(
                        serverType =
                            environment["GROUNDS_PERMISSION_SERVER_TYPE"]?.takeIf {
                                it.isNotBlank()
                            } ?: fallbackServerType,
                        serverId =
                            environment["GROUNDS_PERMISSION_SERVER_ID"]?.takeIf { it.isNotBlank() },
                    ),
            )
        }
    }
}

fun PermissionSnapshotContext.toCheckScope(): PermissionCheckScope =
    when {
        serverId != null && serverType != null -> PermissionCheckScope.server(serverId, serverType)
        serverId != null -> PermissionCheckScope.serverOnly(serverId)
        serverType != null -> PermissionCheckScope.serverType(serverType)
        else -> PermissionCheckScope.global()
    }
