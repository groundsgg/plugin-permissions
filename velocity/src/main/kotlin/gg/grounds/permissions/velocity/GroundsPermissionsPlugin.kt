package gg.grounds.permissions.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.BuildInfo
import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionCheckScope
import io.grpc.LoadBalancerRegistry
import io.grpc.NameResolverRegistry
import io.grpc.internal.DnsNameResolverProvider
import io.grpc.internal.PickFirstLoadBalancerProvider
import java.nio.file.Path
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

    init {
        logger.info("Initialized plugin (plugin=plugin-permissions, version={})", BuildInfo.VERSION)
    }

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        registerProviders()

        val config = VelocityPermissionsConfig.fromEnvironment(System.getenv())
        val snapshotClient = GrpcPermissionSnapshotClient.create(config.grpcTarget)
        client = snapshotClient

        proxy.eventManager.register(
            this,
            PermissionLoginListener(
                logger = logger,
                snapshots = InMemoryPermissionSnapshots(),
                cache = SnapshotDiskCache(logger, dataDirectory.resolve("snapshots")),
                client = snapshotClient,
                context = config.context,
            ),
        )

        logger.info(
            "Configured permissions snapshot client (target={}, serverType={}, serverId={})",
            config.grpcTarget,
            config.context.serverType,
            config.context.serverId,
        )
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        client?.close()
        client = null
    }

    private fun registerProviders() {
        NameResolverRegistry.getDefaultRegistry().register(DnsNameResolverProvider())
        LoadBalancerRegistry.getDefaultRegistry().register(PickFirstLoadBalancerProvider())
    }
}

data class VelocityPermissionsConfig(
    val grpcTarget: String,
    val context: PermissionSnapshotContext,
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
