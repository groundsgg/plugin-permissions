package gg.grounds.permissions.velocity

import java.util.UUID

class PermissionCommandRouter(
    private val service: PermissionCommandService,
    private val findOnlinePlayer: (String) -> OnlinePermissionPlayer?,
    private val onlinePlayers: () -> Collection<OnlinePermissionPlayer>,
    private val defaultScope: PermissionCheckScopeArgument = PermissionCheckScopeArgument.global(),
) {
    fun execute(arguments: Array<String>): PermissionCommandResult {
        if (arguments.isEmpty()) {
            return help()
        }

        return when (arguments[0].lowercase()) {
            "help" -> help()
            "status" -> if (arguments.size == 1) service.status() else usageStatus()
            "user" -> executeUser(arguments)
            "refresh" -> executeRefresh(arguments)
            else -> help()
        }
    }

    fun suggest(arguments: Array<String>): List<String> =
        when {
            arguments.isEmpty() -> ROOT_COMMANDS
            arguments.size == 1 -> filterPrefix(ROOT_COMMANDS, arguments[0])
            arguments[0].equals("user", ignoreCase = true) && arguments.size == 2 ->
                filterPrefix(playerNames(), arguments[1])
            arguments[0].equals("user", ignoreCase = true) && arguments.size == 3 ->
                filterPrefix(USER_COMMANDS, arguments[2])
            arguments[0].equals("user", ignoreCase = true) &&
                arguments.size == 4 &&
                arguments[2].equals("permission", ignoreCase = true) ->
                filterPrefix(PERMISSION_COMMANDS, arguments[3])
            arguments[0].equals("user", ignoreCase = true) &&
                arguments.size == 6 &&
                arguments[2].equals("permission", ignoreCase = true) &&
                arguments[3].equals("check", ignoreCase = true) ->
                filterPrefix(SCOPE_ARGUMENTS, arguments[5])
            arguments[0].equals("refresh", ignoreCase = true) && arguments.size == 2 ->
                filterPrefix(playerNames() + "all", arguments[1])
            else -> emptyList()
        }

    private fun executeUser(arguments: Array<String>): PermissionCommandResult {
        if (arguments.size < 3) {
            return usageUser()
        }

        val player = findOnlinePlayer(arguments[1]) ?: return playerOffline(arguments[1])
        return when (arguments[2].lowercase()) {
            "info" -> if (arguments.size == 3) service.info(player.playerId) else usageInfo()
            "refresh" ->
                if (arguments.size == 3) service.refresh(player.playerId, player.username)
                else usageRefresh()
            "permission" -> executePermissionCheck(player, arguments)
            else -> usageUser()
        }
    }

    private fun executePermissionCheck(
        player: OnlinePermissionPlayer,
        arguments: Array<String>,
    ): PermissionCommandResult {
        if (arguments.size !in 5..6 || !arguments[3].equals("check", ignoreCase = true)) {
            return usagePermissionCheck()
        }

        val scope =
            if (arguments.size == 6) {
                PermissionCheckScopeArgument.parseOrNull(arguments[5])
                    ?: return PermissionCommandResult.Failure(
                        "Invalid permission scope: ${arguments[5]}"
                    )
            } else {
                defaultScope
            }
        return service.check(player.playerId, arguments[4], scope)
    }

    private fun executeRefresh(arguments: Array<String>): PermissionCommandResult {
        if (arguments.size != 2) {
            return usageRefresh()
        }

        if (arguments[1].equals("all", ignoreCase = true)) {
            val results = onlinePlayers().map { service.refresh(it.playerId, it.username) }
            return PermissionCommandResult.Success(
                listOf(
                    "refreshed=${results.count { it is PermissionCommandResult.Success }}",
                    "failed=${results.count { it is PermissionCommandResult.Failure }}",
                )
            )
        }

        val player = findOnlinePlayer(arguments[1]) ?: return playerOffline(arguments[1])
        return service.refresh(player.playerId, player.username)
    }

    private fun playerNames(): List<String> =
        onlinePlayers()
            .map(OnlinePermissionPlayer::username)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun help(): PermissionCommandResult =
        PermissionCommandResult.Success(
            listOf(
                "/perm status",
                "/perm user <player> info",
                "/perm user <player> permission check <node> [scope]",
                "/perm user <player> refresh",
                "/perm refresh <player|all>",
            )
        )

    private fun usageStatus(): PermissionCommandResult.Failure =
        PermissionCommandResult.Failure("Usage: /perm status")

    private fun usageUser(): PermissionCommandResult.Failure =
        PermissionCommandResult.Failure("Usage: /perm user <player> <info|refresh|permission>")

    private fun usageInfo(): PermissionCommandResult.Failure =
        PermissionCommandResult.Failure("Usage: /perm user <player> info")

    private fun usageRefresh(): PermissionCommandResult.Failure =
        PermissionCommandResult.Failure("Usage: /perm refresh <player|all>")

    private fun usagePermissionCheck(): PermissionCommandResult.Failure =
        PermissionCommandResult.Failure(
            "Usage: /perm user <player> permission check <node> [scope]"
        )

    private fun playerOffline(name: String): PermissionCommandResult.Failure =
        PermissionCommandResult.Failure("Player is not online: $name")

    private fun filterPrefix(values: List<String>, prefix: String): List<String> =
        values.filter { it.startsWith(prefix, ignoreCase = true) }

    companion object {
        private val ROOT_COMMANDS = listOf("help", "refresh", "status", "user")
        private val USER_COMMANDS = listOf("info", "permission", "refresh")
        private val PERMISSION_COMMANDS = listOf("check")
        private val SCOPE_ARGUMENTS = listOf("global", "server=", "server-type=")
    }
}

data class OnlinePermissionPlayer(val playerId: UUID, val username: String)
