package gg.grounds.permissions

import java.util.Locale
import java.util.ResourceBundle

/** Early login has no negotiated client locale; all platforms share the same source fallback. */
object PermissionLoginMessages {
    fun unavailable(locale: Locale = Locale.ENGLISH): String =
        ResourceBundle.getBundle(
                "gg.grounds.permissions.login_messages",
                locale,
                javaClass.classLoader,
                ResourceBundle.Control.getNoFallbackControl(
                    ResourceBundle.Control.FORMAT_PROPERTIES
                ),
            )
            .getString("login.unavailable")
}
