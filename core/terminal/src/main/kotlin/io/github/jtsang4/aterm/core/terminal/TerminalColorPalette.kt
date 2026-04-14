package io.github.jtsang4.aterm.core.terminal

data class TerminalColorPalette(
    val foregroundArgb: Int,
    val backgroundArgb: Int,
    val cursorArgb: Int = foregroundArgb,
) {
    companion object {
        val Default = TerminalColorPalette(
            foregroundArgb = 0xFFD7E3F4.toInt(),
            backgroundArgb = 0xFF101418.toInt(),
            cursorArgb = 0xFFD7E3F4.toInt(),
        )
    }
}
