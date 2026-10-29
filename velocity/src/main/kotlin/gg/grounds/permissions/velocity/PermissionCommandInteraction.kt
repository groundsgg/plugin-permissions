package gg.grounds.permissions.velocity

import gg.grounds.i18n.Translations
import java.util.Locale
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent

/** Renders command guidance as localized actions without exposing raw syntax as the label. */
object PermissionCommandInteraction {
    fun supports(message: PermissionsMessage): Boolean = message in interactions

    fun render(messages: Translations, message: PermissionsMessage, locale: Locale): Component {
        return attach(messages.render(message, locale), message)
    }

    fun render(messages: Translations, message: PermissionsMessage, audience: Audience): Component {
        return attach(messages.render(message, audience), message)
    }

    private fun attach(component: Component, message: PermissionsMessage): Component {
        val interaction =
            requireNotNull(interactions[message]) { "No command interaction for ${message.id}" }
        return component
            .clickEvent(interaction.click)
            .hoverEvent(HoverEvent.showText(Component.text(interaction.syntax)))
    }

    private data class Interaction(val click: ClickEvent, val syntax: String)

    private val interactions =
        mapOf(
            PermissionsMessage.HELP_STATUS to
                Interaction(ClickEvent.runCommand("/perm status"), "/perm status"),
            PermissionsMessage.USAGE_STATUS to
                Interaction(ClickEvent.runCommand("/perm status"), "/perm status"),
            PermissionsMessage.HELP_USER_INFO to
                Interaction(ClickEvent.suggestCommand("/perm user "), "/perm user <player> info"),
            PermissionsMessage.USAGE_INFO to
                Interaction(ClickEvent.suggestCommand("/perm user "), "/perm user <player> info"),
            PermissionsMessage.HELP_PERMISSION_CHECK to
                Interaction(
                    ClickEvent.suggestCommand("/perm user "),
                    "/perm user <player> permission check <node> [scope]",
                ),
            PermissionsMessage.USAGE_PERMISSION_CHECK to
                Interaction(
                    ClickEvent.suggestCommand("/perm user "),
                    "/perm user <player> permission check <node> [scope]",
                ),
            PermissionsMessage.HELP_USER_REFRESH to
                Interaction(
                    ClickEvent.suggestCommand("/perm user "),
                    "/perm user <player> refresh",
                ),
            PermissionsMessage.USAGE_USER to
                Interaction(
                    ClickEvent.suggestCommand("/perm user "),
                    "/perm user <player> <info|refresh|permission>",
                ),
            PermissionsMessage.HELP_REFRESH to
                Interaction(
                    ClickEvent.suggestCommand("/perm refresh "),
                    "/perm refresh <player|all>",
                ),
            PermissionsMessage.USAGE_REFRESH to
                Interaction(
                    ClickEvent.suggestCommand("/perm refresh "),
                    "/perm refresh <player|all>",
                ),
        )
}
