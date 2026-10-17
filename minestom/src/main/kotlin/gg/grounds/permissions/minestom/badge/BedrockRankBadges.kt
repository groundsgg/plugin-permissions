package gg.grounds.permissions.minestom.badge

import java.util.Properties
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor

/** Full-colour Bedrock glyphs built from the exported central role metadata, not a role palette. */
internal object BedrockRankBadges {
    private val catalog =
        Properties().apply {
            checkNotNull(
                    BedrockRankBadges::class
                        .java
                        .getResourceAsStream("/gg/grounds/display/bedrock-rank-badges.properties")
                )
                .use { load(it) }
        }

    fun chip(label: String, colour: TextColor): Component {
        val glyph = catalog.getProperty(label + "." + colour.asHexString().lowercase())
        // New or edited roles stay readable until their newly generated pack is deployed.
        return if (glyph == null)
            Component.text("[$label]", colour).font(Key.key("minecraft", "default"))
        else
            Component.text(glyph.toInt(16).toChar(), NamedTextColor.WHITE)
                .font(Key.key("minecraft", "default"))
                .shadowColor(ShadowColor.none())
    }
}
