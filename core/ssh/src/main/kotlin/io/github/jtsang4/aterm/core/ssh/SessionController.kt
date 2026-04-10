package io.github.jtsang4.aterm.core.ssh

import kotlinx.coroutines.flow.StateFlow

interface SessionController {
    fun observeUiState(): StateFlow<SessionUiState>
    fun connect(hostId: Long)
    fun disconnect()
    fun submitHostTrustDecision(accept: Boolean)
    fun sendInput(input: String)
}
