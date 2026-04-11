package io.github.jtsang4.aterm.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalViewportTest {
    @Test
    fun calculate_terminal_viewport_uses_real_surface_pixels() {
        val viewport = calculateTerminalViewport(
            contentWidthPx = 217,
            contentHeightPx = 361,
            cellWidthPx = 9,
            cellHeightPx = 18,
        )

        requireNotNull(viewport)
        assertEquals(24, viewport.columns)
        assertEquals(20, viewport.rows)
        assertEquals(217, viewport.widthPx)
        assertEquals(361, viewport.heightPx)
    }

    @Test
    fun calculate_terminal_viewport_returns_null_for_zero_sized_surface() {
        assertNull(
            calculateTerminalViewport(
                contentWidthPx = 0,
                contentHeightPx = 180,
                cellWidthPx = 9,
                cellHeightPx = 18,
            ),
        )
        assertNull(
            calculateTerminalViewport(
                contentWidthPx = 180,
                contentHeightPx = 0,
                cellWidthPx = 9,
                cellHeightPx = 18,
            ),
        )
    }
}
