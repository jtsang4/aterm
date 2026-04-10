package io.github.jtsang4.aterm.core.domain.model

import java.time.Instant

enum class SessionConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECT_REQUIRED,
    FAILED,
}

data class SessionMetadata(
    val id: Long = 0,
    val hostId: Long,
    val state: SessionConnectionState = SessionConnectionState.DISCONNECTED,
    val title: String? = null,
    val connectedAt: Instant? = null,
    val disconnectedAt: Instant? = null,
    val reconnectRequired: Boolean = false,
    val lastError: String? = null,
) {
    init {
        require(hostId > 0) { "Session metadata must reference a saved host." }
    }

    val isLive: Boolean = state == SessionConnectionState.CONNECTED && !reconnectRequired
}
