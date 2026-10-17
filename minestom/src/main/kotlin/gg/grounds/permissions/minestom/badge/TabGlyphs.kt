package gg.grounds.permissions.minestom.badge

import net.kyori.adventure.key.Key

internal object TabGlyphs {
    val FONT: Key = Key.key("grounds", "tab")
    val LABEL_FONT: Key = Key.key("grounds", "tab_labels")
    const val LOGO = '\uE000'
    const val BADGE_LEFT = '\uE001'
    const val BADGE_MIDDLE = '\uE002'
    const val BADGE_RIGHT = '\uE003'
    const val BEDROCK_ICON = '\uE004'
    const val BEDROCK_ADVANCE = 9
    const val LEFT_PX = 3
    const val MIDDLE_PX = 1
    const val RIGHT_PX = 3
    /** Matches `TabFont` bitmap height and the committed `tab/logo.png`. */
    const val LOGO_HEIGHT = 26
    const val LOGO_TEXTURE_WIDTH = 256
    const val LOGO_TEXTURE_HEIGHT = 52
    /**
     * The client rounds the scaled texture width, then adds the 1px gap every bitmap glyph carries
     * (the same gap [TabBadge] cancels between slices). At height 26, the 256x52 texture is 128px
     * wide and the glyph advances 129px.
     */
    const val LOGO_ADVANCE =
        (LOGO_TEXTURE_WIDTH * LOGO_HEIGHT + LOGO_TEXTURE_HEIGHT / 2) / LOGO_TEXTURE_HEIGHT + 1
    const val LOGO_PAD = 16
    const val HEADER_WIDTH = LOGO_PAD + LOGO_ADVANCE + LOGO_PAD
}
