package gg.grounds.permissions.minestom

import gg.grounds.permissions.Permissions
import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.invalidation.ProxyPlayerEditions
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.color.TeamColor
import net.minestom.server.entity.Player
import net.minestom.server.scoreboard.Team
import net.minestom.server.timer.TaskSchedule

/** Native nameplates use the same role metadata as chat and the tab list. */
internal class MinestomRankNameTags(private val permissions: Permissions) : AutoCloseable {
    private data class Entry(val team: Team, var prefix: Component)

    private val entries = mutableMapOf<UUID, Entry>()
    private val editions = ProxyPlayerEditions()
    private val task =
        MinecraftServer.getSchedulerManager()
            .scheduleTask(::refresh, TaskSchedule.nextTick(), TaskSchedule.seconds(1))

    @Synchronized
    private fun refresh() {
        val players = MinecraftServer.getConnectionManager().onlinePlayers.associateBy { it.uuid }
        entries.keys.toList().filterNot(players::containsKey).forEach { remove(it, null) }
        players.values.forEach { player ->
            val existing = entries[player.uuid]
            // Gameplay owns its teams. Never overwrite an explicit game team or its visibility.
            if (player.team != null && player.team !== existing?.team) {
                remove(player.uuid, player)
                return@forEach
            }
            val role =
                permissions
                    .snapshot(player.uuid)
                    ?.roleMetadata
                    ?.minWithOrNull(compareBy<RoleMetadata> { it.sortOrder }.thenBy { it.key })
            if (role == null || role.name.isBlank()) {
                remove(player.uuid, player)
                return@forEach
            }
            val prefix = prefix(role, editions.isBedrock(player.uuid))
            if (existing == null) {
                val team =
                    MinecraftServer.getTeamManager()
                        .createBuilder("grank" + sequence.incrementAndGet().toString(36))
                        .teamColor(TeamColor.WHITE)
                        .allowFriendlyFire()
                        .prefix(prefix)
                        .build()
                entries[player.uuid] = Entry(team, prefix)
                player.team = team
            } else {
                if (existing.prefix != prefix) {
                    existing.team.updatePrefix(prefix)
                    existing.prefix = prefix
                }
                if (player.team == null) player.team = existing.team
            }
        }
    }

    private fun prefix(role: RoleMetadata, bedrock: Boolean): Component {
        val edition =
            if (bedrock)
                Component.text("\uE004", NamedTextColor.WHITE)
                    .font(Key.key("grounds", "tab"))
                    .append(Component.text(" ").font(Key.key("minecraft", "default")))
            else Component.empty()
        return edition
            .append(
                Component.text(role.name, role.color?.let(TextColor::fromHexString) ?: TEXT)
                    .font(Key.key("minecraft", "default"))
            )
            .append(Component.text(" · ", SECONDARY).font(Key.key("minecraft", "default")))
    }

    private fun remove(id: UUID, player: Player?) {
        val entry = entries.remove(id) ?: return
        if (player?.team === entry.team) player.team = null
        MinecraftServer.getTeamManager().deleteTeam(entry.team)
    }

    @Synchronized
    override fun close() {
        task.cancel()
        editions.close()
        val players = MinecraftServer.getConnectionManager().onlinePlayers.associateBy { it.uuid }
        entries.keys.toList().forEach { remove(it, players[it]) }
    }

    private companion object {
        val sequence = AtomicLong()
        val TEXT = TextColor.fromHexString("#F3F0EA")!!
        val SECONDARY = TextColor.fromHexString("#C1B9AA")!!
    }
}
