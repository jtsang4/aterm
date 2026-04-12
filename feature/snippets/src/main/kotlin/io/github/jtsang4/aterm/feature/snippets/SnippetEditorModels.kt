package io.github.jtsang4.aterm.feature.snippets

import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Snippet

internal data class SnippetEditorDraft(
    val snippetId: Long = 0,
    val title: String = "",
    val description: String = "",
    val tagsText: String = "",
    val body: String = "",
    val hostId: Long? = null,
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
