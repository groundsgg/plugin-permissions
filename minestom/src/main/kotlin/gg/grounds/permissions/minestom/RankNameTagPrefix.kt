package gg.grounds.permissions.minestom

import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.minestom.badge.BedrockRankBadges
import gg.grounds.permissions.minestom.badge.TabBadge
import gg.grounds.permissions.minestom.badge.TabGlyphs
import gg.grounds.permissions.minestom.badge.TabRankLabels
import gg.grounds.permissions.minestom.badge.TabSpaces
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor

/** Uses the tab list's badge glyphs, label metrics and role aliases without its column padding. */
internal object RankNameTagPrefix {
    fun format(role: RoleMetadata, bedrock: Boolean, bedrockViewer: Boolean = false): Component {
        val row = Component.text()
        val colour =
            role.color?.let(TextColor::fromHexString) ?: TextColor.fromHexString("#F3F0EA")!!
        TabRankLabels.resolve(role)?.let { label ->
            row.append(
                if (bedrockViewer) BedrockRankBadges.chip(label, colour)
                else TabBadge.chip(label, colour)
            )
            row.append(gap(bedrockViewer))
        }
        if (bedrock) {
            row.append(
                Component.text(
                        if (bedrockViewer) '\uE500' else TabGlyphs.BEDROCK_ICON,
                        NamedTextColor.WHITE,
                    )
                    .font(if (bedrockViewer) Key.key("minecraft", "default") else TabGlyphs.FONT)
            )
            row.append(gap(bedrockViewer))
        }
        return row.build()
    }

    private fun gap(bedrockViewer: Boolean): Component =
        if (bedrockViewer) Component.text(" ").font(Key.key("minecraft", "default"))
        else Component.text(TabSpaces.of(2)).font(TabGlyphs.FONT)
}
