package gg.grounds.permissions.minestom.badge

/**
 * The tab font's space glyphs: one codepoint per signed power of two, so any pixel offset in
 * `-`[MAX_OFFSET]`..`[MAX_OFFSET] is expressed as a short string of existing glyphs.
 *
 * Starts at `U+E010` so it does not collide with the wordmark (`U+E000`) or the badge slices
 * (`U+E001`–`U+E003`). The ladder itself matches library-gui `Spaces`.
 */
internal object TabSpaces {
    const val PUA_START: Int = 0xE010

    const val PUA_END: Int = 0xF8FF

    private val STEPS = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024)

    const val MAX_OFFSET: Int = 2047

    private fun codepoint(index: Int, negative: Boolean): Int =
        PUA_START + index * 2 + if (negative) 1 else 0

    /**
     * The glyph string that moves the text cursor [px] pixels; negative moves left. Empty for `0`.
     *
     * The steps are powers of two and [px] is bounded, so this is a binary decomposition — each
     * step appears at most once and the result is never longer than [STEPS]`.size` characters.
     */
    fun of(px: Int): String {
        require(px >= -MAX_OFFSET && px <= MAX_OFFSET) {
            "offset $px is outside +-$MAX_OFFSET, which the space ladder cannot express"
        }
        if (px == 0) return ""
        val negative = px < 0
        var remaining = if (negative) -px else px
        val out = StringBuilder()
        for (index in STEPS.indices.reversed()) {
            if (remaining >= STEPS[index]) {
                out.appendCodePoint(codepoint(index, negative))
                remaining -= STEPS[index]
            }
        }
        return out.toString()
    }
}
