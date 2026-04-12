package io.github.jtsang4.aterm.feature.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.SnippetRepository
import java.time.Instant
import kotlinx.coroutines.launch

object SnippetsEntryPoint {
    const val route = "snippets"
}

private sealed interface SnippetsDestination {
    data object Library : SnippetsDestination
    data class Editor(val draft: SnippetEditorDraft) : SnippetsDestination
    data class DeleteConfirmation(
        val snippet: Snippet,
        val returnDraft: SnippetEditorDraft,
    ) : SnippetsDestination
}

@Composable
fun SnippetsScreen(
    snippetRepository: SnippetRepository,
    hostRepository: HostRepository,
) {
    val snippets by snippetRepository.observeSnippets().collectAsState(initial = emptyList())
    val hosts by hostRepository.observeHosts().collectAsState(initial = emptyList())
    var destination by remember { mutableStateOf<SnippetsDestination>(SnippetsDestination.Library) }
    val coroutineScope = rememberCoroutineScope()

    when (val currentDestination = destination) {
        SnippetsDestination.Library -> SnippetsLibraryScreen(
            snippets = snippets,
            hosts = hosts,
            onCreateSnippet = {
                destination = SnippetsDestination.Editor(SnippetEditorDraft())
            },
            onEditSnippet = { snippet ->
                coroutineScope.launch {
                    destination = SnippetsDestination.Editor(
                        SnippetEditorDraft.from(
                            snippet = snippet,
                            body = snippetRepository.getBody(snippet.id).orEmpty(),
                        ),
                    )
                }
            },
        )

        is SnippetsDestination.Editor -> SnippetEditorScreen(
            initialDraft = currentDestination.draft,
            hosts = hosts,
            snippetRepository = snippetRepository,
            onCancel = { destination = SnippetsDestination.Library },
            onSaved = { destination = SnippetsDestination.Library },
            onDeleteRequested = { snippet, draft ->
                destination = SnippetsDestination.DeleteConfirmation(snippet, draft)
            },
        )

        is SnippetsDestination.DeleteConfirmation -> DeleteSnippetScreen(
            snippet = currentDestination.snippet,
            snippetRepository = snippetRepository,
            onCancel = { destination = SnippetsDestination.Editor(currentDestination.returnDraft) },
            onDeleted = { destination = SnippetsDestination.Library },
        )
    }
}

@Composable
private fun SnippetsLibraryScreen(
    snippets: List<Snippet>,
    hosts: List<Host>,
    onCreateSnippet: () -> Unit,
    onEditSnippet: (Snippet) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val hostMetadataById = remember(hosts) { hosts.associate { it.id to it.toSnippetMetadata() } }
    val filteredSnippets = remember(snippets, query, hostMetadataById) {
        snippets.filteredBy(query = query, hostMetadataById = hostMetadataById)
    }

    AppScreenScaffold(
        title = "Snippets",
        supportingText = "Save local command snippets with optional host and tag metadata so they stay easy to rediscover on-device.",
        modifier = Modifier.testTag("screen_snippets"),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = onCreateSnippet,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("snippet_create_action"),
            ) {
                Text("Create snippet")
            }

            if (snippets.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search snippets") },
                    supportingText = { Text("Search by title, description, tags, or associated host.") },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            TextButton(
                                onClick = { query = "" },
                                modifier = Modifier.testTag("snippet_search_clear"),
                            ) {
                                Text("Clear")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("snippet_search_field"),
                )
            }

            when {
                snippets.isEmpty() -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("snippet_empty_state"),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "No snippets saved yet",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("Create your first saved command snippet so you can find and reuse it later from this local library.")
                        }
                    }
                }

                filteredSnippets.isEmpty() -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("snippet_search_empty_state"),
                    ) {
                        Text(
                            text = "No snippets match \"$query\". Clear search to restore the full library.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("snippet_list"),
                    ) {
                        items(filteredSnippets, key = Snippet::id) { snippet ->
                            SnippetRow(
                                snippet = snippet,
                                hostMetadata = snippet.hostId?.let(hostMetadataById::get),
                                onEditSnippet = onEditSnippet,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnippetRow(
    snippet: Snippet,
    hostMetadata: SnippetHostMetadata?,
    onEditSnippet: (Snippet) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("snippet_row_${snippet.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = snippet.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("snippet_title_${snippet.id}"),
            )
            snippet.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    modifier = Modifier.testTag("snippet_description_${snippet.id}"),
                )
            }
            Text(
                text = "Tags: ${snippet.tags.ifEmpty { listOf("None") }.joinToString(", ")}",
                modifier = Modifier.testTag("snippet_tags_${snippet.id}"),
            )
            Text(
                text = hostMetadata?.let { "Associated host: ${it.label} (${it.detail})" } ?: "Associated host: None",
                modifier = Modifier.testTag("snippet_host_${snippet.id}"),
            )
            snippet.lastRunAt?.let { lastRun ->
                Text(
                    text = "Last run: $lastRun",
                    modifier = Modifier.testTag("snippet_last_run_${snippet.id}"),
                )
            }
            TextButton(
                onClick = { onEditSnippet(snippet) },
                modifier = Modifier.testTag("snippet_edit_${snippet.id}"),
            ) {
                Text("Edit")
            }
        }
    }
}

@Composable
private fun SnippetEditorScreen(
    initialDraft: SnippetEditorDraft,
    hosts: List<Host>,
    snippetRepository: SnippetRepository,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    onDeleteRequested: (Snippet, SnippetEditorDraft) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var title by remember(initialDraft) { mutableStateOf(initialDraft.title) }
    var description by remember(initialDraft) { mutableStateOf(initialDraft.description) }
    var tagsText by remember(initialDraft) { mutableStateOf(initialDraft.tagsText) }
    var body by remember(initialDraft) { mutableStateOf(initialDraft.body) }
    var selectedHostId by remember(initialDraft) { mutableStateOf(initialDraft.hostId) }
    var titleError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var bodyError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var saveError by remember(initialDraft) { mutableStateOf<String?>(null) }

    AppScreenScaffold(
        title = if (initialDraft.isEditing) "Edit snippet" else "Create snippet",
        supportingText = "Saved snippets preserve multiline command content and optional organization metadata without leaving the device.",
        modifier = Modifier.testTag("snippet_editor"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    titleError = null
                    saveError = null
                },
                label = { Text("Snippet title") },
                isError = titleError != null,
                supportingText = { Text(titleError ?: "Visible in the snippet library and search results.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("snippet_title_field"),
            )
            titleError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("snippet_title_error"),
                )
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                supportingText = { Text("Optional context to help rediscover the snippet later.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("snippet_description_field"),
            )

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags") },
                supportingText = { Text("Optional comma-separated organization labels.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("snippet_tags_field"),
            )

            Text(
                text = "Associated host",
                style = MaterialTheme.typography.titleMedium,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("snippet_host_selector"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectableHostOption(
                    selected = selectedHostId == null,
                    title = "No associated host",
                    detail = "Keep this snippet unscoped until you choose a target later.",
                    testTag = "snippet_host_option_none",
                    onClick = { selectedHostId = null },
                )
                hosts.forEach { host ->
                    val metadata = host.toSnippetMetadata()
                    SelectableHostOption(
                        selected = selectedHostId == host.id,
                        title = metadata.label,
                        detail = metadata.detail,
                        testTag = "snippet_host_option_${host.id}",
                        onClick = { selectedHostId = host.id },
                    )
                }
            }

            OutlinedTextField(
                value = body,
                onValueChange = {
                    body = it
                    bodyError = null
                    saveError = null
                },
                label = { Text("Command body") },
                minLines = 6,
                isError = bodyError != null,
                supportingText = { Text(bodyError ?: "Multiline content is saved exactly as written.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("snippet_body_field"),
            )
            bodyError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("snippet_body_error"),
                )
            }

            saveError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("snippet_save_error"),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val normalizedTitle = title.trim()
                        val normalizedBody = body.trim()
                        titleError = if (normalizedTitle.isEmpty()) "Snippet title is required." else null
                        bodyError = if (normalizedBody.isEmpty()) "Command body is required." else null
                        if (titleError != null || bodyError != null) {
                            return@Button
                        }

                        coroutineScope.launch {
                            runCatching {
                                snippetRepository.upsert(
                                    snippet = Snippet(
                                        id = initialDraft.snippetId,
                                        title = normalizedTitle,
                                        description = description.trim().takeIf { it.isNotEmpty() },
                                        tags = initialDraft.copy(tagsText = tagsText).parsedTags(),
                                        hostId = selectedHostId,
                                        createdAt = if (initialDraft.isEditing) {
                                            snippetRepository.getSnippet(initialDraft.snippetId)?.createdAt ?: Instant.now()
                                        } else {
                                            Instant.now()
                                        },
                                        updatedAt = Instant.now(),
                                        lastRunAt = if (initialDraft.isEditing) {
                                            snippetRepository.getSnippet(initialDraft.snippetId)?.lastRunAt
                                        } else {
                                            null
                                        },
                                    ),
                                    body = body,
                                )
                            }.onSuccess {
                                onSaved()
                            }.onFailure { error ->
                                saveError = error.message ?: "Unable to save snippet."
                            }
                        }
                    },
                    modifier = Modifier.testTag("snippet_editor_save"),
                ) {
                    Text(if (initialDraft.isEditing) "Save changes" else "Save snippet")
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("snippet_editor_cancel"),
                ) {
                    Text("Cancel")
                }
                if (initialDraft.isEditing) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                snippetRepository.getSnippet(initialDraft.snippetId)?.let { snippet ->
                                    onDeleteRequested(
                                        snippet,
                                        SnippetEditorDraft(
                                            snippetId = initialDraft.snippetId,
                                            title = title,
                                            description = description,
                                            tagsText = tagsText,
                                            body = body,
                                            hostId = selectedHostId,
                                        ),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("snippet_editor_delete"),
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableHostOption(
    selected: Boolean,
    title: String,
    detail: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DeleteSnippetScreen(
    snippet: Snippet,
    snippetRepository: SnippetRepository,
    onCancel: () -> Unit,
    onDeleted: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    AppScreenScaffold(
        title = "Delete snippet",
        supportingText = "Deleting a snippet removes only the saved local snippet record. Confirm before removing it.",
        modifier = Modifier.testTag("snippet_delete_confirmation"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Snippet: ${snippet.title}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Tags: ${snippet.tags.ifEmpty { listOf("None") }.joinToString(", ")}",
                modifier = Modifier.testTag("snippet_delete_detail"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            snippetRepository.deleteSnippet(snippet.id)
                            onDeleted()
                        }
                    },
                    modifier = Modifier.testTag("snippet_delete_confirm"),
                ) {
                    Text("Delete snippet")
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("snippet_delete_cancel"),
                ) {
                    Text("Keep snippet")
                }
            }
        }
    }
}
