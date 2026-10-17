package gg.grounds.permissions.minestom.badge

import gg.grounds.permissions.RoleMetadata
import java.util.Locale

internal object TabRankLabels {
    fun resolve(role: RoleMetadata): String? =
        when (role.key.trim().lowercase(Locale.ROOT)) {
            "admin",
            "administrator" -> "ADMIN"
            "dev",
            "developer" -> "DEV"
            "mod",
            "moderator" -> "MOD"
            "user",
            "player",
            "default" -> "USER"
            "support",
            "supporter" -> "SUP"
            "builder" -> "BUILD"
            else -> role.name.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        }
}
