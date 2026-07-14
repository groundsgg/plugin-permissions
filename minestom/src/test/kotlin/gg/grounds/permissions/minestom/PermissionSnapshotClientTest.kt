package gg.grounds.permissions.minestom

import gg.grounds.grpc.permissions.GetPlayerSnapshotRequest
import gg.grounds.grpc.permissions.PermissionSnapshotServiceGrpc
import gg.grounds.grpc.permissions.PlayerPermissionSnapshot
import gg.grounds.permissions.ReleasedPermissionSnapshotFixture
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
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
                    PermissionSnapshotContext(serverType = "paper", serverId = "lobby-1"),
                )

            val success =
                assertInstanceOf(PermissionSnapshotFetchResult.Success::class.java, result)
            val request = requireNotNull(capturedRequest)
            assertEquals(setOf(1, 3, 4), request.allFields.keys.map { it.number }.toSet())
            assertEquals(playerId.toString(), request.playerId)
            assertEquals("paper", request.serverType)
            assertEquals("lobby-1", request.serverId)
            assertEquals(ReleasedPermissionSnapshotFixture.expected(), success.snapshot)
        }
    }
}
