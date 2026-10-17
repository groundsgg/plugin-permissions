package gg.grounds.permissions.minestom.badge

internal object VanillaAdvances {
    fun width(text: String): Int = text.sumOf { advance(it) }

    private fun advance(ch: Char): Int =
        when (ch) {
            ' ' -> 4
            'I',
            't' -> 4
            'i',
            '!' -> 2
            'l' -> 3
            'f',
            'k' -> 5
            else -> 6
        }
}
