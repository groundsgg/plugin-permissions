package gg.grounds.permissions.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.catalog.PermissionManifest
import gg.grounds.permissions.catalog.PermissionManifestEntry
import gg.grounds.permissions.catalog.PermissionManifestScope
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

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
        val cache = SnapshotCache().also { it.put(cached, NOW) }
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
        val cache = SnapshotCache().also { it.put(expectedSnapshot(), NOW) }

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
        val cache = SnapshotCache().also { it.put(expectedSnapshot(), NOW) }
        responder = { exchange -> respond(exchange, 404) }

        val error =
            assertThrows(SnapshotUnavailableException::class.java) {
                client(cache = cache).fetchSnapshot(playerId, PermissionSnapshotContext())
            }

        assertEquals(SnapshotFailureReason.NOT_FOUND, error.reason)
        assertEquals(0, status.snapshot().validCacheFallbacks)
        assertEquals(1, status.snapshot().failClosedDecisions)

        responder = { exchange -> respond(exchange, 503) }
        val unavailableError =
            assertThrows(SnapshotUnavailableException::class.java) {
                client(cache = cache).fetchSnapshot(playerId, PermissionSnapshotContext())
            }

        assertEquals(SnapshotFailureReason.UNAVAILABLE, unavailableError.reason)
        assertEquals(0, status.snapshot().validCacheFallbacks)
        assertEquals(2, status.snapshot().failClosedDecisions)
    }

    @Test
    fun `successful snapshot fetch evicts expired historical cache entries`() {
        val historicalPlayerId = UUID.fromString("76069bba-4618-4f55-acd4-e43d6c4bde23")
        val cache =
            SnapshotCache().also {
                it.put(
                    expectedSnapshot()
                        .copy(playerId = historicalPlayerId, expiresAt = NOW.minusSeconds(1)),
                    NOW.minusSeconds(2),
                )
            }
        responder = { exchange -> respond(exchange, 200, SNAPSHOT_JSON) }

        client(cache = cache).fetchSnapshot(playerId, PermissionSnapshotContext())

        assertEquals(1, cache.storedEntryCount())
        assertEquals(playerId, cache.valid(playerId, NOW)?.playerId)
    }

    @Test
    fun `uses only a valid cache entry for malformed JSON`() {
        val cached = expectedSnapshot().copy(policyVersion = 2)
        val cache = SnapshotCache().also { it.put(cached, NOW) }
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
        val cache = SnapshotCache().also { it.put(cached, NOW) }
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

    @Test
    fun `maps a manifest registration with its source only in the encoded path`() {
        responder = { exchange ->
            respond(exchange, 204, headers = mapOf("X-Request-ID" to "server-request"))
        }

        val result =
            client()
                .registerManifest(
                    manifest = manifest(source = "plugin/chat staff"),
                    sourceVersion = "1.4.0",
                    context =
                        PermissionSnapshotContext(
                            serverType = "velocity",
                            serverId = "proxy-1",
                            environment = "stage",
                        ),
                )

        assertEquals(PermissionManifestRegistrationResult.Accepted, result)
        val request = requests.single()
        assertEquals("PUT", request.method)
        assertEquals(
            "/base/v1/permissions/runtime/catalog/manifests/plugin%2Fchat%20staff",
            request.rawPath,
        )
        assertEquals(null, request.rawQuery)
        assertEquals("Bearer projected-token", request.authorization)
        assertEquals("application/json", request.contentType)
        assertEquals("client-request-123", request.requestId)

        val body = JsonMapper.builder().build().readTree(request.body)
        assertFalse(body.has("source"))
        assertEquals("1.4.0", body.path("sourceVersion").asString())
        assertEquals("velocity", body.path("serverType").asString())
        assertEquals("proxy-1", body.path("serverId").asString())
        assertEquals("grounds.chat.staff", body.path("permissions").path(0).path("key").asString())
        assertEquals(
            listOf("GLOBAL", "SERVER_TYPE"),
            body.path("permissions").path(0).path("supportedScopes").values().map { it.asString() },
        )
    }

    @Test
    fun `classifies retryable and terminal manifest responses without exposing bodies`() {
        listOf(429, 500).forEach { responseStatus ->
            responder = { exchange ->
                respond(
                    exchange,
                    responseStatus,
                    "secret-response-body",
                    mapOf("X-Request-ID" to "server-$responseStatus"),
                )
            }

            val result =
                client()
                    .registerManifest(manifest("plugin-chat"), "1.4.0", PermissionSnapshotContext())

            val failure =
                assertInstanceOf(
                    PermissionManifestRegistrationResult.RetryableFailure::class.java,
                    result,
                )
            assertEquals(responseStatus, failure.statusCode)
            assertEquals("server-$responseStatus", failure.requestId)
            assertFalse(failure.reason.contains("secret-response-body"))
        }

        listOf(400, 401, 403, 409).forEach { responseStatus ->
            responder = { exchange -> respond(exchange, responseStatus, "secret-response-body") }

            val result =
                client()
                    .registerManifest(manifest("plugin-chat"), "1.4.0", PermissionSnapshotContext())

            val failure =
                assertInstanceOf(
                    PermissionManifestRegistrationResult.TerminalFailure::class.java,
                    result,
                )
            assertEquals(responseStatus, failure.statusCode)
            assertFalse(failure.reason.contains("secret-response-body"))
        }
    }

    @Test
    fun `classifies a manifest connection failure as retryable`() {
        server.stop(0)

        val result =
            client().registerManifest(manifest("plugin-chat"), "1.4.0", PermissionSnapshotContext())

        val failure =
            assertInstanceOf(
                PermissionManifestRegistrationResult.RetryableFailure::class.java,
                result,
            )
        assertEquals("transport_error", failure.reason)
        assertEquals(null, failure.statusCode)
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
            clock = TEST_CLOCK,
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

    private fun manifest(source: String): PermissionManifest =
        PermissionManifest(
            source = source,
            permissions =
                listOf(
                    PermissionManifestEntry(
                        key = "grounds.chat.staff",
                        label = "Staff chat",
                        description = "Allows access to staff chat.",
                        supportedScopes =
                            listOf(
                                PermissionManifestScope.GLOBAL,
                                PermissionManifestScope.SERVER_TYPE,
                            ),
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
            contentType = requestHeaders.getFirst("Content-Type"),
            requestId = requestHeaders.getFirst("X-Request-ID"),
            body = requestBody.bufferedReader().use { it.readText() },
        )

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach(exchange.responseHeaders::add)
        val bytes = body.toByteArray()
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun SnapshotCache.storedEntryCount(): Int {
        val snapshotsField = SnapshotCache::class.java.getDeclaredField("snapshots")
        snapshotsField.isAccessible = true
        return (snapshotsField.get(this) as ConcurrentHashMap<*, *>).size
    }

    private data class RecordedRequest(
        val method: String,
        val rawPath: String,
        val rawQuery: String?,
        val authorization: String?,
        val accept: String?,
        val contentType: String? = null,
        val requestId: String?,
        val body: String = "",
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-26T18:00:00Z")
        val TEST_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        const val SNAPSHOT_JSON =
            """{"playerId":"c5115183-46e6-4458-b15a-c89643c1a91e","policyVersion":4,"issuedAt":"2026-07-26T17:59:00Z","refreshAfter":"2026-07-26T18:01:00Z","expiresAt":"2026-07-26T18:05:00Z","allowPatterns":[{"effect":"ALLOW","pattern":"grounds.chat.staff","scope":{"kind":"GLOBAL","value":null},"source":"ROLE","expiresAt":null}],"denyPatterns":[{"effect":"DENY","pattern":"grounds.command.stop","scope":{"kind":"SERVER_TYPE","value":"velocity proxy"},"source":"PLAYER","expiresAt":"2026-07-26T18:04:00Z"}],"roleKeys":["staff"],"roleMetadata":[{"key":"staff","name":"Staff","prefix":"[Staff]","color":"#ff0000","sortOrder":10}]}"""
    }
}
