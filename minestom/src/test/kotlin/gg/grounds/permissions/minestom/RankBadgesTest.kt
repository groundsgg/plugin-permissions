package gg.grounds.permissions.minestom

import gg.grounds.permissions.PermissionSnapshot
import gg.grounds.permissions.RoleMetadata
import gg.grounds.permissions.SnapshotPermissions
import gg.grounds.permissions.minestom.badge.BedrockRankBadges
import gg.grounds.permissions.minestom.badge.TabBadge
import java.time.Instant
import java.util.UUID
import net.kyori.adventure.text.format.TextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RankBadgesTest {
    private val player = UUID.randomUUID()
    private val javaViewer = UUID.randomUUID()
    private val bedrockViewer = UUID.randomUUID()

    private fun badges(vararg roles: RoleMetadata) =
        PermissionRankBadges(
            SnapshotPermissions(mapOf(player to snapshot(roles.toList()))),
            isBedrock = { it == bedrockViewer },
        )

    @Test
    fun `java viewers get the tab badge of the highest role with its name and colour`() {
        val colour = TextColor.fromHexString("#C9826B")!!
        assertEquals(
            RankBadge(TabBadge.chip("ADMIN", colour), "Administrator", colour),
            badges(
                    RoleMetadata("developer", "Developer", color = "#A3B18A", sortOrder = 2),
                    RoleMetadata("administrator", "Administrator", color = "#C9826B", sortOrder = 1),
                )
                .badge(player, javaViewer),
        )
    }

    @Test
    fun `bedrock viewers get the atlas badge`() {
        val colour = TextColor.fromHexString("#C9826B")!!
        assertEquals(
            RankBadge(BedrockRankBadges.chip("ADMIN", colour), "Administrator", colour),
            badges(RoleMetadata("administrator", "Administrator", color = "#C9826B"))
                .badge(player, bedrockViewer),
        )
    }

    @Test
    fun `roles without a colour fall back to the default chip colour`() {
        assertEquals(
            TextColor.fromHexString("#F3F0EA"),
            badges(RoleMetadata("builder", "Builder")).badge(player, javaViewer)?.color,
        )
    }

    @Test
    fun `players without a role or a displayable name have no badge`() {
        assertNull(badges().badge(UUID.randomUUID(), javaViewer))
        assertNull(badges().badge(player, javaViewer))
        assertNull(badges(RoleMetadata("custom", " ")).badge(player, javaViewer))
    }

    private fun snapshot(roles: List<RoleMetadata>): PermissionSnapshot {
        val now = Instant.now()
        return PermissionSnapshot(
            player,
            1,
            now.minusSeconds(30),
            now.plusSeconds(300),
            now.plusSeconds(3600),
            emptyList(),
            emptyList(),
            roles.map { it.key }.toSet(),
            roles,
        )
    }
}
