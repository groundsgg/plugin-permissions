package gg.grounds.permissions.velocity

class PermissionCommandPermissions private constructor(private val nodes: Map<String, String>) {
    fun forArguments(arguments: Array<String>): String =
        when {
            arguments.firstOrNull().equals("status", ignoreCase = true) -> node("status")
            arguments.firstOrNull().equals("refresh", ignoreCase = true) -> node("refresh")
            arguments.firstOrNull().equals("user", ignoreCase = true) &&
                arguments.getOrNull(2).equals("info", ignoreCase = true) -> node("user.info")
            arguments.firstOrNull().equals("user", ignoreCase = true) &&
                arguments.getOrNull(2).equals("refresh", ignoreCase = true) -> node("user.refresh")
            arguments.firstOrNull().equals("user", ignoreCase = true) &&
                arguments.getOrNull(2).equals("permission", ignoreCase = true) &&
                arguments.getOrNull(3).equals("check", ignoreCase = true) -> node("user.check")
            else -> node("root")
        }

    private fun node(key: String): String =
        requireNotNull(nodes[key]) { "Missing command permission: $key" }

    companion object {
        private val REQUIRED_KEYS =
            setOf("root", "status", "user.info", "user.check", "user.refresh", "refresh")

        fun fromManifest(manifest: PermissionManifest): PermissionCommandPermissions {
            val nodes =
                manifest.permissions.map(PermissionManifestEntry::key).associateBy { key ->
                    when (key) {
                        "grounds.permissions.command" -> "root"
                        "grounds.permissions.command.status" -> "status"
                        "grounds.permissions.command.user.info" -> "user.info"
                        "grounds.permissions.command.user.check" -> "user.check"
                        "grounds.permissions.command.user.refresh" -> "user.refresh"
                        "grounds.permissions.command.refresh" -> "refresh"
                        else -> key
                    }
                }
            val missing = REQUIRED_KEYS - nodes.keys
            require(missing.isEmpty()) {
                "Missing required command permissions: ${missing.sorted().joinToString(",")}"
            }
            return PermissionCommandPermissions(nodes)
        }
    }
}
