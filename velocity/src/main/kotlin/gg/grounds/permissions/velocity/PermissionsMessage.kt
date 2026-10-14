package gg.grounds.permissions.velocity

import gg.grounds.i18n.SystemCategory
import gg.grounds.i18n.SystemMessageKey

enum class PermissionsMessage(override val id: String, override val category: SystemCategory) :
    SystemMessageKey {
    COMMAND_OUTPUT("command.output", SystemCategory.INFO),
    COMMAND_FAILURE("command.failure", SystemCategory.ERROR),
    HELP_STATUS("help.status", SystemCategory.INFO),
    HELP_USER_INFO("help.user-info", SystemCategory.INFO),
    HELP_PERMISSION_CHECK("help.permission-check", SystemCategory.INFO),
    HELP_USER_REFRESH("help.user-refresh", SystemCategory.INFO),
    HELP_REFRESH("help.refresh", SystemCategory.INFO),
    USAGE_STATUS("usage.status", SystemCategory.WARNING),
    USAGE_USER("usage.user", SystemCategory.WARNING),
    USAGE_INFO("usage.info", SystemCategory.WARNING),
    USAGE_REFRESH("usage.refresh", SystemCategory.WARNING),
    USAGE_PERMISSION_CHECK("usage.permission-check", SystemCategory.WARNING),
    INVALID_SCOPE("error.invalid-scope", SystemCategory.ERROR),
    PLAYER_OFFLINE("error.player-offline", SystemCategory.ERROR),
    NO_SNAPSHOT("error.no-snapshot", SystemCategory.ERROR),
    PERMISSIONS_UNAVAILABLE("error.permissions-unavailable", SystemCategory.ERROR),
}
