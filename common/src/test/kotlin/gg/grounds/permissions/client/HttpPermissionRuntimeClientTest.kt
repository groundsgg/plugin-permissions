package gg.grounds.permissions.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpPermissionRuntimeClientTest {
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private var responder: (HttpExchange) -> Unit = { respond(it, 500) }
    private val requestCount = AtomicInteger()
    private val requests = mutableListOf<RecordedRequest>()
    private val status = PermissionRuntimeStatus()
    private val playerId = UUID.fromString("c5115183-46e6-4458-b15a-c89643c1a91e")

    @BeforeEach
    fun startServer() {
        executor = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()
            synchronized(requests) { requests += exchange.recorded() }
            responder(exchange)
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `maps the exact authenticated snapshot request and complete response`() {
        responder = { exchange -> respond(exchange, 200, SNAPSHOT_JSON) }
        val client = client()

        val snapshot =
            client.fetchSnapshot(
                playerId,
                PermissionSnapshotContext(
                    serverType = "velocity proxy",
                    serverId = "proxy/1",
                    environment = "stage",
                ),
            )

        assertEquals(expectedSnapshot(), snapshot)
        assertEquals(1, requestCount.get())
        assertEquals(
            RecordedRequest(
                method = "GET",
                rawPath = "/base/v1/permissions/runtime/players/$playerId/snapshot",
                rawQuery = "serverType=velocity%20proxy&serverId=proxy%2F1",
                authorization = "Bearer projected-token",
                accept = "application/json",
                requestId = "client-request-123",
            ),
            requests.single(),
        )
        assertEquals(
            PermissionRuntimeStatusSnapshot(
                snapshotSuccesses = 1,
                snapshotFailures = 0,
                validCacheFallbacks = 0,
                failClosedDecisions = 0,
            ),
            status.snapshot(),
        )
    }

    @Test
    fun `uses one unexpired cached snapshot after a transient response`() {
        val cached = expectedSnapshot().copy(policyVersion = 3)
        val cache = SnapshotCache().also { it.put(cached) }
        responder = { exchange ->
            respond(exchange, 503, "upstream details", mapOf("X-Request-ID" to "server-request"))
        }

        val snapshot = client(cache = cache).fetchSnapshot(playerId, PermissionSnapshotContext())

        assertEquals(cached, snapshot)
        assertEquals(1, requestCount.get())
        assertEquals(1, status.snapshot().snapshotFailures)
        assertEquals(1, status.snapshot().validCacheFallbacks)
        assertEquals(0, status.snapshot().failClosedDecisions)
    }

    @Test
    fun `fails closed after one transient response when no valid cache exists`() {
        responder = { exchange ->
            respond(
                exchange,
                500,
                "secret-response-body",
                mapOf("X-Request-ID" to "server-request"),
            )
        }

        val error =
            assertThrows(SnapshotUnavailableException::class.java) {
                client().fetchSnapshot(playerId, PermissionSnapshotContext())
            }

        assertEquals(SnapshotFailureReason.UNAVAILABLE, error.reason)
        assertEquals(500, error.statusCode)
        assertEquals("server-request", error.requestId)
        assertFalse(error.message.orEmpty().contains("secret-response-body"))
        assertEquals(1, requestCount.get())
        assertEquals(1, status.snapshot().failClosedDecisions)
    }

    @Test
    fun `never uses a cached snapshot for authentication or authorization failures`() {
        val cache = SnapshotCache().also { it.put(expectedSnapshot()) }

        listOf(401 to SnapshotFailureReason.UNAUTHENTICATED, 403 to SnapshotFailureReason.FORBIDDEN)
            .forEach { (responseStatus, expectedReason) ->
                responder = { exchange -> respond(exchange, responseStatus) }

                val error =
                    assertThrows(SnapshotUnavailableException::class.java) {
                        client(cache = cache).fetchSnapshot(playerId, PermissionSnapshotContext())
                    }

                assertEquals(expectedReason, error.reason)
                assertEquals(responseStatus, error.statusCode)
            }

        assertEquals(2, requestCount.get())
        assertEquals(0, status.snapshot().validCacheFallbacks)
        assertEquals(2, status.snapshot().failClosedDecisions)
    }

    @Test
    fun `fails closed for missing player state even when a cache entry exists`() {
        val cache = SnapshotCache().also { it.put(expectedSnapshot()) }
        responder = { exchange -> respond(exchange, 404) }

        val error =
            assertThrows(SnapshotUnavailableException::class.java) {
                client(cache = cache).fetchSnapshot(playerId, PermissionSnapshotContext())
            }

        assertEquals(SnapshotFailureReason.NOT_FOUND, error.reason)
        assertEquals(0, status.snapshot().validCacheFallbacks)
        assertEquals(1, status.snapshot().failClosedDecisions)
    }

    @Test
    fun `uses only a valid cache entry for malformed JSON`() {
        val cached = expectedSnapshot().copy(policyVersion = 2)
        val cache = SnapshotCache().also { it.put(cached) }
        responder = { exchange -> respond(exchange, 200, "{not-json") }

        assertEquals(
            cached,
            client(cache = cache).fetchSnapshot(playerId, PermissionSnapshotContext()),
        )
        assertEquals(1, requestCount.get())
        assertEquals(1, status.snapshot().validCacheFallbacks)
    }

    @Test
    fun `rejects a response for a different player and falls back to a valid cache entry`() {
        val cached = expectedSnapshot().copy(policyVersion = 2)
        val cache = SnapshotCache().also { it.put(cached) }
        responder = { exchange ->
            respond(
                exchange,
                200,
                SNAPSHOT_JSON.replace(playerId.toString(), "f372070f-af37-4c29-9a04-952f1a63e61a"),
            )
        }

        assertEquals(
            cached,
            client(cache = cache).fetchSnapshot(playerId, PermissionSnapshotContext()),
        )
        assertEquals(1, requestCount.get())
        assertEquals(1, status.snapshot().validCacheFallbacks)
    }

    @Test
    fun `does not retry a timed out snapshot request`() {
        val releaseResponse = CountDownLatch(1)
        responder = { exchange ->
            releaseResponse.await()
            respond(exchange, 200, SNAPSHOT_JSON)
        }

        try {
            val error =
                assertThrows(SnapshotUnavailableException::class.java) {
                    client(requestTimeout = Duration.ofMillis(50))
                        .fetchSnapshot(playerId, PermissionSnapshotContext())
                }

            assertEquals(SnapshotFailureReason.UNAVAILABLE, error.reason)
            assertEquals(1, requestCount.get())
        } finally {
            releaseResponse.countDown()
        }
    }

    private fun client(
        cache: SnapshotCache = SnapshotCache(),
        requestTimeout: Duration = Duration.ofSeconds(2),
    ): HttpPermissionRuntimeClient =
        HttpPermissionRuntimeClient(
            config =
                PermissionServiceConfig(
                    serviceUri = URI("http://127.0.0.1:${server.address.port}/base"),
                    tokenFile = java.nio.file.Path.of("/unused-in-test"),
                ),
            tokenProvider = WorkloadTokenProvider { "projected-token" },
            cache = cache,
            status = status,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            requestTimeout = requestTimeout,
            requestIdSupplier = { "client-request-123" },
        )

    private fun expectedSnapshot(): PermissionSnapshot =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = 4,
            issuedAt = Instant.parse("2026-07-26T17:59:00Z"),
            refreshAfter = Instant.parse("2026-07-26T18:01:00Z"),
            expiresAt = Instant.parse("2026-07-26T18:05:00Z"),
            allowPatterns =
                listOf(
                    PermissionGrant(
                        effect = PermissionEffect.ALLOW,
                        pattern = "grounds.chat.staff",
                        scope = PermissionScope.global(),
                        source = PermissionGrantSource.ROLE,
                    )
                ),
            denyPatterns =
                listOf(
                    PermissionGrant(
                        effect = PermissionEffect.DENY,
                        pattern = "grounds.command.stop",
                        scope = PermissionScope.serverType("velocity proxy"),
                        source = PermissionGrantSource.PLAYER,
                        expiresAt = Instant.parse("2026-07-26T18:04:00Z"),
                    )
                ),
            roleKeys = setOf("staff"),
            roleMetadata =
                listOf(
                    RoleMetadata(
                        key = "staff",
                        name = "Staff",
                        prefix = "[Staff]",
                        color = "#ff0000",
                        sortOrder = 10,
                    )
                ),
        )

    private fun HttpExchange.recorded(): RecordedRequest =
        RecordedRequest(
            method = requestMethod,
            rawPath = requestURI.rawPath,
            rawQuery = requestURI.rawQuery,
            authorization = requestHeaders.getFirst("Authorization"),
            accept = requestHeaders.getFirst("Accept"),
            requestId = requestHeaders.getFirst("X-Request-ID"),
        )

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach(exchange.responseHeaders::add)
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class RecordedRequest(
        val method: String,
        val rawPath: String,
        val rawQuery: String?,
        val authorization: String?,
        val accept: String?,
        val requestId: String?,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-26T18:00:00Z")
        const val SNAPSHOT_JSON =
            """{"playerId":"c5115183-46e6-4458-b15a-c89643c1a91e","policyVersion":4,"issuedAt":"2026-07-26T17:59:00Z","refreshAfter":"2026-07-26T18:01:00Z","expiresAt":"2026-07-26T18:05:00Z","allowPatterns":[{"effect":"ALLOW","pattern":"grounds.chat.staff","scope":{"kind":"GLOBAL","value":null},"source":"ROLE","expiresAt":null}],"denyPatterns":[{"effect":"DENY","pattern":"grounds.command.stop","scope":{"kind":"SERVER_TYPE","value":"velocity proxy"},"source":"PLAYER","expiresAt":"2026-07-26T18:04:00Z"}],"roleKeys":["staff"],"roleMetadata":[{"key":"staff","name":"Staff","prefix":"[Staff]","color":"#ff0000","sortOrder":10}]}"""
    }
}
