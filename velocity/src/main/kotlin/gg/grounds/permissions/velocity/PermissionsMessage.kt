package gg.grounds.permissions.velocity

import gg.grounds.i18n.SystemCategory
import gg.grounds.i18n.SystemLevel
import gg.grounds.i18n.SystemMessageKey

enum class PermissionsMessage(
    override val id: String,
    override val category: SystemCategory = SystemCategory.GENERAL,
    override val level: SystemLevel = SystemLevel.INFO,
) : SystemMessageKey {
    COMMAND_OUTPUT("command.output"),
    COMMAND_FAILURE("command.failure", level = SystemLevel.ERROR),
    HELP_STATUS("help.status"),
    HELP_USER_INFO("help.user-info"),
    HELP_PERMISSION_CHECK("help.permission-check"),
    HELP_USER_REFRESH("help.user-refresh"),
    HELP_REFRESH("help.refresh"),
    USAGE_STATUS("usage.status", level = SystemLevel.WARNING),
    USAGE_USER("usage.user", level = SystemLevel.WARNING),
    USAGE_INFO("usage.info", level = SystemLevel.WARNING),
    USAGE_REFRESH("usage.refresh", level = SystemLevel.WARNING),
    USAGE_PERMISSION_CHECK("usage.permission-check", level = SystemLevel.WARNING),
    INVALID_SCOPE("error.invalid-scope", level = SystemLevel.ERROR),
    PLAYER_OFFLINE("error.player-offline", level = SystemLevel.ERROR),
    NO_SNAPSHOT("error.no-snapshot", level = SystemLevel.ERROR),
    PERMISSIONS_UNAVAILABLE("error.permissions-unavailable", level = SystemLevel.ERROR),
}
