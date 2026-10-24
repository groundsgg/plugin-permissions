package gg.grounds.permissions.minestom

import gg.grounds.permissions.Permissions
import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.minestom.badge.BedrockRankBadges
import gg.grounds.permissions.minestom.badge.TabBadge
import gg.grounds.permissions.minestom.badge.TabRankLabels
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor

/**
 * The rank badge the tab list and nameplates show, for other modules to place elsewhere.
 *
 * Registered in the module ServiceRegistry; resolve it with `services.get<RankBadges>()`.
 */
interface RankBadges {
    /**
     * The badge for [playerId]'s highest role as [viewerId] should see it: the Java font chip, or
     * the Bedrock atlas glyph for a Bedrock viewer. Null when the player has no role to show.
     */
    fun badge(playerId: UUID, viewerId: UUID): RankBadge?
}

/**
 * [component] is the bare chip without trailing spacing; [name] and [color] are the role's display
 * name and colour for a text fallback.
 */
data class RankBadge(val component: Component, val name: String, val color: TextColor)

internal class PermissionRankBadges(
    private val permissions: Permissions,
    private val isBedrock: (UUID) -> Boolean,
) : RankBadges {
    override fun badge(playerId: UUID, viewerId: UUID): RankBadge? {
        val role =
            permissions
                .snapshot(playerId)
                ?.roleMetadata
                ?.minWithOrNull(compareBy<RoleMetadata> { it.sortOrder }.thenBy { it.key })
                ?.takeIf { it.name.isNotBlank() } ?: return null
        val label = TabRankLabels.resolve(role) ?: return null
        val colour =
            role.color?.let(TextColor::fromHexString) ?: TextColor.fromHexString("#F3F0EA")!!
        return RankBadge(
            component =
                if (isBedrock(viewerId)) BedrockRankBadges.chip(label, colour)
                else TabBadge.chip(label, colour),
            name = role.name,
            color = colour,
        )
    }
}
