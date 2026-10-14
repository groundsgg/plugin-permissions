package gg.grounds.permissions.velocity

import gg.grounds.i18n.BundleCheck
import gg.grounds.i18n.SystemCategory
import gg.grounds.i18n.SystemLevel
import gg.grounds.i18n.Translations
import java.util.Locale
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PermissionsMessagesTest {
    private val messages =
        Translations.forBundle("gg.grounds.permissions.messages", javaClass.classLoader)
    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `command output remains literal`() {
        val rendered =
            messages.render(
                PermissionsMessage.COMMAND_FAILURE,
                Locale.GERMAN,
                "message" to "<red>Player is not online: Ada</red>",
            )

        assertEquals("<red>Player is not online: Ada</red>", plain.serialize(rendered))
        assertEquals(SystemCategory.GENERAL, PermissionsMessage.COMMAND_FAILURE.category)
        assertEquals(SystemLevel.ERROR, PermissionsMessage.COMMAND_FAILURE.level)
    }

    @Test
    fun `fixed command failures are localized and arguments remain literal`() {
        val rendered =
            messages.render(
                PermissionsMessage.PLAYER_OFFLINE,
                Locale.GERMAN,
                "player" to "<red>Ada</red>",
            )

        assertEquals("Spieler ist nicht online: <red>Ada</red>", plain.serialize(rendered))
    }

    @Test
    fun `English and German bundles cover every permissions message`() {
        assertEquals(
            emptyList<String>(),
            BundleCheck.problems(
                "gg.grounds.permissions.messages",
                javaClass.classLoader,
                PermissionsMessage.entries,
                translations = listOf(Locale.GERMAN),
            ),
        )
    }
}
