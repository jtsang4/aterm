package io.github.jtsang4.aterm.core.terminal

import kotlinx.coroutines.flow.StateFlow

data class TerminalUiState(
    val snapshot: TerminalSnapshot = TerminalBuffer().snapshot(),
    val canSendInput: Boolean = false,
    val authoritativeSession: AuthoritativeTerminalSession? = null,
    val cellWidthPx: Int = 9,
    val cellHeightPx: Int = 18,
    val fontScale: Float = 1f,
)

interface TerminalController {
    fun observeTerminalUiState(): StateFlow<TerminalUiState>
    fun setTerminalFontScale(scale: Float)
    fun sendText(text: String)
    fun sendSpecialKey(key: TerminalSpecialKey)
    fun pasteText(text: String)
    fun scrollPageUp()
    fun scrollPageDown()
    fun jumpToBottom()
    fun resize(columns: Int, rows: Int)
    fun resize(viewport: TerminalViewport) = resize(viewport.columns, viewport.rows)
}
