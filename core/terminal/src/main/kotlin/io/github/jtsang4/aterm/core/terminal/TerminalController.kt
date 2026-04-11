package io.github.jtsang4.aterm.core.terminal

import kotlinx.coroutines.flow.StateFlow

data class TerminalUiState(
    val snapshot: TerminalSnapshot = TerminalBuffer().snapshot(),
    val canSendInput: Boolean = false,
)

interface TerminalController {
    fun observeTerminalUiState(): StateFlow<TerminalUiState>
    fun sendText(text: String)
    fun sendSpecialKey(key: TerminalSpecialKey)
    fun pasteText(text: String)
    fun scrollPageUp()
    fun scrollPageDown()
    fun jumpToBottom()
    fun resize(columns: Int, rows: Int)
}
