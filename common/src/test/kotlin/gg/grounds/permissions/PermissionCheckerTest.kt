package gg.grounds.permissions

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionCheckerTest {
    private val now = Instant.parse("2026-06-28T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val playerId = UUID.fromString("f39f6265-f481-4c90-8dd8-4d334b82f937")

    @Test
    fun returnsSnapshotForPlayer() {
        val snapshot = snapshot(listOf(allow("chat.send")))
        val permissions = SnapshotPermissions(mapOf(playerId to snapshot), clock = clock)

        assertEquals(snapshot, permissions.snapshot(playerId))
    }

    @Test
    fun storedSnapshotsDoNotReflectCallerCollectionMutations() {
        val allowPatterns = mutableListOf(allow("chat.send"))
        val denyPatterns = mutableListOf<PermissionGrant>()
        val roleKeys = mutableSetOf("member")
        val roleMetadata = mutableListOf(RoleMetadata(key = "member", name = "Member"))
        val snapshot =
            PermissionSnapshot(
                playerId = playerId,
                policyVersion = 1,
                issuedAt = now,
                refreshAfter = now.plusSeconds(60),
                expiresAt = now.plusSeconds(300),
                allowPatterns = allowPatterns,
                denyPatterns = denyPatterns,
                roleKeys = roleKeys,
                roleMetadata = roleMetadata,
            )
        val permissions = SnapshotPermissions(mapOf(playerId to snapshot), clock = clock)

        allowPatterns.clear()
        denyPatterns += deny("chat.send")
        roleKeys.clear()
        roleMetadata.clear()

        assertTrue(permissions.hasPermission(playerId, "chat.send"))
        assertEquals(listOf(allow("chat.send")), permissions.snapshot(playerId)?.allowPatterns)
        assertEquals(setOf("member"), permissions.snapshot(playerId)?.roleKeys)
    }

    @Test
    fun inMemorySnapshotStoreCopiesInputAndSupportsReplacement() {
        val initialSnapshots = mutableMapOf(playerId to snapshot(listOf(allow("chat.send"))))
        val snapshots = InMemoryPermissionSnapshots(initialSnapshots)
        val permissions = SnapshotPermissions(snapshots, clock = clock)

        initialSnapshots.clear()

        assertTrue(permissions.hasPermission(playerId, "chat.send"))

        snapshots.replaceAll(mapOf(playerId to snapshot(listOf(allow("economy.pay")))))

        assertFalse(permissions.hasPermission(playerId, "chat.send"))
        assertTrue(permissions.hasPermission(playerId, "economy.pay"))
    }

    @Test
    fun failsClosedWhenSnapshotIsMissingOrExpired() {
        val missingPermissions = SnapshotPermissions(emptyMap(), clock = clock)
        val expiredPermissions =
            SnapshotPermissions(
                mapOf(playerId to snapshot(listOf(allow("*")), expiresAt = now)),
                clock = clock,
            )

        assertFalse(missingPermissions.hasPermission(playerId, "chat.send"))
        assertFalse(expiredPermissions.hasPermission(playerId, "chat.send"))
    }

    @Test
    fun exactAndPrefixWildcardPatternsMatchExpectedPermissions() {
        val permissions = permissions(allowPatterns = listOf(allow("chat.send"), allow("region.*")))

        assertTrue(permissions.hasPermission(playerId, "chat.send"))
        assertTrue(permissions.hasPermission(playerId, "region.edit"))
        assertFalse(permissions.hasPermission(playerId, "region"))
        assertFalse(permissions.hasPermission(playerId, "region."))
    }

    @Test
    fun starPatternMatchesEveryPermission() {
        val permissions = permissions(allowPatterns = listOf(allow("*")))

        assertTrue(permissions.hasPermission(playerId, "economy.pay"))
        assertTrue(permissions.hasPermission(playerId, "chat"))
    }

    @Test
    fun mostSpecificScopeWinsBeforeSourcePatternAndEffect() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("region.edit", PermissionScope.global())),
                denyPatterns = listOf(deny("region.*", PermissionScope.serverType("survival"))),
                scope = PermissionCheckScope.server(server = "survival-1", serverType = "survival"),
            )

        assertFalse(permissions.hasPermission(playerId, "region.edit"))
    }

    @Test
    fun directPlayerGrantWinsBeforeMoreSpecificPatternAtSameScope() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("*", source = PermissionGrantSource.PLAYER)),
                denyPatterns = listOf(deny("chat.send", source = PermissionGrantSource.ROLE)),
            )

        assertTrue(permissions.hasPermission(playerId, "chat.send"))
    }

    @Test
    fun exactPatternWinsOverPrefixWildcardAtSameScopeAndSource() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("chat.send")),
                denyPatterns = listOf(deny("chat.*")),
            )

        assertTrue(permissions.hasPermission(playerId, "chat.send"))
    }

    @Test
    fun denyWinsWhenSpecificityTies() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("economy.pay")),
                denyPatterns = listOf(deny("economy.pay")),
            )

        assertFalse(permissions.hasPermission(playerId, "economy.pay"))
    }

    @Test
    fun scopedGrantsOnlyMatchCompatibleCheckScopes() {
        val serverTypePermissions =
            permissions(
                allowPatterns = listOf(allow("queue.join", PermissionScope.serverType("survival"))),
                scope = PermissionCheckScope.serverOnly("survival-1"),
            )
        val serverPermissions =
            permissions(
                allowPatterns = listOf(allow("queue.join", PermissionScope.server("survival-1"))),
                scope = PermissionCheckScope.server(server = "survival-1", serverType = "survival"),
            )
        val otherServerPermissions =
            permissions(
                allowPatterns = listOf(allow("queue.join", PermissionScope.server("survival-1"))),
                scope = PermissionCheckScope.server(server = "survival-2", serverType = "survival"),
            )

        assertFalse(serverTypePermissions.hasPermission(playerId, "queue.join"))
        assertTrue(serverPermissions.hasPermission(playerId, "queue.join"))
        assertFalse(otherServerPermissions.hasPermission(playerId, "queue.join"))
    }

    @Test
    fun publicScopedPermissionCheckUsesProvidedScope() {
        val permissions: Permissions =
            permissions(
                allowPatterns = listOf(allow("queue.join", PermissionScope.serverType("survival")))
            )

        assertTrue(
            permissions.hasPermission(
                playerId,
                "queue.join",
                PermissionCheckScope.serverType("survival"),
            )
        )
        assertFalse(
            permissions.hasPermission(
                playerId,
                "queue.join",
                PermissionCheckScope.serverType("creative"),
            )
        )
    }

    @Test
    fun ignoresExpiredIndividualGrants() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("chat.send", expiresAt = now.minusSeconds(1))),
                denyPatterns = listOf(deny("chat.send", expiresAt = now.plusSeconds(60))),
            )

        assertFalse(permissions.hasPermission(playerId, "chat.send"))
    }

    // An operator holds ALLOW *. That says "give this player everything" — it must not hand them
    // grounds.chat.muted, which takes chat away. This muted every admin on the network.
    @Test
    fun globalWildcardDoesNotGrantANegativePermission() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("*")),
                negativePermissions = setOf("grounds.chat.muted"),
            )

        assertFalse(permissions.hasPermission(playerId, "grounds.chat.muted"))
        assertTrue(permissions.hasPermission(playerId, "grounds.chat.staff"))
    }

    @Test
    fun prefixWildcardDoesNotGrantANegativePermission() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("grounds.chat.*")),
                negativePermissions = setOf("grounds.chat.muted"),
            )

        assertFalse(permissions.hasPermission(playerId, "grounds.chat.muted"))
        assertTrue(permissions.hasPermission(playerId, "grounds.chat.staff"))
    }

    // Muting somebody still has to work — by name.
    @Test
    fun exactGrantStillAppliesANegativePermission() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("grounds.chat.muted")),
                negativePermissions = setOf("grounds.chat.muted"),
            )

        assertTrue(permissions.hasPermission(playerId, "grounds.chat.muted"))
    }

    // A wildcard cannot grant it, so a DENY next to it has nothing to overrule — but an exact
    // grant that was later revoked must still lose.
    @Test
    fun denyBeatsAnExactGrantOfANegativePermission() {
        val permissions =
            permissions(
                allowPatterns = listOf(allow("grounds.chat.muted")),
                denyPatterns = listOf(deny("grounds.chat.muted")),
                negativePermissions = setOf("grounds.chat.muted"),
            )

        assertFalse(permissions.hasPermission(playerId, "grounds.chat.muted"))
    }

    private fun permissions(
        allowPatterns: List<PermissionGrant> = emptyList(),
        denyPatterns: List<PermissionGrant> = emptyList(),
        scope: PermissionCheckScope = PermissionCheckScope.global(),
        negativePermissions: Set<String> = emptySet(),
    ): Permissions =
        SnapshotPermissions(
            mapOf(playerId to snapshot(allowPatterns = allowPatterns, denyPatterns = denyPatterns)),
            defaultScope = scope,
            clock = clock,
            negativePermissions = negativePermissions,
        )

    private fun snapshot(
        grants: List<PermissionGrant> = emptyList(),
        allowPatterns: List<PermissionGrant> =
            grants.filter { it.effect == PermissionEffect.ALLOW },
        denyPatterns: List<PermissionGrant> = grants.filter { it.effect == PermissionEffect.DENY },
        expiresAt: Instant = now.plusSeconds(300),
    ) =
        PermissionSnapshot(
            playerId = playerId,
            policyVersion = 1,
            issuedAt = now,
            refreshAfter = now.plusSeconds(60),
            expiresAt = expiresAt,
            allowPatterns = allowPatterns,
            denyPatterns = denyPatterns,
            roleKeys = setOf("member"),
            roleMetadata = listOf(RoleMetadata(key = "member", name = "Member")),
        )

    private fun allow(
        pattern: String,
        scope: PermissionScope = PermissionScope.global(),
        source: PermissionGrantSource = PermissionGrantSource.ROLE,
        expiresAt: Instant? = null,
    ) =
        PermissionGrant(
            effect = PermissionEffect.ALLOW,
            pattern = pattern,
            scope = scope,
            source = source,
            expiresAt = expiresAt,
        )

    private fun deny(
        pattern: String,
        scope: PermissionScope = PermissionScope.global(),
        source: PermissionGrantSource = PermissionGrantSource.ROLE,
        expiresAt: Instant? = null,
    ) =
        PermissionGrant(
            effect = PermissionEffect.DENY,
            pattern = pattern,
            scope = scope,
            source = source,
            expiresAt = expiresAt,
        )
}
