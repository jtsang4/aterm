package io.github.jtsang4.aterm.core.ssh

import io.github.jtsang4.aterm.core.terminal.TerminalController
import io.github.jtsang4.aterm.core.terminal.TerminalUiState
import io.github.jtsang4.aterm.core.terminal.TerminalSpecialKey
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

interface SessionController : TerminalController {
    fun observeUiState(): StateFlow<SessionUiState>
    fun connect(hostId: Long)
    fun disconnect()
    fun submitHostTrustDecision(accept: Boolean)
    fun sendInput(input: String)

    override fun sendText(text: String) = sendInput(text)

    override fun sendSpecialKey(key: TerminalSpecialKey) = sendInput(key.encoded)

    override fun pasteText(text: String) = sendInput(text)

    override fun observeTerminalUiState(): StateFlow<TerminalUiState> = observeUiState().let { flow ->
        object : StateFlow<TerminalUiState> {
            override val replayCache: List<TerminalUiState>
                get() = listOf(flow.value.liveTerminalState)

            override val value: TerminalUiState
                get() = flow.value.liveTerminalState

            override suspend fun collect(collector: FlowCollector<TerminalUiState>): Nothing {
                flow.collect { collector.emit(it.liveTerminalState) }
            }
        }
    }

    override fun scrollPageUp() = Unit

    override fun scrollPageDown() = Unit

    override fun jumpToBottom() = Unit

    override fun resize(columns: Int, rows: Int) = Unit
}
