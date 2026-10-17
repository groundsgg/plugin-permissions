package gg.grounds.permissions.velocity

import gg.grounds.permissions.*
import java.time.*
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemotePlayerRolesTest {
    private val id = UUID.randomUUID()
    private val now = Instant.parse("2026-10-17T10:00:00Z")

    private fun snapshot() =
        PermissionSnapshot(
            playerId = id,
            policyVersion = 1,
            issuedAt = now,
            refreshAfter = now.plusSeconds(60),
            expiresAt = now.plusSeconds(600),
            allowPatterns =
                listOf(
                    PermissionGrant(
                        PermissionEffect.ALLOW,
                        "*",
                        PermissionScope.global(),
                        PermissionGrantSource.ROLE,
                    )
                ),
            denyPatterns = emptyList(),
            roleKeys = setOf("admin"),
            roleMetadata = listOf(RoleMetadata("admin", "Admin", sortOrder = 0)),
        )

    @Test
    fun `remote display loads asynchronously without populating authorization snapshots`() {
        val tasks = mutableListOf<Runnable>()
        var calls = 0
        val local = InMemoryPermissionSnapshots()
        val remote =
            RemotePlayerRoles(
                fetchSnapshot = {
                    calls++
                    snapshot()
                },
                executor = Executor(tasks::add),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
        val query = SnapshotRoleQuery(local, remote::roleMetadataOf)
        repeat(5) { assertNull(query.highestRoleOf(id)) }
        assertEquals(1, tasks.size)
        assertEquals(0, calls)
        tasks.single().run()
        assertEquals("admin", query.highestRoleOf(id)?.key)
        assertNull(local.get(id))
        assertEquals(1, calls)
    }

    @Test
    fun `local authoritative role takes precedence over remote display cache`() {
        val local = InMemoryPermissionSnapshots(mapOf(id to snapshot()))
        val query = SnapshotRoleQuery(local, { error("must not query remote for local players") })
        assertEquals("admin", query.highestRoleOf(id)?.key)
    }

    @Test
    fun `rejected scheduling does not break tab rendering or busy retry`() {
        var attempts = 0
        val remote =
            RemotePlayerRoles(
                fetchSnapshot = { error("not scheduled") },
                executor =
                    Executor {
                        attempts++
                        throw java.util.concurrent.RejectedExecutionException()
                    },
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
        repeat(5) { assertNull(remote.roleMetadataOf(id)) }
        assertEquals(1, attempts)
    }

    private class TestClock(var value: Instant) : Clock() {
        override fun instant() = value

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    @Test
    fun `role removal refreshes metadata while expired cache never renders old admin`() {
        val tasks = mutableListOf<Runnable>()
        val clock = TestClock(now)
        var response = snapshot()
        val remote = RemotePlayerRoles({ response }, Executor(tasks::add), clock)
        assertNull(remote.roleMetadataOf(id))
        tasks.removeAt(0).run()
        assertEquals("admin", remote.roleMetadataOf(id)?.single()?.key)
        clock.value = now.plusSeconds(31)
        response = snapshot().copy(roleMetadata = emptyList())
        assertEquals("admin", remote.roleMetadataOf(id)?.single()?.key)
        tasks.removeAt(0).run()
        assertEquals(emptyList<RoleMetadata>(), remote.roleMetadataOf(id))
        clock.value = now.plusSeconds(601)
        assertNull(remote.roleMetadataOf(id))
    }

    @Test
    fun `foreign player response is rejected and failures are throttled`() {
        val tasks = mutableListOf<Runnable>()
        var calls = 0
        val remote =
            RemotePlayerRoles(
                {
                    calls++
                    snapshot().copy(playerId = UUID.randomUUID())
                },
                Executor(tasks::add),
                Clock.fixed(now, ZoneOffset.UTC),
            )
        assertNull(remote.roleMetadataOf(id))
        tasks.removeAt(0).run()
        repeat(10) { assertNull(remote.roleMetadataOf(id)) }
        assertEquals(1, calls)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `disconnected local snapshots do not override the current remote role`() {
        val local = InMemoryPermissionSnapshots(mapOf(id to snapshot()))
        val query = SnapshotRoleQuery(local, { listOf(RoleMetadata("user", "User")) }, { false })
        assertEquals("user", query.highestRoleOf(id)?.key)
        assertEquals("admin", local.get(id)?.roleMetadata?.single()?.key)
    }
}
