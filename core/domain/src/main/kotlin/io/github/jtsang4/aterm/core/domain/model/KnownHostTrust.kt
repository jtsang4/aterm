package io.github.jtsang4.aterm.core.domain.model

import java.time.Instant

data class KnownHostTrust(
    val id: Long = 0,
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val hostKeyBase64: String,
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(host.isNotBlank()) { "Trusted host endpoint must not be blank." }
        require(port in 1..65_535) { "Trusted host port must be in the valid TCP range." }
        require(algorithm.isNotBlank()) { "Host-key algorithm must not be blank." }
        require(fingerprint.isNotBlank()) { "Host-key fingerprint must not be blank." }
        require(hostKeyBase64.isNotBlank()) { "Host-key payload must not be blank." }
    }

    val endpointKey: String = "$host:$port"
}
