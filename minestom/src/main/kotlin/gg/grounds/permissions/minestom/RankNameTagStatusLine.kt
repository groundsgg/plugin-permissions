package gg.grounds.permissions.minestom

import gg.grounds.permissions.minestom.badge.TabGlyphs
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.DisplayScoreboardPacket
import net.minestom.server.network.packet.server.play.ResetScorePacket
import net.minestom.server.network.packet.server.play.ScoreboardObjectivePacket
import net.minestom.server.network.packet.server.play.UpdateScorePacket
import net.minestom.server.scoreboard.Sidebar.NumberFormat

/** Native second nameplate row, without extra entities or movement packets. */
internal class RankNameTagStatusLine : AutoCloseable {
    private data class Viewer(
        val player: Player,
        var scores: Map<String, Component> = emptyMap(),
        var active: Boolean = false,
    )

    private val objective = "grstatus" + sequence.incrementAndGet().toString(36)
    private val viewers = mutableMapOf<UUID, Viewer>()
    private val gameObjectives = mutableMapOf<UUID, String>()

    @Synchronized
    fun observe(viewer: Player, packet: ServerPacket): Boolean {
        when (packet) {
            is DisplayScoreboardPacket -> {
                if (packet.position() != 2.toByte()) return true
                // Socket events fire when queued packets drain. A game can claim the slot
                // after refresh enqueues our display: suppress that now-stale activation.
                if (packet.scoreName() == objective && viewer.uuid in gameObjectives) {
                    viewers[viewer.uuid]?.active = false
                    return false
                }
                if (packet.scoreName().isNotEmpty() && packet.scoreName() != objective)
                    gameObjectives[viewer.uuid] = packet.scoreName()
                else gameObjectives.remove(viewer.uuid)
                viewers[viewer.uuid]?.active = packet.scoreName() == objective
            }
            is ScoreboardObjectivePacket -> {
                // BelowNameTag.removeViewer deletes its objective without clearing the slot.
                if (
                    packet.mode() == 1.toByte() &&
                        gameObjectives[viewer.uuid] == packet.objectiveName()
                ) {
                    gameObjectives.remove(viewer.uuid)
                }
            }
            else -> Unit
        }
        return true
    }

    @Synchronized
    fun refresh(players: Collection<Player>, isBedrock: (UUID) -> Boolean) {
        val online = players.mapTo(mutableSetOf()) { it.uuid }
        viewers.keys.retainAll(online)
        gameObjectives.keys.retainAll(online)
        val subjects = players.filter { isBedrock(it.uuid) }
        val javaScores = subjects.associate { it.username to JAVA_ICON }
        val bedrockScores = subjects.associate { it.username to BEDROCK_ICON }
        players.forEach { player ->
            if (subjects.isEmpty()) {
                viewers.remove(player.uuid)?.let { remove(it.player) }
                return@forEach
            }
            if (player.uuid in gameObjectives) return@forEach
            val state =
                viewers.getOrPut(player.uuid) {
                    player.sendPacket(
                        ScoreboardObjectivePacket(
                            objective,
                            0,
                            Component.empty(),
                            ScoreboardObjectivePacket.Type.INTEGER,
                            NumberFormat.blank(),
                        )
                    )
                    Viewer(player)
                }
            val scores = if (isBedrock(player.uuid)) bedrockScores else javaScores
            (state.scores.keys - scores.keys).forEach { name ->
                player.sendPacket(ResetScorePacket(name, objective))
            }
            scores.forEach { (name, content) ->
                if (state.scores[name] != content) {
                    player.sendPacket(
                        UpdateScorePacket(name, objective, 0, null, NumberFormat.fixed(content))
                    )
                }
            }
            state.scores = scores
            // Populate scores before displaying, so Java never briefly shows a zero.
            if (!state.active) {
                state.active = true
                player.sendPacket(DisplayScoreboardPacket(2, objective))
            }
        }
    }

    private fun remove(player: Player) {
        player.sendPacket(
            ScoreboardObjectivePacket(
                objective,
                1,
                Component.empty(),
                ScoreboardObjectivePacket.Type.INTEGER,
                null,
            )
        )
    }

    @Synchronized
    override fun close() {
        viewers.values.forEach { remove(it.player) }
        viewers.clear()
        gameObjectives.clear()
    }

    private companion object {
        val sequence = AtomicLong()
        val JAVA_ICON =
            Component.text(TabGlyphs.BEDROCK_ICON, NamedTextColor.WHITE).font(TabGlyphs.FONT)
        val BEDROCK_ICON =
            Component.text('\uE500', NamedTextColor.WHITE).font(Key.key("minecraft", "default"))
    }
}
