package gg.grounds.permissions

import com.google.protobuf.TextFormat
import gg.grounds.grpc.permissions.PlayerPermissionSnapshot
import java.time.Instant
import java.util.UUID

object ReleasedPermissionSnapshotFixture {
    val playerId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000321")

    fun proto(): PlayerPermissionSnapshot {
        val fixture =
            requireNotNull(javaClass.getResourceAsStream(FIXTURE_PATH)) {
                "Released permission snapshot fixture is missing (path=$FIXTURE_PATH)"
            }
        return fixture.bufferedReader().use { reader ->
            PlayerPermissionSnapshot.newBuilder()
                .also { TextFormat.getParser().merge(reader, it) }
                .build()
        }
    }

    fun expected(): PermissionSnapshot =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = 73,
            issuedAt = Instant.parse("2030-01-01T00:00:00Z"),
            refreshAfter = Instant.parse("2030-01-01T00:05:00Z"),
            expiresAt = Instant.parse("2030-01-01T01:00:00Z"),
            allowPatterns =
                listOf(
                    roleGrant(
                        pattern = "spawn.use",
                        origin = PermissionGrantOriginKind.DEFAULT_ROLE,
                        roleKey = "default",
                    ),
                    roleGrant(
                        pattern = "build.use",
                        origin = PermissionGrantOriginKind.DIRECT_ROLE,
                        roleKey = "builder",
                    ),
                    roleGrant(
                        pattern = "staff.chat",
                        origin = PermissionGrantOriginKind.GROUP_MAPPING,
                        roleKey = "staff",
                        mappingId = MAPPING_ID,
                    ),
                    roleGrant(
                        pattern = "home.use",
                        origin = PermissionGrantOriginKind.GROUP_MAPPING,
                        roleKey = "member",
                        mappingId = MAPPING_ID,
                        inheritedPath = listOf("staff", "member"),
                    ),
                ),
            denyPatterns =
                listOf(
                    PermissionGrant(
                        effect = PermissionEffect.DENY,
                        pattern = "operator.use",
                        scope = PermissionScope.server("survival-1"),
                        source = PermissionGrantSource.PLAYER,
                        expiresAt = Instant.parse("2030-01-01T01:00:00Z"),
                        origin =
                            PermissionGrantOrigin(
                                kind = PermissionGrantOriginKind.DIRECT_PERMISSION
                            ),
                    )
                ),
            roleKeys = setOf("default", "builder", "staff", "member"),
            roleMetadata =
                listOf(
                    RoleMetadata("default", "Default", "[D]", "gray", 100),
                    RoleMetadata("builder", "Builder", "[B]", "green", 75),
                    RoleMetadata("staff", "Staff", "[S]", "red", 50),
                    RoleMetadata("member", "Member", null, null, 25),
                ),
        )

    private fun roleGrant(
        pattern: String,
        origin: PermissionGrantOriginKind,
        roleKey: String,
        mappingId: String? = null,
        inheritedPath: List<String> = emptyList(),
    ): PermissionGrant =
        PermissionGrant(
            effect = PermissionEffect.ALLOW,
            pattern = pattern,
            scope = PermissionScope.global(),
            source = PermissionGrantSource.ROLE,
            origin = PermissionGrantOrigin(origin, roleKey, mappingId, inheritedPath),
        )

    private const val FIXTURE_PATH =
        "/fixtures/service-permissions-v0.7.0/player-permission-snapshot.textproto"
    private const val MAPPING_ID = "00000000-0000-0000-0000-000000000322"
}
