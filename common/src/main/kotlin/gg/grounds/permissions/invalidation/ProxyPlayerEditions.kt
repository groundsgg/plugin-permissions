package gg.grounds.permissions.invalidation

import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/** Reads the proxy's existing edition roster, including linked Floodgate accounts. */
class ProxyPlayerEditions(environment: Map<String, String> = System.getenv()) : AutoCloseable {
    private data class Snapshot(val players: Map<UUID, Boolean>, val receivedAt: Long)

    private val snapshots = mutableMapOf<String, Snapshot>()
    private val mapper = JsonMapper.builder().build()
    private val logger = LoggerFactory.getLogger(javaClass)
    private var connection: Connection? = null
    @Volatile private var closed = false
    private val lifecycleLock = Any()
    private val executor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "grounds-player-editions").apply { isDaemon = true }
        }

    init {
        val deployment =
            environment["GROUNDS_PERMISSION_ENVIRONMENT"] ?: environment["GROUNDS_ENVIRONMENT"]
        val config = PermissionSnapshotInvalidationConfig.fromEnvironment(environment)
        if (config != null && deployment != null && deployment.matches(OWNER)) {
            executor.scheduleWithFixedDelay(
                { connect(config, "proxy.platform.$deployment") },
                0,
                10,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun connect(config: PermissionSnapshotInvalidationConfig, subject: String) =
        synchronized(lifecycleLock) {
            if (closed || connection != null) return
            var opened: Connection? = null
            try {
                val builder =
                    Options.Builder()
                        .server(config.natsUrl)
                        .maxReconnects(-1)
                        .connectionTimeout(Duration.ofSeconds(2))
                when (val credentials = config.credentials) {
                    PermissionSnapshotInvalidationCredentials.Anonymous -> Unit
                    PermissionSnapshotInvalidationCredentials.InvalidTokenFile -> return
                    is PermissionSnapshotInvalidationCredentials.TokenFile ->
                        builder.tokenSupplier {
                            Files.readString(credentials.path).trim().toCharArray()
                        }
                }
                opened = Nats.connect(builder.build())
                opened.createDispatcher { message -> receive(message.data) }.subscribe(subject)
                connection = opened
            } catch (_: Exception) {
                opened?.close()
                logger.debug("Edition roster subscription will retry (subject={})", subject)
            }
        }

    @Synchronized
    internal fun receive(payload: ByteArray) {
        if (closed || payload.size > 512 * 1024) return
        try {
            val root = mapper.readTree(payload)
            if (root.path("schemaVersion").asInt() != 1) return
            val owner = root.path("proxy").asString()
            if (!owner.matches(OWNER)) return
            val values = root.path("players")
            if (!values.isObject || values.size() > 10_000) return
            val players =
                values.properties().associate { (key, value) ->
                    val id = UUID.fromString(key)
                    if (id.toString() != key || !value.isBoolean) return
                    id to value.asBoolean()
                }
            val now = System.nanoTime()
            snapshots.entries.removeIf { now - it.value.receivedAt >= EXPIRY_NANOS }
            if (owner !in snapshots && snapshots.size >= 256) return
            snapshots[owner] = Snapshot(players, now)
        } catch (_: Exception) {
            // Invalid display metadata never affects login or permissions.
        }
    }

    @Synchronized
    fun isBedrock(id: UUID): Boolean {
        val now = System.nanoTime()
        return snapshots.values
            .filter { now - it.receivedAt < EXPIRY_NANOS && id in it.players }
            .maxByOrNull { it.receivedAt }
            ?.players
            ?.get(id) ?: (id.mostSignificantBits == 0L && id.leastSignificantBits != 0L)
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
        val current = synchronized(lifecycleLock) { connection.also { connection = null } }
        current?.close()
        synchronized(this) { snapshots.clear() }
    }

    private companion object {
        val OWNER = Regex("[A-Za-z0-9_-]{1,128}")
        val EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(30)
    }
}
