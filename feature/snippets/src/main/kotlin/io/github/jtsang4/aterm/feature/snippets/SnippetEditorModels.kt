package io.github.jtsang4.aterm.feature.snippets

import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionRecordInput
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionTargetKind
import io.github.jtsang4.aterm.core.domain.model.SnippetSavedTarget

internal data class SnippetEditorDraft(
    val snippetId: Long = 0,
    val title: String = "",
    val description: String = "",
    val tagsText: String = "",
    val body: String = "",
    val hostId: Long? = null,
    val savedTarget: SnippetSavedTarget = if (hostId != null) {
        SnippetSavedTarget.SAVED_HOST
    } else {
        SnippetSavedTarget.ACTIVE_SESSION
    },
) {
    val isEditing: Boolean = snippetId != 0L

    fun parsedTags(): List<String> = tagsText
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    companion object {
        fun from(
            snippet: Snippet?,
            body: String = "",
        ): SnippetEditorDraft = SnippetEditorDraft(
            snippetId = snippet?.id ?: 0,
            title = snippet?.title.orEmpty(),
            description = snippet?.description.orEmpty(),
            tagsText = snippet?.tags.orEmpty().joinToString(", "),
            body = body,
            hostId = snippet?.hostId,
            savedTarget = snippet?.savedTarget ?: SnippetSavedTarget.ACTIVE_SESSION,
        )
    }
}

internal data class SnippetHostMetadata(
    val label: String,
    val detail: String,
)

internal fun Host.toSnippetMetadata(): SnippetHostMetadata = SnippetHostMetadata(
    label = label,
    detail = "$username@$address:$port",
)

internal fun List<Snippet>.filteredBy(
    query: String,
    hostMetadataById: Map<Long, SnippetHostMetadata>,
): List<Snippet> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        return this
    }

    return filter { snippet ->
        snippet.matchesQuery(normalizedQuery, hostMetadataById[snippet.hostId])
    }
}

private fun Snippet.matchesQuery(
    query: String,
    hostMetadata: SnippetHostMetadata?,
): Boolean = buildList {
    add(title)
    description?.let(::add)
    addAll(tags)
    hostMetadata?.label?.let(::add)
    hostMetadata?.detail?.let(::add)
}.any { candidate ->
    candidate.contains(query, ignoreCase = true)
}

internal enum class SnippetExecutionTargetMode {
    SAVED_HOST,
    ACTIVE_SESSION,
}

internal data class ActiveSessionSnippetTarget(
    val hostId: Long,
    val hostLabel: String,
    val endpoint: String,
    val connectionState: SessionConnectionState,
) {
    val isLive: Boolean = connectionState == SessionConnectionState.CONNECTED
}

internal data class SnippetExecutionTargetSnapshot(
    val mode: SnippetExecutionTargetMode,
    val snippetHostId: Long?,
    val snippetHostMetadata: SnippetHostMetadata?,
    val activeSessionTarget: ActiveSessionSnippetTarget?,
) {
    val summary: String = when (mode) {
        SnippetExecutionTargetMode.SAVED_HOST -> snippetHostMetadata?.let { metadata ->
            "Saved host: ${metadata.label} (${metadata.detail})"
        } ?: "Saved host target unavailable"

        SnippetExecutionTargetMode.ACTIVE_SESSION -> activeSessionTarget?.let { session ->
            "Active session: ${session.hostLabel} (${session.endpoint})"
        } ?: "Active session target unavailable"
    }

    fun invalidReason(): String? = when (mode) {
        SnippetExecutionTargetMode.SAVED_HOST -> when {
            snippetHostId == null -> "This snippet has no saved host target. Choose the current active session or edit the snippet to assign a host."
            snippetHostMetadata == null -> "The saved host target is missing or stale. Repair the snippet target before running it."
            else -> null
        }

        SnippetExecutionTargetMode.ACTIVE_SESSION -> when {
            activeSessionTarget == null -> "No active session is available. Connect first or switch to the saved host target."
            !activeSessionTarget.isLive -> "The current session is no longer live. Reconnect before running this snippet in-session."
            else -> null
        }
    }

    val historyTargetKind: SnippetExecutionTargetKind
        get() = when (mode) {
            SnippetExecutionTargetMode.SAVED_HOST -> SnippetExecutionTargetKind.SAVED_HOST
            SnippetExecutionTargetMode.ACTIVE_SESSION -> SnippetExecutionTargetKind.ACTIVE_SESSION
        }

    val historyTargetLabel: String
        get() = when (mode) {
            SnippetExecutionTargetMode.SAVED_HOST -> snippetHostMetadata?.label ?: "Unknown saved host"
            SnippetExecutionTargetMode.ACTIVE_SESSION -> activeSessionTarget?.hostLabel ?: "Current session"
        }

    val historyTargetDetail: String
        get() = when (mode) {
            SnippetExecutionTargetMode.SAVED_HOST -> snippetHostMetadata?.detail ?: "Unavailable target"
            SnippetExecutionTargetMode.ACTIVE_SESSION -> activeSessionTarget?.endpoint ?: "Unavailable session"
        }
}

internal data class SnippetExecutionDraft(
    val snippet: Snippet,
    val body: String,
    val targetSnapshot: SnippetExecutionTargetSnapshot,
) {
    val bodyPreview: String = body
        .trimEnd()
        .lineSequence()
        .take(4)
        .joinToString("\n")
        .ifBlank { "(empty)" }

    fun toExecutionRecord(): SnippetExecutionRecordInput = SnippetExecutionRecordInput(
        snippetId = snippet.id,
        targetKind = targetSnapshot.historyTargetKind,
        targetLabel = targetSnapshot.historyTargetLabel,
        targetDetail = targetSnapshot.historyTargetDetail,
    )
}
