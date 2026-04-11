package io.github.jtsang4.aterm.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {
    @Test
    fun scrollback_preserves_earlier_output_and_jump_to_live_restores_latest_view() {
        val buffer = TerminalBuffer(columns = 20, rows = 3, maxScrollbackLines = 32)

        buffer.append("one\ntwo\nthree\nfour\n")

        val liveSnapshot = buffer.snapshot()
        assertEquals(listOf("three", "four", ""), liveSnapshot.visibleLines.map(String::trimEnd))
        assertEquals(listOf("one", "two"), liveSnapshot.scrollbackLines.map(String::trimEnd))

        buffer.scrollPageUp()
        val historySnapshot = buffer.snapshot()
        assertTrue(historySnapshot.visibleText.contains("one"))
        assertFalse(historySnapshot.isAtLiveBottom)

        buffer.jumpToBottom()
        val restoredSnapshot = buffer.snapshot()
        assertTrue(restoredSnapshot.isAtLiveBottom)
        assertTrue(restoredSnapshot.visibleText.contains("four"))
    }

    @Test
    fun full_screen_clear_and_home_redraw_replace_visible_screen_instead_of_appending_log_lines() {
        val buffer = TerminalBuffer(columns = 20, rows = 3, maxScrollbackLines = 32)

        buffer.append("before redraw\n")
        buffer.append("\u001B[2J\u001B[Hfullscreen mode\nrow two")

        val snapshot = buffer.snapshot()
        assertTrue(snapshot.visibleText.contains("fullscreen mode"))
        assertTrue(snapshot.visibleText.contains("row two"))
        assertEquals(listOf("fullscreen mode", "row two", ""), snapshot.visibleLines.map(String::trimEnd))
    }
}
