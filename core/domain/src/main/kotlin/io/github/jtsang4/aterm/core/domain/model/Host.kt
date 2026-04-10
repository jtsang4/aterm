package io.github.jtsang4.aterm.core.domain.model

import java.time.Instant

enum class HostAuthKind {
    UNKNOWN,
    PASSWORD,
    KEY,
}

data class Host(
    val id: Long = 0,
    val label: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val identityId: Long?,
    val authKind: HostAuthKind = HostAuthKind.PASSWORD,
    val isFavorite: Boolean = false,
    val lastUsedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
) {
    init {
        require(label.isNotBlank()) { "Host label must not be blank." }
        require(address.isNotBlank()) { "Host address must not be blank." }
        require(port in 1..65_535) { "Host port must be in the valid TCP range." }
        require(username.isNotBlank()) { "Host username must not be blank." }
        require(identityId == null || identityId > 0) { "Hosts must reference a saved identity when linked." }
        require(id != 0L || identityId != null) { "New hosts must reference a saved identity." }
    }

    val endpoint: String = "$address:$port"
    val hasLinkedIdentity: Boolean = identityId != null
}
