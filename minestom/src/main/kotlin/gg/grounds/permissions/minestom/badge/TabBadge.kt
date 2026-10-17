package gg.grounds.permissions.minestom.badge

import kotlin.math.max
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

internal object TabBadge {
    fun width(label: String): Int {
        val textWidth = TabLabelAdvances.width(label)
        val pad = 4
        val inner =
            max(textWidth + pad, TabGlyphs.LEFT_PX + TabGlyphs.RIGHT_PX + TabGlyphs.MIDDLE_PX)
        val middles = inner - TabGlyphs.LEFT_PX - TabGlyphs.RIGHT_PX
        return TabGlyphs.LEFT_PX + middles * TabGlyphs.MIDDLE_PX + TabGlyphs.RIGHT_PX
    }

    fun chip(label: String, fill: TextColor): Component {
        val textWidth = TabLabelAdvances.width(label)
        val badgeWidth = width(label)
        val middles = badgeWidth - TabGlyphs.LEFT_PX - TabGlyphs.RIGHT_PX
        val padLeft = (badgeWidth - textWidth) / 2
        val padRight = badgeWidth - textWidth - padLeft
        val gap = TabSpaces.of(-1)
        val slices = buildString {
            append(TabGlyphs.BADGE_LEFT)
            append(gap)
            repeat(middles) {
                append(TabGlyphs.BADGE_MIDDLE)
                append(gap)
            }
            append(TabGlyphs.BADGE_RIGHT)
            append(gap)
        }
        return Component.text()
            .append(Component.text(slices, fill).font(TabGlyphs.FONT))
            .append(Component.text(TabSpaces.of(-badgeWidth)).font(TabGlyphs.FONT))
            .append(Component.text(TabSpaces.of(padLeft)).font(TabGlyphs.FONT))
            .append(
                Component.text(label, NamedTextColor.WHITE)
                    .font(TabGlyphs.LABEL_FONT)
                    .decoration(TextDecoration.BOLD, false)
                    .decoration(TextDecoration.ITALIC, false)
                    .shadowColor(ShadowColor.none())
            )
            .append(Component.text(TabSpaces.of(padRight)).font(TabGlyphs.FONT))
            .build()
    }
}
