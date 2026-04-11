package io.github.jtsang4.aterm.core.ssh

import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.terminal.TerminalBuffer
import io.github.jtsang4.aterm.core.terminal.TerminalUiState

data class PendingTrustDecision(
    val hostId: Long,
    val hostLabel: String,
    val address: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val hostKeyBase64: String,
) {
    val endpoint: String = "$address:$port"
}

data class SessionUiState(
    val activeHostId: Long? = null,
    val activeHostLabel: String? = null,
    val endpoint: String? = null,
    val connectionState: SessionConnectionState = SessionConnectionState.DISCONNECTED,
    val statusMessage: String? = null,
    val transcript: String = "",
    val pendingTrustDecision: PendingTrustDecision? = null,
    val lastError: String? = null,
    val liveTerminalState: TerminalUiState = TerminalUiState(snapshot = TerminalBuffer().snapshot()),
    val reconnectRequired: Boolean = false,
    val disconnectReason: String? = null,
) {
    val isConnecting: Boolean = connectionState == SessionConnectionState.CONNECTING
    val isConnected: Boolean = connectionState == SessionConnectionState.CONNECTED
    val canSendInput: Boolean = isConnected
    val hasLiveSession: Boolean = isConnected && !reconnectRequired
    val isTerminalLive: Boolean = hasLiveSession && liveTerminalState.canSendInput
}
