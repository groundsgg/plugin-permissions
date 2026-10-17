package gg.grounds.permissions.minestom.badge

internal object TabLabelAdvances {
    fun width(text: String): Int = text.sumOf(::advance)

    private fun advance(ch: Char): Int =
        when {
            ch in 'A'..'Z' && ch != 'I' -> 6
            ch in '0'..'9' -> 6
            ch == 'I' -> 4
            ch == ' ' || ch == '-' -> 4
            ch == '!' || ch == '.' || ch == ':' -> 2
            ch == '+' || ch == '_' || ch == '?' || ch == '/' -> 6
            else -> VanillaAdvances.width(ch.toString())
        }
}
