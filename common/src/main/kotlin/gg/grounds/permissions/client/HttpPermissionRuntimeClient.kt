package gg.grounds.permissions.client

import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.catalog.PermissionManifest
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.UUID
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class HttpPermissionRuntimeClient(
    private val config: PermissionServiceConfig,
    private val tokenProvider: WorkloadTokenProvider = FileWorkloadTokenProvider(config.tokenFile),
    private val cache: SnapshotCache = SnapshotCache(),
    private val status: PermissionRuntimeStatus = PermissionRuntimeStatus(),
    private val clock: Clock = Clock.systemUTC(),
    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(DEFAULT_REQUEST_TIMEOUT).build(),
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val requestIdSupplier: () -> String = { UUID.randomUUID().toString() },
    private val mapper: ObjectMapper = defaultMapper(),
) : PermissionRuntimeClient {
    override fun fetchSnapshot(
        playerId: UUID,
        context: PermissionSnapshotContext,
    ): PermissionSnapshot {
        val requestId = requestIdSupplier()
        val request =
            HttpRequest.newBuilder(snapshotUri(playerId, context))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer ${tokenProvider.readToken()}")
                .header("Accept", "application/json")
                .header("X-Request-ID", requestId)
                .GET()
                .build()
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (exception: IOException) {
                return fallbackOrThrow(
                    playerId = playerId,
                    reason = SnapshotFailureReason.UNAVAILABLE,
                    requestId = requestId,
                    cause = exception,
                )
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                return fallbackOrThrow(
                    playerId = playerId,
                    reason = SnapshotFailureReason.UNAVAILABLE,
                    requestId = requestId,
                    cause = exception,
                )
            }

        val responseRequestId = response.headers().firstValue("X-Request-ID").orElse(requestId)
        return when (response.statusCode()) {
            200 -> decodeOrFallback(playerId, response.body(), responseRequestId)
            401 ->
                failClosed(
                    SnapshotFailureReason.UNAUTHENTICATED,
                    response.statusCode(),
                    responseRequestId,
                )
            403 ->
                failClosed(
                    SnapshotFailureReason.FORBIDDEN,
                    response.statusCode(),
                    responseRequestId,
                )
            404 ->
                failClosed(
                    SnapshotFailureReason.NOT_FOUND,
                    response.statusCode(),
                    responseRequestId,
                )
            429 ->
                fallbackOrThrow(
                    playerId,
                    SnapshotFailureReason.UNAVAILABLE,
                    response.statusCode(),
                    responseRequestId,
                )
            in 500..599 ->
                fallbackOrThrow(
                    playerId,
                    SnapshotFailureReason.UNAVAILABLE,
                    response.statusCode(),
                    responseRequestId,
                )
            else ->
                failClosed(
                    SnapshotFailureReason.INVALID_RESPONSE,
                    response.statusCode(),
                    responseRequestId,
                )
        }
    }

    override fun registerManifest(
        manifest: PermissionManifest,
        sourceVersion: String,
        context: PermissionSnapshotContext,
    ): PermissionManifestRegistrationResult {
        require(sourceVersion.isNotBlank()) {
            "Permission manifest sourceVersion must not be blank"
        }
        val requestId = requestIdSupplier()
        val token =
            try {
                tokenProvider.readToken()
            } catch (exception: IllegalStateException) {
                return PermissionManifestRegistrationResult.RetryableFailure(
                    reason = "token_unavailable",
                    requestId = requestId,
                )
            }
        val body = mapper.writeValueAsString(manifest.toRuntimeRequest(sourceVersion, context))
        val request =
            try {
                HttpRequest.newBuilder(manifestUri(manifest.source))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Request-ID", requestId)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
            } catch (exception: IllegalArgumentException) {
                return PermissionManifestRegistrationResult.TerminalFailure(
                    reason = "invalid_request",
                    requestId = requestId,
                )
            }
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (exception: IOException) {
                return PermissionManifestRegistrationResult.RetryableFailure(
                    reason = "transport_error",
                    requestId = requestId,
                )
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                return PermissionManifestRegistrationResult.RetryableFailure(
                    reason = "interrupted",
                    requestId = requestId,
                )
            }
        val responseRequestId = response.headers().firstValue("X-Request-ID").orElse(requestId)
        return when (response.statusCode()) {
            204 -> PermissionManifestRegistrationResult.Accepted
            429 ->
                PermissionManifestRegistrationResult.RetryableFailure(
                    reason = "http_429",
                    statusCode = response.statusCode(),
                    requestId = responseRequestId,
                )
            in 500..599 ->
                PermissionManifestRegistrationResult.RetryableFailure(
                    reason = "http_${response.statusCode()}",
                    statusCode = response.statusCode(),
                    requestId = responseRequestId,
                )
            else ->
                PermissionManifestRegistrationResult.TerminalFailure(
                    reason = "http_${response.statusCode()}",
                    statusCode = response.statusCode(),
                    requestId = responseRequestId,
                )
        }
    }

    private fun decodeOrFallback(
        playerId: UUID,
        responseBody: String,
        requestId: String,
    ): PermissionSnapshot =
        try {
            mapper
                .readValue(responseBody, RuntimePermissionSnapshotDto::class.java)
                .toDomain(playerId)
                .also {
                    cache.put(it)
                    status.recordSnapshotSuccess()
                }
        } catch (exception: Exception) {
            fallbackOrThrow(
                playerId = playerId,
                reason = SnapshotFailureReason.INVALID_RESPONSE,
                statusCode = 200,
                requestId = requestId,
                cause = exception,
            )
        }

    private fun fallbackOrThrow(
        playerId: UUID,
        reason: SnapshotFailureReason,
        statusCode: Int? = null,
        requestId: String? = null,
        cause: Throwable? = null,
    ): PermissionSnapshot {
        status.recordSnapshotFailure()
        cache.valid(playerId, clock.instant())?.let {
            status.recordValidCacheFallback()
            return it
        }
        status.recordFailClosedDecision()
        throw SnapshotUnavailableException(reason, statusCode, requestId, cause)
    }

    private fun failClosed(
        reason: SnapshotFailureReason,
        statusCode: Int,
        requestId: String,
    ): Nothing {
        status.recordSnapshotFailure()
        status.recordFailClosedDecision()
        throw SnapshotUnavailableException(reason, statusCode, requestId)
    }

    private fun snapshotUri(playerId: UUID, context: PermissionSnapshotContext): URI {
        val query =
            listOfNotNull(
                    context.serverType?.let { "serverType=${encode(it)}" },
                    context.serverId?.let { "serverId=${encode(it)}" },
                )
                .joinToString("&")
        val endpoint =
            "${config.serviceUri.toString().trimEnd('/')}/v1/permissions/runtime/players/$playerId/snapshot"
        return URI.create(if (query.isEmpty()) endpoint else "$endpoint?$query")
    }

    private fun manifestUri(source: String): URI =
        URI.create(
            "${config.serviceUri.toString().trimEnd('/')}/v1/permissions/runtime/catalog/manifests/${encode(source)}"
        )

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        private val DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(2)

        private fun defaultMapper(): ObjectMapper =
            JsonMapper.builder()
                .addModule(KotlinModule.Builder().build())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
    }
}
