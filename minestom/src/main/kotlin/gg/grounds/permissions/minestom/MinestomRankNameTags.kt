package gg.grounds.permissions.minestom

import gg.grounds.permissions.Permissions
import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.invalidation.ProxyPlayerEditions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.color.TeamColor
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.network.packet.server.play.TeamsPacket
import net.minestom.server.scoreboard.Team
import net.minestom.server.timer.TaskSchedule

/** Native nameplates use the same role metadata as chat and the tab list. */
internal class MinestomRankNameTags(private val permissions: Permissions) : AutoCloseable {
    private data class Entry(val team: Team, var prefix: Component)

    private val bedrockPrefixes = ConcurrentHashMap<String, Pair<Component, Component>>()
    private val entries = mutableMapOf<UUID, Entry>()
    private val editions = ProxyPlayerEditions()
    private val viewerEditions = mutableMapOf<UUID, Boolean>()
    private val node = EventNode.all("grounds-rank-nameplates")

    init {
        node.addListener(PlayerPacketOutEvent::class.java) { event ->
            val packet = event.packet as? TeamsPacket ?: return@addListener
            if (!packet.teamName().startsWith("grank") || !editions.isBedrock(event.player.uuid))
                return@addListener
            val prefix = bedrockPrefixes[packet.teamName()] ?: return@addListener
            val mapped =
                packet.copyWithOperator { component ->
                    if (component == prefix.first) prefix.second else component
                }
            if (mapped != packet) {
                event.isCancelled = true
                event.player.sendPacket(mapped)
            }
        }
        MinecraftServer.getGlobalEventHandler().addChild(node)
    }

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
            val bedrock = editions.isBedrock(player.uuid)
            val prefix = RankNameTagPrefix.format(role, bedrock)
            val bedrockPrefix = RankNameTagPrefix.format(role, bedrock, bedrockViewer = true)
            if (existing == null) {
                val teamName = "grank" + sequence.incrementAndGet().toString(36)
                bedrockPrefixes[teamName] = prefix to bedrockPrefix
                val team =
                    MinecraftServer.getTeamManager()
                        .createBuilder(teamName)
                        .teamColor(TeamColor.WHITE)
                        .allowFriendlyFire()
                        .prefix(prefix)
                        .build()
                entries[player.uuid] = Entry(team, prefix)
                player.team = team
            } else {
                bedrockPrefixes[existing.team.teamName] = prefix to bedrockPrefix
                if (existing.prefix != prefix) {
                    existing.team.updatePrefix(prefix)
                    existing.prefix = prefix
                }
                if (player.team == null) player.team = existing.team
            }
        }
        viewerEditions.keys.retainAll(players.keys)
        players.values.forEach { viewer ->
            val bedrock = editions.isBedrock(viewer.uuid)
            if (viewerEditions.put(viewer.uuid, bedrock) != bedrock) {
                entries.values.forEach { entry ->
                    val creation =
                        entry.team.createTeamsCreationPacket().action()
                            as TeamsPacket.CreateTeamAction
                    viewer.sendPacket(
                        TeamsPacket(
                            entry.team.teamName,
                            TeamsPacket.UpdateTeamAction(creation.settings()),
                        )
                    )
                }
            }
        }
    }

    private fun remove(id: UUID, player: Player?) {
        val entry = entries.remove(id) ?: return
        bedrockPrefixes.remove(entry.team.teamName)
        if (player?.team === entry.team) player.team = null
        MinecraftServer.getTeamManager().deleteTeam(entry.team)
    }

    @Synchronized
    override fun close() {
        task.cancel()
        MinecraftServer.getGlobalEventHandler().removeChild(node)
        editions.close()
        val players = MinecraftServer.getConnectionManager().onlinePlayers.associateBy { it.uuid }
        entries.keys.toList().forEach { remove(it, players[it]) }
    }

    private companion object {
        val sequence = AtomicLong()
    }
}
