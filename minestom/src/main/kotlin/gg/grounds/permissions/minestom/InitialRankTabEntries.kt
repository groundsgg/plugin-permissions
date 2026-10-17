package gg.grounds.permissions.minestom

import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.minestom.badge.TabBadge
import gg.grounds.permissions.minestom.badge.TabGlyphs
import gg.grounds.permissions.minestom.badge.TabRankLabels
import gg.grounds.permissions.minestom.badge.TabSpaces
import gg.grounds.permissions.minestom.badge.VanillaAdvances
import java.util.EnumSet
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket

/** Format the ADD_PLAYER packet before its first write, using snapshots loaded before login. */
internal object InitialRankTabEntries {
    fun format(
        packet: PlayerInfoUpdatePacket,
        roleOf: (UUID) -> RoleMetadata?,
        isBedrock: (UUID) -> Boolean,
        bedrockViewer: Boolean,
    ): PlayerInfoUpdatePacket {
        if (PlayerInfoUpdatePacket.Action.ADD_PLAYER !in packet.actions()) return packet
        var ranked = false
        val entries =
            packet.entries().map { entry ->
                val role = roleOf(entry.uuid()) ?: return@map entry
                val name = entry.username()?.takeIf { it.isNotBlank() } ?: return@map entry
                ranked = true
                val bedrock = isBedrock(entry.uuid())
                val colour =
                    role.color?.let(TextColor::fromHexString)
                        ?: TextColor.fromHexString("#F3F0EA")!!
                val row =
                    Component.text()
                        .append(RankNameTagPrefix.format(role, bedrock, bedrockViewer))
                        .append(Component.text(name, colour))
                if (!bedrockViewer) {
                    val label = TabRankLabels.resolve(role)
                    val width =
                        VanillaAdvances.width(name) +
                            (label?.let { TabBadge.width(it) + 2 } ?: 0) +
                            (if (bedrock) TabGlyphs.BEDROCK_ADVANCE + 2 else 0)
                    val pad = TabGlyphs.HEADER_WIDTH - width
                    if (pad > 0) row.append(Component.text(TabSpaces.of(pad)).font(TabGlyphs.FONT))
                }
                val displayName = row.build()
                if (entry.displayName() == displayName) entry
                else
                    PlayerInfoUpdatePacket.Entry(
                        entry.uuid(),
                        name,
                        entry.properties(),
                        entry.listed(),
                        entry.latency(),
                        entry.gameMode(),
                        displayName,
                        entry.chatSession(),
                        entry.listOrder(),
                        entry.displayHat(),
                    )
            }
        if (!ranked) return packet
        val actions =
            EnumSet.copyOf(packet.actions()).apply {
                add(PlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)
            }
        return if (actions == packet.actions() && entries == packet.entries()) packet
        else PlayerInfoUpdatePacket(actions, entries)
    }
}
