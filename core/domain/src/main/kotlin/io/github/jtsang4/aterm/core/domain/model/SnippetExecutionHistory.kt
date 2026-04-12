package io.github.jtsang4.aterm.core.domain.model

import java.time.Instant

enum class SnippetExecutionTargetKind {
    SAVED_HOST,
    ACTIVE_SESSION,
}

data class SnippetExecutionRecordInput(
    val snippetId: Long,
    val targetKind: SnippetExecutionTargetKind,
    val targetLabel: String,
    val targetDetail: String,
    val executedAt: Instant = Instant.now(),
)

data class SnippetExecutionHistoryEntry(
    val id: Long = 0,
    val snippetId: Long?,
    val snippetTitle: String,
    val targetKind: SnippetExecutionTargetKind,
    val targetLabel: String,
    val targetDetail: String,
    val executedAt: Instant,
) {
    val isSnippetDeleted: Boolean = snippetId == null
}
