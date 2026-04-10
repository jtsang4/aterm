package io.github.jtsang4.aterm.core.domain.model

import java.time.Instant

data class Snippet(
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val hostId: Long? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val lastRunAt: Instant? = null,
) {
    init {
        require(title.isNotBlank()) { "Snippet title must not be blank." }
    }

    val hasTargetHost: Boolean = hostId != null
}
