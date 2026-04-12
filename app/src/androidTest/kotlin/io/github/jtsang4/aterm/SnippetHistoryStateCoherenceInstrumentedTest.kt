package io.github.jtsang4.aterm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionHistoryEntry
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionRecordInput
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionTargetKind
import io.github.jtsang4.aterm.core.domain.model.SnippetSavedTarget
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.SnippetRepository
import io.github.jtsang4.aterm.feature.snippets.SnippetsScreen
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnippetHistoryStateCoherenceInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("aterm.db")
        File(context.filesDir.parentFile, "datastore").deleteRecursively()
    }

    @Test
    fun history_survives_relaunch_uses_latest_saved_content_and_deletion_stays_non_broken() {
        val store = SharedSnippetStore()
        val hostRepository = HistorySnippetFakeHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "SSH fixture",
                    address = "10.0.2.2",
                    port = 3122,
                    username = "atermtester",
                    identityId = null,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        val firstRepository = store.newRepository()

        val savedHostSnippetId: Long
        runBlocking {
            val savedHostSnippet = firstRepository.upsert(
                snippet = Snippet(
                    title = "History saved host",
                    hostId = 1,
                    savedTarget = SnippetSavedTarget.SAVED_HOST,
                    createdAt = Instant.parse("2026-04-10T00:00:00Z"),
                    updatedAt = Instant.parse("2026-04-10T00:00:00Z"),
                ),
                body = "printf 'OLD_HISTORY_PROOF\\n'",
            )
            savedHostSnippetId = firstRepository.upsert(
                snippet = savedHostSnippet.copy(updatedAt = Instant.parse("2026-04-10T00:30:00Z")),
                body = "printf 'NEW_HISTORY_PROOF\\n'",
            ).id
            val activeSessionSnippetId = firstRepository.upsert(
                snippet = Snippet(
                    title = "History active session",
                    savedTarget = SnippetSavedTarget.ACTIVE_SESSION,
                    createdAt = Instant.parse("2026-04-10T00:10:00Z"),
                    updatedAt = Instant.parse("2026-04-10T00:10:00Z"),
                ),
                body = "printf 'ACTIVE_HISTORY_PROOF\\n'",
            ).id
            firstRepository.markExecuted(
                id = savedHostSnippetId,
                execution = SnippetExecutionRecordInput(
                    snippetId = savedHostSnippetId,
                    targetKind = SnippetExecutionTargetKind.SAVED_HOST,
                    targetLabel = "SSH fixture",
                    targetDetail = "atermtester@10.0.2.2:3122",
                    executedAt = Instant.parse("2026-04-10T01:00:00Z"),
                ),
            )
            firstRepository.markExecuted(
                id = activeSessionSnippetId,
                execution = SnippetExecutionRecordInput(
                    snippetId = activeSessionSnippetId,
                    targetKind = SnippetExecutionTargetKind.ACTIVE_SESSION,
                    targetLabel = "Fixture live session",
                    targetDetail = "atermtester@10.0.2.2:3122",
                    executedAt = Instant.parse("2026-04-10T02:00:00Z"),
                ),
            )
        }

        var snippetRepository by mutableStateOf<SnippetRepository>(firstRepository)
        composeRule.setContent {
            SnippetsScreen(
                snippetRepository = snippetRepository,
                hostRepository = hostRepository,
            )
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("snippet_history_title_1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("snippet_history_title_0")
            .assertTextContains("History active session", substring = true)
        composeRule.onNodeWithTag("snippet_history_target_0")
            .assertTextContains("Active session: Fixture live session (atermtester@10.0.2.2:3122)", substring = true)
        composeRule.onNodeWithTag("snippet_history_title_1")
            .assertTextContains("History saved host", substring = true)
        composeRule.onNodeWithTag("snippet_history_target_1")
            .assertTextContains("Saved host: SSH fixture (atermtester@10.0.2.2:3122)", substring = true)

        composeRule.onNodeWithTag("snippet_row_$savedHostSnippetId").performScrollTo()
        composeRule.onNodeWithTag("snippet_run_$savedHostSnippetId").performClick()
        composeRule.onNodeWithTag("snippet_execution_body_preview")
            .assertTextContains("NEW_HISTORY_PROOF", substring = true)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: SSH fixture (atermtester@10.0.2.2:3122)", substring = true)
        composeRule.onNodeWithTag("snippet_execution_cancel").performClick()

        val relaunchedRepository = store.newRepository()
        composeRule.runOnIdle {
            snippetRepository = relaunchedRepository
        }

        composeRule.onNodeWithTag("snippet_history_title_0")
            .assertTextContains("History active session", substring = true)
        composeRule.onNodeWithTag("snippet_history_status_0")
            .assertTextContains("Snippet still available", substring = true)
        composeRule.onNodeWithTag("snippet_history_title_1")
            .assertTextContains("History saved host", substring = true)
        composeRule.onNodeWithTag("snippet_history_status_1")
            .assertTextContains("Snippet still available", substring = true)

        runBlocking {
            relaunchedRepository.deleteSnippet(savedHostSnippetId)
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("snippet_row_$savedHostSnippetId").assertCountEquals(0)
        composeRule.onNodeWithTag("snippet_history_title_1")
            .assertTextContains("History saved host", substring = true)
        composeRule.onNodeWithTag("snippet_history_status_1")
            .assertTextContains("Snippet deleted", substring = true)
    }
}

private class SharedSnippetStore {
    private val snippets = MutableStateFlow<List<Snippet>>(emptyList())
    private val history = MutableStateFlow<List<SnippetExecutionHistoryEntry>>(emptyList())
    private val bodies = linkedMapOf<Long, String>()
    private var nextSnippetId = 1L
    private var nextHistoryId = 1L

    fun newRepository(): SnippetRepository = object : SnippetRepository {
        override fun observeSnippets(): Flow<List<Snippet>> = snippets

        override fun observeExecutionHistory(): Flow<List<SnippetExecutionHistoryEntry>> = history

        override suspend fun getSnippet(id: Long): Snippet? = snippets.value.firstOrNull { it.id == id }

        override suspend fun upsert(snippet: Snippet, body: String): Snippet {
            val id = snippet.id.takeIf { it != 0L } ?: nextSnippetId++
            val existing = getSnippet(id)
            val persisted = snippet.copy(
                id = id,
                savedTarget = if (snippet.hostId != null) SnippetSavedTarget.SAVED_HOST else snippet.savedTarget,
                createdAt = existing?.createdAt ?: snippet.createdAt,
            )
            bodies[id] = body
            snippets.value = snippets.value.filterNot { it.id == id }.plus(persisted).sortedBy(Snippet::id)
            return persisted
        }

        override suspend fun getBody(id: Long): String? = bodies[id]

        override suspend fun markExecuted(
            id: Long,
            execution: SnippetExecutionRecordInput,
            executedAt: Instant,
        ) {
            val snippet = getSnippet(id) ?: return
            snippets.value = snippets.value
                .filterNot { it.id == id }
                .plus(snippet.copy(lastRunAt = executedAt))
                .sortedBy(Snippet::id)
            history.value = listOf(
                SnippetExecutionHistoryEntry(
                    id = nextHistoryId++,
                    snippetId = id,
                    snippetTitle = snippet.title,
                    targetKind = execution.targetKind,
                    targetLabel = execution.targetLabel,
                    targetDetail = execution.targetDetail,
                    executedAt = executedAt,
                ),
            ) + history.value
        }

        override suspend fun deleteSnippet(id: Long) {
            snippets.value = snippets.value.filterNot { it.id == id }
            bodies.remove(id)
            history.value = history.value.map { entry ->
                if (entry.snippetId == id) entry.copy(snippetId = null) else entry
            }
        }
    }
}

private class HistorySnippetFakeHostRepository(
    initialHosts: List<Host>,
) : HostRepository {
    private val hosts = MutableStateFlow(initialHosts.sortedBy(Host::id))

    override fun observeHosts(): Flow<List<Host>> = hosts

    override suspend fun getHost(id: Long): Host? = hosts.value.firstOrNull { it.id == id }

    override suspend fun upsert(host: Host): Host {
        val persisted = host.copy(id = host.id.takeIf { it != 0L } ?: ((hosts.value.maxOfOrNull(Host::id) ?: 0L) + 1L))
        hosts.value = hosts.value.filterNot { it.id == persisted.id }.plus(persisted).sortedBy(Host::id)
        return persisted
    }

    override suspend fun markUsed(id: Long, usedAt: Instant) = Unit

    override suspend fun deleteHost(id: Long) {
        hosts.value = hosts.value.filterNot { it.id == id }
    }
}
