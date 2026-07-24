package gg.grounds.permissions.velocity

import com.google.protobuf.Timestamp
import gg.grounds.grpc.permissions.GetPlayerSnapshotRequest
import gg.grounds.grpc.permissions.PermissionEffect as GrpcPermissionEffect
import gg.grounds.grpc.permissions.PermissionGrant as GrpcPermissionGrant
import gg.grounds.grpc.permissions.PermissionScope as GrpcPermissionScope
import gg.grounds.grpc.permissions.PermissionScopeKind as GrpcPermissionScopeKind
import gg.grounds.grpc.permissions.PermissionSnapshotServiceGrpc
import gg.grounds.grpc.permissions.PlayerPermissionSnapshot
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.ReleasedPermissionSnapshotFixture
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PermissionSnapshotClientTest {
    private var server: Server? = null

    @AfterEach
    fun stopServer() {
        server?.shutdownNow()
    }

    @Test
    fun `consumes authoritative backend snapshot without caller groups`() {
        val playerId = ReleasedPermissionSnapshotFixture.playerId
        var capturedRequest: GetPlayerSnapshotRequest? = null
        val serverName = InProcessServerBuilder.generateName()
        server =
            InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(
                    object : PermissionSnapshotServiceGrpc.PermissionSnapshotServiceImplBase() {
                        override fun getPlayerSnapshot(
                            request: GetPlayerSnapshotRequest,
                            responseObserver: StreamObserver<PlayerPermissionSnapshot>,
                        ) {
                            capturedRequest = request
                            responseObserver.onNext(ReleasedPermissionSnapshotFixture.proto())
                            responseObserver.onCompleted()
                        }
                    }
                )
                .build()
                .start()
        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()

        GrpcPermissionSnapshotClient.create(channel).use { client ->
            val result =
                client.fetchSnapshot(
                    playerId,
                    PermissionSnapshotContext(serverType = "velocity", serverId = "proxy-1"),
                )

            val success =
                assertInstanceOf(PermissionSnapshotFetchResult.Success::class.java, result)
            val request = requireNotNull(capturedRequest)
            assertEquals(setOf(1, 3, 4), request.allFields.keys.map { it.number }.toSet())
            assertEquals(playerId.toString(), request.playerId)
            assertEquals("velocity", request.serverType)
            assertEquals("proxy-1", request.serverId)
            assertEquals(ReleasedPermissionSnapshotFixture.expected(), success.snapshot)
        }
    }

    @Test
    fun `maps environment scopes and drops scope kinds this build does not know`() {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000456")
        val serverName = InProcessServerBuilder.generateName()
        server =
            InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(
                    object : PermissionSnapshotServiceGrpc.PermissionSnapshotServiceImplBase() {
                        override fun getPlayerSnapshot(
                            request: GetPlayerSnapshotRequest,
                            responseObserver: StreamObserver<PlayerPermissionSnapshot>,
                        ) {
                            responseObserver.onNext(
                                PlayerPermissionSnapshot.newBuilder()
                                    .setPlayerId(playerId.toString())
                                    .setPolicyVersion(1)
                                    .setIssuedAt(secondsFromEpoch(0))
                                    .setRefreshAfter(secondsFromEpoch(60))
                                    .setExpiresAt(secondsFromEpoch(3600))
                                    .addAllowPatterns(
                                        grant(
                                            "stage.only",
                                            GrpcPermissionScopeKind
                                                .PERMISSION_SCOPE_KIND_ENVIRONMENT,
                                            "stage",
                                        )
                                    )
                                    .addAllowPatterns(
                                        grant(
                                            "from.the.future",
                                            GrpcPermissionScopeKind
                                                .PERMISSION_SCOPE_KIND_UNSPECIFIED,
                                            "whatever",
                                        )
                                    )
                                    .build()
                            )
                            responseObserver.onCompleted()
                        }
                    }
                )
                .build()
                .start()
        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()

        GrpcPermissionSnapshotClient.create(channel).use { client ->
            val result =
                client.fetchSnapshot(playerId, PermissionSnapshotContext(environment = "stage"))

            val success =
                assertInstanceOf(PermissionSnapshotFetchResult.Success::class.java, result)
            assertEquals(1, success.snapshot.allowPatterns.size)
            assertEquals("stage.only", success.snapshot.allowPatterns.single().pattern)
            assertEquals(
                PermissionScope.environment("stage"),
                success.snapshot.allowPatterns.single().scope,
            )
        }
    }

    private fun grant(
        pattern: String,
        kind: GrpcPermissionScopeKind,
        scopeValue: String,
    ): GrpcPermissionGrant =
        GrpcPermissionGrant.newBuilder()
            .setEffect(GrpcPermissionEffect.PERMISSION_EFFECT_ALLOW)
            .setPattern(pattern)
            .setScope(GrpcPermissionScope.newBuilder().setKind(kind).setValue(scopeValue))
            .build()

    private fun secondsFromEpoch(seconds: Long): Timestamp =
        Timestamp.newBuilder().setSeconds(seconds).build()
}
