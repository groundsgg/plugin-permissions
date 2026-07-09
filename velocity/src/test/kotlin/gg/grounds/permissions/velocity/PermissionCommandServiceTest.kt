package gg.grounds.permissions.velocity

import gg.grounds.permissions.InMemoryPermissionSnapshots
import gg.grounds.permissions.PermissionEffect
import gg.grounds.permissions.PermissionGrant
import gg.grounds.permissions.PermissionGrantSource
import gg.grounds.permissions.PermissionScope
import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.SnapshotPermissions
import gg.grounds.permissions.catalog.PermissionManifest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionCommandServiceTest {
    private val now: Instant = Instant.parse("2026-07-09T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val playerId: UUID = UUID.fromString("f39f6265-f481-4c90-8dd8-4d334b82f937")
    private val snapshots = InMemoryPermissionSnapshots()
    private val permissions = SnapshotPermissions(snapshots, clock = clock)

    @Test
    fun `status returns the configured runtime details`() {
        val service = service()

        val result = service.status()

        assertEquals(
            PermissionCommandResult.Success(
                listOf(
                    "version=0.1.0",
                    "target=permissions:9090",
                    "serverType=lobby",
                    "serverId=proxy-1",
                )
            ),
            result,
        )
    }

    @Test
    fun `info returns loaded snapshot details`() {
        snapshots.put(snapshot(roleKeys = setOf("moderator")))
        val service = service()

        val result = service.info(playerId)

        val success = result as? PermissionCommandResult.Success
        assertTrue(success != null)
        assertEquals(
            listOf(
                "playerId=$playerId",
                "policyVersion=7",
                "roles=moderator",
                "allow=chat.send",
                "deny=chat.moderation.*",
            ),
            success!!.lines,
        )
    }

    @Test
    fun `check evaluates the loaded snapshot at the requested scope`() {
        snapshots.put(snapshot())
        val service = service()

        val result = service.check(playerId, "chat.send", PermissionCheckScopeArgument.global())

        assertEquals(PermissionCommandResult.Success(listOf("allowed=true")), result)
    }

    @Test
    fun `check rejects a permission overridden by a deny grant`() {
        snapshots.put(snapshot())
        val service = service()

        val result =
            service.check(playerId, "chat.moderation.kick", PermissionCheckScopeArgument.global())

        assertEquals(PermissionCommandResult.Success(listOf("allowed=false")), result)
    }

    @Test
    fun `check reports a player without a loaded snapshot`() {
        val service = service()

        val result = service.check(playerId, "chat.send", PermissionCheckScopeArgument.global())

        assertEquals(
            PermissionCommandResult.Failure("No permission snapshot is loaded for this player."),
            result,
        )
    }

    @Test
    fun `refresh reloads and stores the latest snapshot`() {
        val refreshed = snapshot(policyVersion = 8)
        val service = service { _, _ -> PermissionLoginResult.allowed(refreshed) }

        val result = service.refresh(playerId, "Alex")

        assertEquals(PermissionCommandResult.Success(listOf("policyVersion=8")), result)
        assertEquals(refreshed, snapshots.get(playerId))
    }

    @Test
    fun `refresh reports unavailable snapshots`() {
        val service = service { _, _ -> PermissionLoginResult.denied() }

        val result = service.refresh(playerId, "Alex")

        assertEquals(
            PermissionCommandResult.Failure(
                "Permissions are currently unavailable. Please try again later."
            ),
            result,
        )
    }

    @Test
    fun `scope parser accepts global server type and server scopes`() {
        assertEquals(
            PermissionCheckScopeArgument.global(),
            PermissionCheckScopeArgument.parse("global"),
        )
        assertEquals(
            PermissionCheckScopeArgument(serverType = "lobby"),
            PermissionCheckScopeArgument.parse("server-type=lobby"),
        )
        assertEquals(
            PermissionCheckScopeArgument(server = "lobby-1"),
            PermissionCheckScopeArgument.parse("server=lobby-1"),
        )
    }

    @Test
    fun `scope parser rejects invalid values`() {
        assertFalse(PermissionCheckScopeArgument.parseOrNull("world=lobby") != null)
    }

    @Test
    fun `router resolves an online player for snapshot info`() {
        snapshots.put(snapshot(roleKeys = setOf("moderator")))
        val router = router()

        val result = router.execute(arrayOf("user", "Alex", "info"))

        assertEquals(
            PermissionCommandResult.Success(
                listOf(
                    "playerId=$playerId",
                    "policyVersion=7",
                    "roles=moderator",
                    "allow=chat.send",
                    "deny=chat.moderation.*",
                )
            ),
            result,
        )
    }

    @Test
    fun `router rejects a player who is not online`() {
        val router = router()

        val result = router.execute(arrayOf("user", "Ada", "info"))

        assertEquals(PermissionCommandResult.Failure("Player is not online: Ada"), result)
    }

    @Test
    fun `router refreshes every online player`() {
        val secondPlayer = OnlinePermissionPlayer(UUID.randomUUID(), "Ada")
        val refreshed = snapshot(policyVersion = 9)
        val router =
            router(
                players = listOf(OnlinePermissionPlayer(playerId, "Alex"), secondPlayer),
                refresh = { id, _ ->
                    if (id == playerId) PermissionLoginResult.allowed(refreshed)
                    else PermissionLoginResult.denied()
                },
            )

        val result = router.execute(arrayOf("refresh", "all"))

        assertEquals(PermissionCommandResult.Success(listOf("refreshed=1", "failed=1")), result)
        assertEquals(refreshed, snapshots.get(playerId))
    }

    @Test
    fun `router reports syntax for unsupported commands`() {
        val router = router()

        val result = router.execute(arrayOf("user", "Alex", "permission", "set", "chat.send"))

        assertEquals(
            PermissionCommandResult.Failure(
                "Usage: /perm user <player> permission check <node> [scope]"
            ),
            result,
        )
    }

    @Test
    fun `router suggests commands players and scope values`() {
        val router = router()

        assertEquals(listOf("help", "refresh", "status", "user"), router.suggest(emptyArray()))
        assertEquals(listOf("Alex"), router.suggest(arrayOf("user", "al")))
        assertEquals(
            listOf("info", "permission", "refresh"),
            router.suggest(arrayOf("user", "Alex", "")),
        )
        assertEquals(
            listOf("global", "server=", "server-type="),
            router.suggest(arrayOf("user", "Alex", "permission", "check", "chat.send", "")),
        )
        assertEquals(listOf("Alex", "all"), router.suggest(arrayOf("refresh", "")))
    }

    @Test
    fun `command permissions map every supported command path`() {
        val manifest =
            PermissionManifest.load(
                """
                {
                  "source": "plugin-permissions",
                  "permissions": [
                    { "key": "grounds.permissions.command", "label": "Permissions commands", "description": "Allows command help.", "supportedScopes": ["GLOBAL"] },
                    { "key": "grounds.permissions.command.status", "label": "Permissions status", "description": "Allows status.", "supportedScopes": ["GLOBAL"] },
                    { "key": "grounds.permissions.command.user.info", "label": "Player snapshot info", "description": "Allows info.", "supportedScopes": ["GLOBAL"] },
                    { "key": "grounds.permissions.command.user.check", "label": "Player permission check", "description": "Allows checks.", "supportedScopes": ["GLOBAL"] },
                    { "key": "grounds.permissions.command.user.refresh", "label": "Player snapshot refresh", "description": "Allows player refresh.", "supportedScopes": ["GLOBAL"] },
                    { "key": "grounds.permissions.command.refresh", "label": "Snapshot refresh", "description": "Allows bulk refresh.", "supportedScopes": ["GLOBAL"] }
                  ]
                }
                """
                    .trimIndent()
                    .byteInputStream()
            )
        val permissions = PermissionCommandPermissions.fromManifest(manifest)

        assertEquals("grounds.permissions.command", permissions.forArguments(emptyArray()))
        assertEquals(
            "grounds.permissions.command.status",
            permissions.forArguments(arrayOf("status")),
        )
        assertEquals(
            "grounds.permissions.command.user.check",
            permissions.forArguments(arrayOf("user", "Alex", "permission", "check", "chat.send")),
        )
        assertEquals(
            "grounds.permissions.command.refresh",
            permissions.forArguments(arrayOf("refresh", "all")),
        )
    }

    @Test
    fun `packaged command permissions are complete`() {
        val permissions =
            PermissionCommandPermissions.fromManifest(
                PermissionManifest.loadRequiredResource(
                    GroundsPermissionsPlugin::class.java.classLoader
                )
            )

        assertEquals("grounds.permissions.command", permissions.forArguments(emptyArray()))
        assertEquals(
            "grounds.permissions.command.user.info",
            permissions.forArguments(arrayOf("user", "Alex", "info")),
        )
    }

    private fun service(
        refresh: (UUID, String) -> PermissionLoginResult = { _, _ ->
            PermissionLoginResult.denied()
        }
    ): PermissionCommandService =
        PermissionCommandService(
            snapshots = snapshots,
            permissions = permissions,
            refreshSnapshot = refresh,
            status =
                PermissionCommandStatus(
                    version = "0.1.0",
                    grpcTarget = "permissions:9090",
                    context = PermissionSnapshotContext(serverType = "lobby", serverId = "proxy-1"),
                ),
        )

    private fun router(
        players: List<OnlinePermissionPlayer> = listOf(OnlinePermissionPlayer(playerId, "Alex")),
        refresh: (UUID, String) -> PermissionLoginResult = { _, _ ->
            PermissionLoginResult.denied()
        },
    ): PermissionCommandRouter {
        val byName = players.associateBy { it.username.lowercase() }
        return PermissionCommandRouter(
            service = service(refresh),
            findOnlinePlayer = { name -> byName[name.lowercase()] },
            onlinePlayers = { players },
        )
    }

    private fun snapshot(
        policyVersion: Long = 7,
        roleKeys: Set<String> = setOf("member"),
    ): PermissionSnapshot =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = policyVersion,
            issuedAt = now.minusSeconds(60),
            refreshAfter = now.plusSeconds(300),
            expiresAt = now.plusSeconds(3600),
            allowPatterns =
                listOf(
                    PermissionGrant(
                        effect = PermissionEffect.ALLOW,
                        pattern = "chat.send",
                        scope = PermissionScope.global(),
                        source = PermissionGrantSource.ROLE,
                    )
                ),
            denyPatterns =
                listOf(
                    PermissionGrant(
                        effect = PermissionEffect.DENY,
                        pattern = "chat.moderation.*",
                        scope = PermissionScope.global(),
                        source = PermissionGrantSource.ROLE,
                    )
                ),
            roleKeys = roleKeys,
            roleMetadata = listOf(RoleMetadata(key = "moderator", name = "Moderator")),
        )
}
