package gg.grounds.permissions.minestom

import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.runtime.PlayerPrefetch
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.Logger

/** How a login's snapshot related to a prefetch, logged with every login. */
internal enum class PrefetchOutcome {
    /** Nothing was prefetched for this player. */
    NONE,

    /** The login used the prefetched snapshot, or waited for the prefetch already in flight. */
    HIT,

    /** A prefetch was there but unusable (failed, stale, or not started yet); the login fetched. */
    MISS,
}

internal data class LoginSnapshot(val snapshot: PermissionSnapshot, val prefetch: PrefetchOutcome)

/**
 * Fetches the snapshots of a pushed match's roster before those players connect, so their login
 * does not wait on service-permissions.
 *
 * A login takes its player's entry. It uses a prefetched snapshot until the snapshot's
 * `refreshAfter`, waits for a fetch already in flight rather than sending a second request, and
 * runs a fetch that is still queued itself. A failed, stale or invalidated prefetch leaves the
 * login to fetch exactly as it would without one.
 *
 * [executor] bounds how many prefetches run at once, so a full roster does not hit the service with
 * one request per player at the same moment.
 */
internal class PermissionSnapshotPrefetches(
    private val fetch: (UUID) -> PermissionSnapshot,
    private val executor: Executor,
    private val onlinePlayerIds: () -> Set<UUID>,
    private val clock: Clock,
    private val logger: Logger,
) : PlayerPrefetch {
    private val entries = ConcurrentHashMap<UUID, Entry>()

    override fun prefetch(players: Collection<UUID>, reason: String) {
        try {
            start(players, reason)
        } catch (exception: RuntimeException) {
            logger.warn(
                "Permission snapshot prefetch failed (reason={}, players={}, exceptionType={})",
                reason,
                players.size,
                exception::class.java.name,
            )
        }
    }

    private fun start(players: Collection<UUID>, reason: String) {
        evictUnusable()
        val online = onlinePlayerIds()
        val started = mutableListOf<Entry>()
        for (playerId in players.distinct()) {
            if (playerId in online) continue
            val entry = Entry(playerId)
            if (entries.putIfAbsent(playerId, entry) != null) continue
            started += entry
            entry.future.whenComplete { _, error ->
                if (error != null) entries.remove(playerId, entry)
            }
            executor.execute(entry::run)
        }
        if (started.isEmpty()) return
        CompletableFuture.allOf(*started.map { it.future }.toTypedArray()).whenComplete { _, _ ->
            val failed = started.count { it.future.isCompletedExceptionally }
            if (failed == 0) {
                logger.info(
                    "Permission snapshots prefetched successfully (reason={}, requested={}, prefetched={})",
                    reason,
                    players.size,
                    started.size,
                )
            } else {
                logger.warn(
                    "Permission snapshot prefetch partly failed (reason={}, requested={}, prefetched={}, failed={})",
                    reason,
                    players.size,
                    started.size - failed,
                    failed,
                )
            }
        }
    }

    /** The snapshot for [playerId]'s login. Throws whatever the client throws. */
    fun fetchForLogin(playerId: UUID): LoginSnapshot {
        val entry =
            entries.remove(playerId) ?: return LoginSnapshot(fetch(playerId), PrefetchOutcome.NONE)
        if (entry.claim()) {
            // Still queued behind other prefetches: fetch now instead of waiting for a free slot.
            return LoginSnapshot(entry.complete(), PrefetchOutcome.MISS)
        }
        val prefetched = runCatching { entry.future.join() }.getOrNull()
        if (prefetched != null && prefetched.refreshAfter.isAfter(clock.instant())) {
            return LoginSnapshot(prefetched, PrefetchOutcome.HIT)
        }
        return LoginSnapshot(fetch(playerId), PrefetchOutcome.MISS)
    }

    /** Called for every snapshot invalidation, so a login never uses a snapshot from before it. */
    fun invalidate(playerId: UUID) {
        entries.remove(playerId)
    }

    fun clear() {
        entries.clear()
    }

    /** Drops snapshots prefetched for players who never logged in once they are stale. */
    private fun evictUnusable() {
        val now = clock.instant()
        entries.entries.removeIf { (_, entry) ->
            val future = entry.future
            future.isDone &&
                !future.isCompletedExceptionally &&
                !future.join().refreshAfter.isAfter(now)
        }
    }

    private inner class Entry(private val playerId: UUID) {
        private val claimed = AtomicBoolean()
        val future = CompletableFuture<PermissionSnapshot>()

        fun claim(): Boolean = claimed.compareAndSet(false, true)

        fun run() {
            if (claim()) runCatching { complete() }
        }

        fun complete(): PermissionSnapshot =
            try {
                fetch(playerId).also { future.complete(it) }
            } catch (throwable: Throwable) {
                // Anything that escapes would leave a login waiting on this future forever.
                future.completeExceptionally(throwable)
                throw throwable
            }
    }
}
