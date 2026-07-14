package gg.grounds.permissions

import gg.grounds.grpc.permissions.GetPlayerSnapshotRequest
import gg.grounds.grpc.permissions.PermissionGrant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PermissionProtoContractTest {
    @Test
    fun `snapshot request reserves removed keycloak groups field`() {
        val descriptor = GetPlayerSnapshotRequest.getDescriptor().toProto()

        assertFalse(
            GetPlayerSnapshotRequest.getDescriptor().fields.any { it.name == "keycloak_groups" }
        )
        assertEquals(listOf("keycloak_groups"), descriptor.reservedNameList)
        assertEquals(listOf(2 to 3), descriptor.reservedRangeList.map { it.start to it.end })
        assertEquals(
            3,
            GetPlayerSnapshotRequest.getDescriptor().findFieldByName("server_type").number,
        )
        assertEquals(
            4,
            GetPlayerSnapshotRequest.getDescriptor().findFieldByName("server_id").number,
        )
    }

    @Test
    fun `permission grants expose structured origin metadata`() {
        val descriptor = PermissionGrant.getDescriptor()

        assertNotNull(descriptor.findFieldByName("origin"))
        assertEquals(6, descriptor.findFieldByName("origin").number)
    }
}
