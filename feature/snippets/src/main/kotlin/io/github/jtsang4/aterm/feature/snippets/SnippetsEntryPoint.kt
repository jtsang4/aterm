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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionHistoryEntry
import io.github.jtsang4.aterm.core.domain.model.SnippetSavedTarget
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.SnippetRepository
import io.github.jtsang4.aterm.core.ssh.SessionController
import io.github.jtsang4.aterm.core.ssh.SessionDispatchResult
import io.github.jtsang4.aterm.core.ssh.SessionUiState
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    data class ExecutionBlocked(
        val draft: SnippetExecutionDraft,
    ) : SnippetsDestination

    data class ExecuteConfirmation(
        val draft: SnippetExecutionDraft,
    ) : SnippetsDestination
}

private data class SnippetExecutionNotice(
    val message: String,
    val isError: Boolean,
)

@Composable
fun SnippetsScreen(
    snippetRepository: SnippetRepository,
    hostRepository: HostRepository,
    sessionController: SessionController? = null,
) {
    val snippets by snippetRepository.observeSnippets().collectAsState(initial = emptyList())
    val history by snippetRepository.observeExecutionHistory().collectAsState(initial = emptyList())
    val hosts by hostRepository.observeHosts().collectAsState(initial = emptyList())
    val sessionState by (
        sessionController?.observeUiState()?.collectAsState()
            ?: remember { mutableStateOf(SessionUiState()) }
        )
    var destination by remember { mutableStateOf<SnippetsDestination>(SnippetsDestination.Library) }
    var executionNotice by remember { mutableStateOf<SnippetExecutionNotice?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val hostMetadataById = remember(hosts) { hosts.associate { it.id to it.toSnippetMetadata() } }

    fun currentSnippet(snippetId: Long): Snippet? = snippets.firstOrNull { it.id == snippetId }

    when (val currentDestination = destination) {
        SnippetsDestination.Library -> SnippetsLibraryScreen(
            snippets = snippets,
            history = history,
            hosts = hosts,
            sessionState = sessionState,
            notice = executionNotice,
            onDismissNotice = { executionNotice = null },
            onCreateSnippet = {
                executionNotice = null
                destination = SnippetsDestination.Editor(SnippetEditorDraft())
            },
            onEditSnippet = { snippet ->
                executionNotice = null
                coroutineScope.launch {
                    destination = SnippetsDestination.Editor(
                        SnippetEditorDraft.from(
                            snippet = snippet,
                            body = snippetRepository.getBody(snippet.id).orEmpty(),
                        ),
                    )
                }
            },
            onRunSnippet = { snippet, targetMode ->
                executionNotice = null
                coroutineScope.launch {
                    val latestSnippet = currentSnippet(snippet.id) ?: snippet
                    val body = snippetRepository.getBody(snippet.id).orEmpty()
                    val draft = SnippetExecutionDraft(
                        snippet = latestSnippet,
                        body = body,
                        targetSnapshot = buildTargetSnapshot(
                            snippet = latestSnippet,
                            targetMode = targetMode,
                            hostMetadataById = hostMetadataById,
                            sessionState = sessionState,
                        ),
                    )
                    destination = if (draft.targetSnapshot.invalidReason() != null) {
                        SnippetsDestination.ExecutionBlocked(draft)
                    } else {
                        SnippetsDestination.ExecuteConfirmation(draft)
                    }
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

        is SnippetsDestination.ExecutionBlocked -> {
            val latestSnippet = currentSnippet(currentDestination.draft.snippet.id) ?: currentDestination.draft.snippet
            val latestTargetSnapshot = buildTargetSnapshot(
                snippet = latestSnippet,
                targetMode = currentDestination.draft.targetSnapshot.mode,
                hostMetadataById = hostMetadataById,
                sessionState = sessionState,
            )
            val latestDraft = currentDestination.draft.copy(
                snippet = latestSnippet,
                targetSnapshot = latestTargetSnapshot,
            )
            SnippetExecutionBlockedScreen(
                draft = latestDraft,
                onBack = { destination = SnippetsDestination.Library },
                onEditSnippet = {
                    coroutineScope.launch {
                        destination = SnippetsDestination.Editor(
                            SnippetEditorDraft.from(
                                snippet = latestSnippet,
                                body = snippetRepository.getBody(latestSnippet.id).orEmpty(),
                            ),
                        )
                    }
                },
            )
        }

        is SnippetsDestination.ExecuteConfirmation -> {
            val latestSnippet = currentSnippet(currentDestination.draft.snippet.id) ?: currentDestination.draft.snippet
            val latestTargetSnapshot = buildTargetSnapshot(
                snippet = latestSnippet,
                targetMode = currentDestination.draft.targetSnapshot.mode,
                hostMetadataById = hostMetadataById,
                sessionState = sessionState,
            )
            val latestDraft = currentDestination.draft.copy(
                snippet = latestSnippet,
                targetSnapshot = latestTargetSnapshot,
            )
            SnippetExecutionConfirmationScreen(
                draft = latestDraft,
                sessionState = sessionState,
                sessionController = sessionController,
                snippetRepository = snippetRepository,
                onCancel = { destination = SnippetsDestination.Library },
                onEditSnippet = {
                    coroutineScope.launch {
                        destination = SnippetsDestination.Editor(
                            SnippetEditorDraft.from(
                                snippet = latestSnippet,
                                body = snippetRepository.getBody(latestSnippet.id).orEmpty(),
                            ),
                        )
                    }
                },
                onSuccess = { message ->
                    executionNotice = SnippetExecutionNotice(message = message, isError = false)
                    destination = SnippetsDestination.Library
                },
            )
        }
    }
}

@Composable
private fun SnippetsLibraryScreen(
    snippets: List<Snippet>,
    history: List<SnippetExecutionHistoryEntry>,
    hosts: List<Host>,
    sessionState: SessionUiState,
    notice: SnippetExecutionNotice?,
    onDismissNotice: () -> Unit,
    onCreateSnippet: () -> Unit,
    onEditSnippet: (Snippet) -> Unit,
    onRunSnippet: (Snippet, SnippetExecutionTargetMode) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val targetModes = remember { mutableStateMapOf<Long, SnippetExecutionTargetMode>() }
    val hostMetadataById = remember(hosts) { hosts.associate { it.id to it.toSnippetMetadata() } }
    val filteredSnippets = remember(snippets, query, hostMetadataById) {
        snippets.filteredBy(query = query, hostMetadataById = hostMetadataById)
    }

    AppScreenScaffold(
        title = "Snippets",
        supportingText = "Save local command snippets with optional host metadata, then run them only against an explicit saved host or the current live session.",
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

            notice?.let { currentNotice ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("snippet_execution_notice"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (currentNotice.isError) "Snippet run failed" else "Snippet run sent",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (currentNotice.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        Text(currentNotice.message)
                        TextButton(
                            onClick = onDismissNotice,
                            modifier = Modifier.testTag("snippet_execution_notice_dismiss"),
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
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

            if (history.isNotEmpty()) {
                RecentSnippetHistoryCard(history = history)
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
                            val selectedTargetMode = targetModes[snippet.id]
                                ?: defaultExecutionTargetMode(snippet)
                            val targetSnapshot = buildTargetSnapshot(
                                snippet = snippet,
                                targetMode = selectedTargetMode,
                                hostMetadataById = hostMetadataById,
                                sessionState = sessionState,
                            )
                            SnippetRow(
                                snippet = snippet,
                                hostMetadata = snippet.hostId?.let(hostMetadataById::get),
                                targetSnapshot = targetSnapshot,
                                onSelectTargetMode = { mode ->
                                    targetModes[snippet.id] = mode
                                },
                                onEditSnippet = onEditSnippet,
                                onRunSnippet = { onRunSnippet(snippet, selectedTargetMode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSnippetHistoryCard(
    history: List<SnippetExecutionHistoryEntry>,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("snippet_recent_history"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Recent successful runs",
                style = MaterialTheme.typography.titleMedium,
            )
            history.take(5).forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("snippet_history_entry_$index"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = entry.snippetTitle,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.testTag("snippet_history_title_$index"),
                        )
                        Text(
                            text = entry.historySummary(),
                            modifier = Modifier.testTag("snippet_history_target_$index"),
                        )
                        Text(
                            text = if (entry.isSnippetDeleted) {
                                "Snippet deleted; this history entry is retained for context only."
                            } else {
                                "Snippet still available."
                            },
                            modifier = Modifier.testTag("snippet_history_status_$index"),
                        )
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
    targetSnapshot: SnippetExecutionTargetSnapshot,
    onSelectTargetMode: (SnippetExecutionTargetMode) -> Unit,
    onEditSnippet: (Snippet) -> Unit,
    onRunSnippet: () -> Unit,
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

            Text(
                text = "Execution target",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSelectTargetMode(SnippetExecutionTargetMode.SAVED_HOST) },
                    modifier = Modifier.testTag("snippet_target_saved_host_${snippet.id}"),
                ) {
                    Text("Saved host")
                }
                OutlinedButton(
                    onClick = { onSelectTargetMode(SnippetExecutionTargetMode.ACTIVE_SESSION) },
                    modifier = Modifier.testTag("snippet_target_active_session_${snippet.id}"),
                ) {
                    Text("Active session")
                }
            }
            Text(
                text = targetSnapshot.summary,
                modifier = Modifier.testTag("snippet_run_target_${snippet.id}"),
            )
            targetSnapshot.invalidReason()?.let { reason ->
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("snippet_target_warning_${snippet.id}"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunSnippet,
                    modifier = Modifier.testTag("snippet_run_${snippet.id}"),
                ) {
                    Text("Run")
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
}

@Composable
private fun SnippetExecutionBlockedScreen(
    draft: SnippetExecutionDraft,
    onBack: () -> Unit,
    onEditSnippet: () -> Unit,
) {
    AppScreenScaffold(
        title = "Snippet target blocked",
        supportingText = "Snippet execution is blocked until the target is explicit and valid.",
        modifier = Modifier.testTag("snippet_execution_blocked"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = draft.snippet.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("snippet_execution_blocked_title"),
            )
            Text(
                text = draft.targetSnapshot.summary,
                modifier = Modifier.testTag("snippet_execution_blocked_target"),
            )
            Text(
                text = requireNotNull(draft.targetSnapshot.invalidReason()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("snippet_execution_block_reason"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.targetSnapshot.mode == SnippetExecutionTargetMode.SAVED_HOST) {
                    Button(
                        onClick = onEditSnippet,
                        modifier = Modifier.testTag("snippet_execution_repair"),
                    ) {
                        Text("Repair target")
                    }
                }
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("snippet_execution_block_back"),
                ) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun SnippetExecutionConfirmationScreen(
    draft: SnippetExecutionDraft,
    sessionState: SessionUiState,
    sessionController: SessionController?,
    snippetRepository: SnippetRepository,
    onCancel: () -> Unit,
    onEditSnippet: () -> Unit,
    onSuccess: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var isDispatching by remember(draft.snippet.id, draft.targetSnapshot.mode) { mutableStateOf(false) }
    var executionError by remember(draft.snippet.id, draft.targetSnapshot.mode) { mutableStateOf<String?>(null) }
    val invalidReason = draft.targetSnapshot.invalidReason()
    val confirmEnabled = !isDispatching && invalidReason == null && sessionController != null

    AppScreenScaffold(
        title = "Confirm snippet run",
        supportingText = "Verify the target and visible command body before dispatching anything.",
        modifier = Modifier.testTag("snippet_execution_confirmation"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = draft.snippet.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("snippet_execution_title"),
            )
            Text(
                text = draft.targetSnapshot.summary,
                modifier = Modifier.testTag("snippet_execution_target"),
            )
            Text(
                text = draft.bodyPreview,
                modifier = Modifier.testTag("snippet_execution_body_preview"),
            )
            if (invalidReason != null) {
                Text(
                    text = invalidReason,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("snippet_execution_error"),
                )
            }
            if (sessionController == null) {
                Text(
                    text = "Snippet execution is unavailable in this surface because no session controller is attached.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("snippet_execution_error"),
                )
            }
            executionError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("snippet_execution_error"),
                )
            }
            if (isDispatching) {
                Text(
                    text = "Dispatching snippet…",
                    modifier = Modifier.testTag("snippet_execution_progress"),
                )
            }
            sessionState.pendingTrustDecision?.let { decision ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("snippet_execution_trust_prompt"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Trust host key for ${decision.hostLabel}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Endpoint: ${decision.endpoint}",
                            modifier = Modifier.testTag("snippet_execution_trust_endpoint"),
                        )
                        Text(
                            text = "Fingerprint: ${decision.fingerprint}",
                            modifier = Modifier.testTag("snippet_execution_trust_fingerprint"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { sessionController?.submitHostTrustDecision(false) },
                                enabled = isDispatching && sessionController != null,
                                modifier = Modifier.testTag("snippet_execution_trust_reject"),
                            ) {
                                Text("Reject")
                            }
                            Button(
                                onClick = { sessionController?.submitHostTrustDecision(true) },
                                enabled = isDispatching && sessionController != null,
                                modifier = Modifier.testTag("snippet_execution_trust_accept"),
                            ) {
                                Text("Trust and continue")
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!confirmEnabled) {
                            return@Button
                        }
                        executionError = null
                        isDispatching = true
                        coroutineScope.launch {
                            when (
                                val result = executeSnippet(
                                    draft = draft,
                                    sessionController = requireNotNull(sessionController),
                                    snippetRepository = snippetRepository,
                                )
                            ) {
                                is SnippetExecutionResult.Success -> {
                                    onSuccess(result.message)
                                }

                                is SnippetExecutionResult.Failure -> {
                                    executionError = result.message
                                    isDispatching = false
                                }
                            }
                        }
                    },
                    enabled = confirmEnabled,
                    modifier = Modifier.testTag("snippet_execution_confirm"),
                ) {
                    Text("Confirm run")
                }
                TextButton(
                    onClick = onCancel,
                    enabled = !isDispatching,
                    modifier = Modifier.testTag("snippet_execution_cancel"),
                ) {
                    Text("Cancel")
                }
                if (draft.targetSnapshot.mode == SnippetExecutionTargetMode.SAVED_HOST) {
                    TextButton(
                        onClick = onEditSnippet,
                        enabled = !isDispatching,
                        modifier = Modifier.testTag("snippet_execution_edit_target"),
                    ) {
                        Text("Edit target")
                    }
                }
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
    var savedTarget by remember(initialDraft) {
        mutableStateOf(
            if (initialDraft.hostId != null) {
                SnippetSavedTarget.SAVED_HOST
            } else {
                initialDraft.savedTarget
            },
        )
    }
    var titleError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var bodyError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var targetError by remember(initialDraft) { mutableStateOf<String?>(null) }
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
                text = "Default execution target",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        savedTarget = SnippetSavedTarget.ACTIVE_SESSION
                        targetError = null
                    },
                    modifier = Modifier.testTag("snippet_target_mode_active_session"),
                ) {
                    Text("Current active session")
                }
                OutlinedButton(
                    onClick = {
                        savedTarget = SnippetSavedTarget.SAVED_HOST
                        targetError = null
                    },
                    modifier = Modifier.testTag("snippet_target_mode_saved_host"),
                ) {
                    Text("Saved host")
                }
            }
            Text(
                text = if (savedTarget == SnippetSavedTarget.SAVED_HOST) {
                    "Runs only after confirming the selected saved host target."
                } else {
                    "Runs only in the currently live session after confirmation."
                },
                modifier = Modifier.testTag("snippet_target_mode_summary"),
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
                    detail = "Use the current live session instead of a saved host target.",
                    testTag = "snippet_host_option_none",
                    onClick = {
                        selectedHostId = null
                        targetError = null
                    },
                )
                hosts.forEach { host ->
                    val metadata = host.toSnippetMetadata()
                    SelectableHostOption(
                        selected = selectedHostId == host.id && savedTarget == SnippetSavedTarget.SAVED_HOST,
                        title = metadata.label,
                        detail = metadata.detail,
                        testTag = "snippet_host_option_${host.id}",
                        onClick = {
                            selectedHostId = host.id
                            savedTarget = SnippetSavedTarget.SAVED_HOST
                            targetError = null
                        },
                    )
                }
            }
            targetError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("snippet_target_error"),
                )
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
                        targetError = if (savedTarget == SnippetSavedTarget.SAVED_HOST && selectedHostId == null) {
                            "Choose a saved host target or switch this snippet back to active-session execution."
                        } else {
                            null
                        }
                        if (titleError != null || bodyError != null || targetError != null) {
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
                                        hostId = if (savedTarget == SnippetSavedTarget.SAVED_HOST) selectedHostId else null,
                                        savedTarget = savedTarget,
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
                                            savedTarget = savedTarget,
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

private fun defaultExecutionTargetMode(snippet: Snippet): SnippetExecutionTargetMode =
    if (snippet.savedTarget == SnippetSavedTarget.SAVED_HOST) {
        SnippetExecutionTargetMode.SAVED_HOST
    } else {
        SnippetExecutionTargetMode.ACTIVE_SESSION
    }

private fun buildTargetSnapshot(
    snippet: Snippet,
    targetMode: SnippetExecutionTargetMode,
    hostMetadataById: Map<Long, SnippetHostMetadata>,
    sessionState: SessionUiState,
): SnippetExecutionTargetSnapshot = SnippetExecutionTargetSnapshot(
    mode = targetMode,
    snippetHostId = snippet.hostId,
    snippetHostMetadata = snippet.hostId?.let(hostMetadataById::get),
    activeSessionTarget = sessionState.activeHostId?.let { activeHostId ->
        ActiveSessionSnippetTarget(
            hostId = activeHostId,
            hostLabel = sessionState.activeHostLabel ?: sessionState.endpoint ?: "Current session",
            endpoint = sessionState.endpoint ?: "Unknown endpoint",
            connectionState = sessionState.connectionState,
        )
    },
)

private sealed interface SnippetExecutionResult {
    data class Success(
        val message: String,
    ) : SnippetExecutionResult

    data class Failure(
        val message: String,
    ) : SnippetExecutionResult
}

private suspend fun executeSnippet(
    draft: SnippetExecutionDraft,
    sessionController: SessionController,
    snippetRepository: SnippetRepository,
): SnippetExecutionResult {
    val invalidReason = draft.targetSnapshot.invalidReason()
    if (invalidReason != null) {
        return SnippetExecutionResult.Failure(invalidReason)
    }

    return when (draft.targetSnapshot.mode) {
        SnippetExecutionTargetMode.ACTIVE_SESSION -> {
            dispatchIntoCurrentSession(
                draft = draft,
                sessionController = sessionController,
                snippetRepository = snippetRepository,
            )
        }

        SnippetExecutionTargetMode.SAVED_HOST -> {
            val targetHostId = draft.snippet.hostId
                ?: return SnippetExecutionResult.Failure(
                    "This snippet no longer has a saved host target. Edit it before running.",
                )
            val ready = ensureConnectedToTargetHost(
                sessionController = sessionController,
                targetHostId = targetHostId,
            )
            when (ready) {
                is SnippetExecutionResult.Failure -> ready
                is SnippetExecutionResult.Success -> {
                    dispatchIntoCurrentSession(
                        draft = draft,
                        sessionController = sessionController,
                        snippetRepository = snippetRepository,
                    )
                }
            }
        }
    }
}

private suspend fun ensureConnectedToTargetHost(
    sessionController: SessionController,
    targetHostId: Long,
): SnippetExecutionResult {
    val currentState = sessionController.observeUiState().value
    if (currentState.activeHostId == targetHostId && currentState.isTerminalLive) {
        return SnippetExecutionResult.Success("Ready.")
    }

    sessionController.connect(targetHostId)

    val finalState = withTimeoutOrNull<SessionUiState>(30_000L) {
        while (true) {
            val state = sessionController.observeUiState().value
            when {
                state.activeHostId == targetHostId && state.isTerminalLive -> {
                    return@withTimeoutOrNull state
                }

                state.activeHostId == targetHostId &&
                    state.connectionState in setOf(
                        SessionConnectionState.FAILED,
                        SessionConnectionState.RECONNECT_REQUIRED,
                        SessionConnectionState.DISCONNECTED,
                    ) &&
                    !state.isConnecting -> {
                    return@withTimeoutOrNull state
                }
            }
            delay(100)
        }
        error("Unreachable")
    } ?: return SnippetExecutionResult.Failure(
        "Timed out while preparing the saved host target.",
    )

    return if (finalState.isTerminalLive) {
        SnippetExecutionResult.Success("Ready.")
    } else {
        SnippetExecutionResult.Failure(
            finalState.statusMessage ?: finalState.disconnectReason ?: "Unable to prepare the saved host target.",
        )
    }
}

private suspend fun dispatchIntoCurrentSession(
    draft: SnippetExecutionDraft,
    sessionController: SessionController,
    snippetRepository: SnippetRepository,
): SnippetExecutionResult {
    val transcriptBeforeDispatch = sessionController.observeUiState().value.transcript
    val payload = draft.body.ensureTrailingNewline()
    return when (val dispatchResult = sessionController.dispatchToActiveSession(payload)) {
        is SessionDispatchResult.Failure -> SnippetExecutionResult.Failure(dispatchResult.message)
        SessionDispatchResult.Success -> {
            val transcriptConfirmed = withTimeoutOrNull<Boolean>(5_000L) {
                while (true) {
                    val state = sessionController.observeUiState().value
                    if (!state.isTerminalLive) {
                        return@withTimeoutOrNull false
                    }
                    if (transcriptShowsPayloadProof(
                            transcriptBeforeDispatch = transcriptBeforeDispatch,
                            transcriptAfterDispatch = state.transcript,
                            payload = payload,
                        )
                    ) {
                        return@withTimeoutOrNull true
                    }
                    delay(100)
                }
                error("Unreachable")
            } ?: false

            if (!transcriptConfirmed) {
                SnippetExecutionResult.Failure(
                    "Snippet dispatch could not be confirmed in the live transcript, so no successful run was recorded.",
                )
            } else {
                snippetRepository.markExecuted(
                    draft.snippet.id,
                    draft.toExecutionRecord(),
                )
                SnippetExecutionResult.Success(
                    "Sent “${draft.snippet.title}” to ${draft.targetSnapshot.summary.lowercase()}.",
                )
            }
        }
    }
}

private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"

private fun transcriptShowsPayloadProof(
    transcriptBeforeDispatch: String,
    transcriptAfterDispatch: String,
    payload: String,
): Boolean {
    val expectedPayloadLineSets = payload.payloadProofLineSets()
    if (expectedPayloadLineSets.isEmpty()) {
        return false
    }
    val transcriptDelta = if (transcriptAfterDispatch.startsWith(transcriptBeforeDispatch)) {
        transcriptAfterDispatch.removePrefix(transcriptBeforeDispatch)
    } else {
        transcriptAfterDispatch
    }
    val transcriptLines = sanitizedTranscriptLines(transcriptDelta)
    return expectedPayloadLineSets.any { payloadLines ->
        containsOrderedPayloadProofLines(
            transcriptLines = transcriptLines,
            payloadLines = payloadLines,
        )
    }
}

private fun String.payloadProofLineSets(): List<List<String>> {
    val commandLines = lineSequence()
        .map(String::trimEnd)
        .filter { it.isNotBlank() }
        .toList()
    if (commandLines.isEmpty()) {
        return emptyList()
    }

    val proofLineSets = mutableListOf(commandLines)
    commandLines.printfOutputProofLinesOrNull()
        ?.takeIf(List<String>::isNotEmpty)
        ?.let(proofLineSets::add)
    return proofLineSets
}

private fun List<String>.printfOutputProofLinesOrNull(): List<String>? {
    val outputLines = mutableListOf<String>()
    for (line in this) {
        val parsedLines = parsePrintfOutputProofLines(line) ?: return null
        outputLines += parsedLines
    }
    return outputLines
}

private fun parsePrintfOutputProofLines(commandLine: String): List<String>? {
    val match = Regex("""^printf\s+'((?:\\.|[^'])*)';?$""").matchEntire(commandLine.trim())
        ?: return null
    return decodeSingleQuotedPrintfLiteral(match.groupValues[1])
        .lineSequence()
        .map(String::trimEnd)
        .filter { it.isNotBlank() }
        .toList()
}

private fun decodeSingleQuotedPrintfLiteral(value: String): String {
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index]
        if (current == '\\' && index + 1 < value.length) {
            when (val escaped = value[index + 1]) {
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                '\\' -> builder.append('\\')
                '\'' -> builder.append('\'')
                else -> {
                    builder.append('\\')
                    builder.append(escaped)
                }
            }
            index += 2
        } else {
            builder.append(current)
            index += 1
        }
    }
    return builder.toString()
}

private fun sanitizedTranscriptLines(text: String): List<String> = text.lineSequence()
    .map(::sanitizeTerminalLine)
    .filter { it.isNotBlank() }
    .toList()

private fun sanitizeTerminalLine(text: String): String =
    text.replace(Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]"""), "").trimEnd()

private fun containsOrderedPayloadProofLines(
    transcriptLines: List<String>,
    payloadLines: List<String>,
): Boolean {
    var nextPayloadLine = 0
    for (line in transcriptLines) {
        if (nextPayloadLine < payloadLines.size && line.endsWith(payloadLines[nextPayloadLine])) {
            nextPayloadLine += 1
        }
    }
    return nextPayloadLine == payloadLines.size
}

private fun SnippetExecutionHistoryEntry.historySummary(): String =
    buildString {
        append(
            when (targetKind) {
                io.github.jtsang4.aterm.core.domain.model.SnippetExecutionTargetKind.SAVED_HOST -> "Saved host"
                io.github.jtsang4.aterm.core.domain.model.SnippetExecutionTargetKind.ACTIVE_SESSION -> "Active session"
            },
        )
        append(": ")
        append(targetLabel)
        append(" (")
        append(targetDetail)
        append(")")
        append(" • ")
        append(executedAt)
    }

