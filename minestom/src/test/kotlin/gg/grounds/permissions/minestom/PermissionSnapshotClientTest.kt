package gg.grounds.permissions.minestom

import com.google.protobuf.Timestamp
import gg.grounds.grpc.permissions.GetPlayerSnapshotRequest
import gg.grounds.grpc.permissions.PermissionEffect.PERMISSION_EFFECT_ALLOW
import gg.grounds.grpc.permissions.PermissionGrant
import gg.grounds.grpc.permissions.PermissionGrantOrigin
import gg.grounds.grpc.permissions.PermissionGrantOriginKind.PERMISSION_GRANT_ORIGIN_KIND_GROUP_MAPPING
import gg.grounds.grpc.permissions.PermissionGrantSource.PERMISSION_GRANT_SOURCE_ROLE
import gg.grounds.grpc.permissions.PermissionScope
import gg.grounds.grpc.permissions.PermissionScopeKind.PERMISSION_SCOPE_KIND_GLOBAL
import gg.grounds.grpc.permissions.PermissionSnapshotServiceGrpc
import gg.grounds.grpc.permissions.PlayerPermissionSnapshot
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
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
    fun `sends only player and server context and maps grant origin`() {
        val playerId = UUID.randomUUID()
        var capturedRequest: GetPlayerSnapshotRequest? = null
        server =
            NettyServerBuilder.forPort(0)
                .addService(
                    object : PermissionSnapshotServiceGrpc.PermissionSnapshotServiceImplBase() {
                        override fun getPlayerSnapshot(
                            request: GetPlayerSnapshotRequest,
                            responseObserver: StreamObserver<PlayerPermissionSnapshot>,
                        ) {
                            capturedRequest = request
                            responseObserver.onNext(snapshotResponse(playerId))
                            responseObserver.onCompleted()
                        }
                    }
                )
                .build()
                .start()

        GrpcPermissionSnapshotClient.create("localhost:${server!!.port}").use { client ->
            val result =
                client.fetchSnapshot(
                    playerId,
                    PermissionSnapshotContext(serverType = "paper", serverId = "lobby-1"),
                )

            val success =
                assertInstanceOf(PermissionSnapshotFetchResult.Success::class.java, result)
            val request = requireNotNull(capturedRequest)
            assertEquals(setOf(1, 3, 4), request.allFields.keys.map { it.number }.toSet())
            assertEquals(playerId.toString(), request.playerId)
            assertEquals("paper", request.serverType)
            assertEquals("lobby-1", request.serverId)
            assertEquals(
                gg.grounds.permissions.PermissionGrantOrigin(
                    kind = gg.grounds.permissions.PermissionGrantOriginKind.GROUP_MAPPING,
                    roleKey = "moderator",
                    mappingId = "mapping-1",
                    inheritedPath = listOf("member", "moderator"),
                ),
                success.snapshot.allowPatterns.single().origin,
            )
        }
    }

    private fun snapshotResponse(playerId: UUID): PlayerPermissionSnapshot {
        val timestamp = Timestamp.newBuilder().setSeconds(1_700_000_000).build()
        return PlayerPermissionSnapshot.newBuilder()
            .setPlayerId(playerId.toString())
            .setPolicyVersion(42)
            .setIssuedAt(timestamp)
            .setRefreshAfter(timestamp)
            .setExpiresAt(timestamp)
            .addAllowPatterns(
                PermissionGrant.newBuilder()
                    .setEffect(PERMISSION_EFFECT_ALLOW)
                    .setPattern("grounds.chat")
                    .setScope(PermissionScope.newBuilder().setKind(PERMISSION_SCOPE_KIND_GLOBAL))
                    .setSource(PERMISSION_GRANT_SOURCE_ROLE)
                    .setOrigin(
                        PermissionGrantOrigin.newBuilder()
                            .setKind(PERMISSION_GRANT_ORIGIN_KIND_GROUP_MAPPING)
                            .setRoleKey("moderator")
                            .setMappingId("mapping-1")
                            .addAllInheritedPath(listOf("member", "moderator"))
                    )
            )
            .build()
    }
}
