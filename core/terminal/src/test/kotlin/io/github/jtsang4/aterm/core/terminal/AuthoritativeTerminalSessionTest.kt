package io.github.jtsang4.aterm.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthoritativeTerminalSessionTest {
    @Test
    fun color_palette_defaults_cursor_to_foreground() {
        val palette = TerminalColorPalette(
            foregroundArgb = 0xFF171C26.toInt(),
            backgroundArgb = 0xFFFFFFFF.toInt(),
        )

        assertEquals(palette.foregroundArgb, palette.cursorArgb)
    }

    @Test
    fun color_palette_preserves_explicit_palette_values() {
        val palette = TerminalColorPalette(
            foregroundArgb = 0xFF171C26.toInt(),
            backgroundArgb = 0xFFF7F9FC.toInt(),
            cursorArgb = 0xFF0057D9.toInt(),
        )

        assertEquals(0xFF171C26.toInt(), palette.foregroundArgb)
        assertEquals(0xFFF7F9FC.toInt(), palette.backgroundArgb)
        assertEquals(0xFF0057D9.toInt(), palette.cursorArgb)
    }
}
