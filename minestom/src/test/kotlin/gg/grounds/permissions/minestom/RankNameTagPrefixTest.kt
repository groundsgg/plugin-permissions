package gg.grounds.permissions.minestom

import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.minestom.badge.TabBadge
import gg.grounds.permissions.minestom.badge.TabGlyphs
import gg.grounds.permissions.minestom.badge.TabSpaces
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RankNameTagPrefixTest {
    @Test
    fun `bedrock viewers get their full colour atlas badge without Java font glyphs`() {
        assertEquals(
            Component.text()
                .append(
                    Component.text('\uE600', NamedTextColor.WHITE)
                        .font(net.kyori.adventure.key.Key.key("minecraft", "default"))
                        .shadowColor(net.kyori.adventure.text.format.ShadowColor.none())
                )
                .append(
                    Component.text(" ")
                        .font(net.kyori.adventure.key.Key.key("minecraft", "default"))
                )
                .append(
                    Component.text('\uE500', NamedTextColor.WHITE)
                        .font(net.kyori.adventure.key.Key.key("minecraft", "default"))
                )
                .append(
                    Component.text(" ")
                        .font(net.kyori.adventure.key.Key.key("minecraft", "default"))
                )
                .build(),
            RankNameTagPrefix.format(
                RoleMetadata("administrator", "Administrator", color = "#C9826B"),
                true,
                bedrockViewer = true,
            ),
        )
    }

    @Test
    fun `administrator uses the tab badge and its spacing instead of the role name`() {
        val colour = TextColor.fromHexString("#C9826B")!!
        assertEquals(
            Component.text()
                .append(TabBadge.chip("ADMIN", colour))
                .append(Component.text(TabSpaces.of(2)).font(TabGlyphs.FONT))
                .build(),
            RankNameTagPrefix.format(
                RoleMetadata("administrator", "Administrator", color = "#C9826B"),
                false,
            ),
        )
    }

    @Test
    fun `bedrock logo follows the badge in the same order as the tab list`() {
        val colour = TextColor.fromHexString("#A3B18A")!!
        assertEquals(
            Component.text()
                .append(TabBadge.chip("DEV", colour))
                .append(Component.text(TabSpaces.of(2)).font(TabGlyphs.FONT))
                .append(
                    Component.text(TabGlyphs.BEDROCK_ICON, NamedTextColor.WHITE)
                        .font(TabGlyphs.FONT)
                )
                .append(Component.text(TabSpaces.of(2)).font(TabGlyphs.FONT))
                .build(),
            RankNameTagPrefix.format(
                RoleMetadata("developer", "Developer", color = "#A3B18A"),
                true,
            ),
        )
    }
}
