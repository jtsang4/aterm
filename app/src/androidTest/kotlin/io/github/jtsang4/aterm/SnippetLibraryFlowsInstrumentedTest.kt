package io.github.jtsang4.aterm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.SecretStorageState
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.SnippetRepository
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.snippets.SnippetsScreen
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnippetLibraryFlowsInstrumentedTest {
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
    fun empty_state_create_action_and_validation_errors_are_visible() {
        composeRule.setContent {
            SnippetsScreen(
                snippetRepository = FakeSnippetRepository(),
                hostRepository = SnippetFakeHostRepository(),
            )
        }

        composeRule.onNodeWithTag("screen_snippets").assertIsDisplayed()
        composeRule.onAllNodesWithTag("snippet_empty_state").assertCountEquals(1)
        composeRule.onNodeWithTag("snippet_create_action").assertIsDisplayed()

        composeRule.onNodeWithTag("snippet_create_action").performClick()
        composeRule.onNodeWithTag("snippet_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("snippet_title_error").assertTextContains("Snippet title is required.")
        composeRule.onNodeWithTag("snippet_body_error").assertTextContains("Command body is required.")
    }

    @Test
    fun snippet_can_be_saved_and_rediscovered_after_relaunch() {
        val firstContainer = AppContainer.create(context)
        var appContainer by mutableStateOf(firstContainer)
        val hostId = runBlocking { seedHost(firstContainer, label = "Production", address = "10.0.2.2", username = "root") }

        composeRule.setContent {
            AtermApp(appContainer = appContainer)
        }

        navigateToSnippets()
        composeRule.onNodeWithTag("snippet_create_action").performClick()
        composeRule.onNodeWithTag("snippet_title_field").performTextInput("Restart API")
        composeRule.onNodeWithTag("snippet_description_field").performTextInput("Restarts the API and tails logs.")
        composeRule.onNodeWithTag("snippet_tags_field").performTextInput("ops, restart")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("snippet_host_option_$hostId").performScrollTo().performClick()
        composeRule.onNodeWithTag("snippet_body_field").performTextInput("sudo systemctl restart api\njournalctl -u api -n 20")
        composeRule.onNodeWithTag("snippet_editor_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { firstContainer.foundationGraph.snippetRepository.getSnippet(1) } != null
        }

        composeRule.onNodeWithTag("snippet_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_tags_1").assertTextContains("ops, restart", substring = true)
        composeRule.onNodeWithTag("snippet_host_1")
            .assertTextContains("Production (root@10.0.2.2:22)", substring = true)

        val relaunchedContainer = AppContainer.create(context)
        composeRule.runOnIdle {
            appContainer = relaunchedContainer
        }

        navigateToSnippets()
        composeRule.onNodeWithTag("snippet_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_title_1").assertTextContains("Restart API", substring = true)
        composeRule.onNodeWithTag("snippet_host_1")
            .assertTextContains("Production (root@10.0.2.2:22)", substring = true)
        assertEquals(
            "sudo systemctl restart api\njournalctl -u api -n 20",
            runBlocking { relaunchedContainer.foundationGraph.snippetRepository.getBody(1) },
        )
    }

    @Test
    fun whitespace_only_required_fields_are_blocked_and_multiline_body_reopens_exactly() {
        val container = AppContainer.create(context)
        composeRule.setContent {
            AtermApp(appContainer = container)
        }

        navigateToSnippets()
        composeRule.onNodeWithTag("snippet_create_action").performClick()
        composeRule.onNodeWithTag("snippet_title_field").performTextInput("   ")
        composeRule.onNodeWithTag("snippet_body_field").performTextInput(" \n ")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("snippet_editor_save").performScrollTo().performClick()

        composeRule.onNodeWithTag("snippet_title_error").assertTextContains("Snippet title is required.")
        composeRule.onNodeWithTag("snippet_body_error").assertTextContains("Command body is required.")
        assertNull(runBlocking { container.foundationGraph.snippetRepository.getSnippet(1) })

        composeRule.onNodeWithTag("snippet_title_field").performTextClearance()
        composeRule.onNodeWithTag("snippet_title_field").performTextInput("Multiline deploy")
        composeRule.onNodeWithTag("snippet_body_field").performTextClearance()
        val multilineBody = "echo first line\n\necho second line\n  echo third line"
        composeRule.onNodeWithTag("snippet_body_field").performTextInput(multilineBody)
        composeRule.onNodeWithTag("snippet_editor_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.foundationGraph.snippetRepository.getSnippet(1) } != null
        }

        composeRule.onNodeWithTag("snippet_edit_1").performScrollTo().performClick()
        composeRule.onNodeWithTag("snippet_body_field").assertTextContains(multilineBody, substring = true)
        assertEquals(multilineBody, runBlocking { container.foundationGraph.snippetRepository.getBody(1) })
    }

    @Test
    fun edit_cancel_delete_and_reopen_do_not_leak_stale_drafts() {
        val snippetRepository = FakeSnippetRepository(
            initialSnippets = listOf(
                Snippet(
                    id = 1,
                    title = "Deploy",
                    description = "Original description",
                    tags = listOf("ops"),
                    hostId = null,
                    createdAt = Instant.parse("2026-04-10T00:00:00Z"),
                    updatedAt = Instant.parse("2026-04-10T00:00:00Z"),
                ),
            ),
            initialBodies = mapOf(1L to "echo original"),
        )

        composeRule.setContent {
            SnippetsScreen(
                snippetRepository = snippetRepository,
                hostRepository = SnippetFakeHostRepository(),
            )
        }

        composeRule.onNodeWithTag("snippet_edit_1").performClick()
        composeRule.onNodeWithTag("snippet_title_field").performTextClearance()
        composeRule.onNodeWithTag("snippet_title_field").performTextInput("Deploy updated")
        composeRule.onNodeWithTag("snippet_body_field").performTextClearance()
        composeRule.onNodeWithTag("snippet_body_field").performTextInput("echo updated draft")
        composeRule.onNodeWithTag("snippet_editor_delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("snippet_delete_confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_delete_cancel").performClick()
        composeRule.onNodeWithTag("snippet_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_title_field").assertTextContains("Deploy updated")
        composeRule.onNodeWithTag("snippet_body_field").assertTextContains("echo updated draft", substring = true)

        composeRule.onNodeWithTag("snippet_editor_cancel").performScrollTo().performClick()
        composeRule.onNodeWithTag("snippet_title_1").assertTextContains("Deploy", substring = true)
        assertEquals("echo original", runBlocking { snippetRepository.getBody(1) })

        composeRule.onNodeWithTag("snippet_create_action").performClick()
        composeRule.onNodeWithTag("snippet_title_field").assertTextContains("", substring = true)
        composeRule.onNodeWithTag("snippet_body_field").assertTextContains("", substring = true)
        composeRule.onNodeWithTag("snippet_editor_cancel").performScrollTo().performClick()

        composeRule.onNodeWithTag("snippet_edit_1").performClick()
        composeRule.onNodeWithTag("snippet_title_field").performTextClearance()
        composeRule.onNodeWithTag("snippet_title_field").performTextInput("Deploy saved")
        composeRule.onNodeWithTag("snippet_body_field").performTextClearance()
        composeRule.onNodeWithTag("snippet_body_field").performTextInput("echo saved")
        composeRule.onNodeWithTag("snippet_editor_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { snippetRepository.getSnippet(1) }?.title == "Deploy saved"
        }
        composeRule.onNodeWithTag("snippet_title_1").assertTextContains("Deploy saved", substring = true)
        assertEquals("echo saved", runBlocking { snippetRepository.getBody(1) })

        composeRule.onNodeWithTag("snippet_edit_1").performClick()
        composeRule.onNodeWithTag("snippet_editor_delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("snippet_delete_confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_delete_confirm").performClick()
        composeRule.onAllNodesWithTag("snippet_row_1").assertCountEquals(0)
        composeRule.onNodeWithTag("snippet_empty_state").assertIsDisplayed()
        assertNull(runBlocking { snippetRepository.getSnippet(1) })
    }

    @Test
    fun search_uses_visible_metadata_and_clears_cleanly() {
        val hostRepository = SnippetFakeHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Production",
                    address = "prod.example",
                    port = 22,
                    username = "root",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
                Host(
                    id = 2,
                    label = "Analytics",
                    address = "metrics.example",
                    port = 2222,
                    username = "reporter",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        val snippetRepository = FakeSnippetRepository(
            initialSnippets = listOf(
                Snippet(id = 1, title = "Restart API", description = "Ops restart flow", tags = listOf("ops", "restart"), hostId = 1),
                Snippet(id = 2, title = "Tail metrics", description = "Analytics check", tags = listOf("metrics"), hostId = 2),
                Snippet(id = 3, title = "List tmp", description = "Filesystem", tags = listOf("files"), hostId = null),
            ),
            initialBodies = mapOf(
                1L to "echo one",
                2L to "echo two",
                3L to "echo three",
            ),
        )

        composeRule.setContent {
            SnippetsScreen(
                snippetRepository = snippetRepository,
                hostRepository = hostRepository,
            )
        }

        composeRule.onNodeWithTag("snippet_tags_1").assertTextContains("ops, restart", substring = true)
        composeRule.onNodeWithTag("snippet_host_1")
            .assertTextContains("Production (root@prod.example:22)", substring = true)
        composeRule.onNodeWithTag("snippet_search_field").assert(hasSetTextAction())

        composeRule.onNodeWithTag("snippet_search_field").performTextInput("metrics")
        composeRule.onNodeWithTag("snippet_row_2").assertIsDisplayed()
        composeRule.onAllNodesWithTag("snippet_row_1").assertCountEquals(0)
        composeRule.onAllNodesWithTag("snippet_row_3").assertCountEquals(0)

        composeRule.onNodeWithTag("snippet_search_clear").performClick()
        composeRule.onNodeWithTag("snippet_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_row_2").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_row_3").assertIsDisplayed()

        composeRule.onNodeWithTag("snippet_search_field").performTextInput("production")
        composeRule.onNodeWithTag("snippet_row_1").assertIsDisplayed()
        composeRule.onAllNodesWithTag("snippet_row_2").assertCountEquals(0)
        composeRule.onAllNodesWithTag("snippet_row_3").assertCountEquals(0)
    }

    private fun navigateToSnippets() {
        composeRule.onNodeWithTag("nav_snippets").performClick()
        composeRule.onNodeWithTag("screen_snippets").assertIsDisplayed()
    }

    private fun seedHost(
        appContainer: AppContainer,
        label: String,
        address: String,
        username: String,
    ): Long {
        val identity = runBlocking {
            appContainer.foundationGraph.identityRepository.upsert(
                identity = snippetReadyIdentity(
                    id = 0,
                    name = "$label password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = "snippet-secret"),
            )
        }
        return runBlocking {
            appContainer.foundationGraph.hostRepository.upsert(
                Host(
                    id = 0,
                    label = label,
                    address = address,
                    port = 22,
                    username = username,
                    identityId = identity.id,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
    }
}

private class FakeSnippetRepository(
    initialSnippets: List<Snippet> = emptyList(),
    initialBodies: Map<Long, String> = emptyMap(),
) : SnippetRepository {
    private val snippets = MutableStateFlow(initialSnippets.sortedBy(Snippet::id))
    private val bodies = linkedMapOf<Long, String>().apply { putAll(initialBodies) }
    private var nextId = ((initialSnippets.maxOfOrNull(Snippet::id) ?: 0L) + 1L)

    override fun observeSnippets(): Flow<List<Snippet>> = snippets

    override suspend fun getSnippet(id: Long): Snippet? = snippets.value.firstOrNull { it.id == id }

    override suspend fun upsert(snippet: Snippet, body: String): Snippet {
        val persistedId = snippet.id.takeIf { it != 0L } ?: nextId++
        val existing = getSnippet(persistedId)
        val persisted = snippet.copy(
            id = persistedId,
            createdAt = existing?.createdAt ?: snippet.createdAt,
            updatedAt = snippet.updatedAt,
            lastRunAt = existing?.lastRunAt ?: snippet.lastRunAt,
        )
        bodies[persistedId] = body
        snippets.value = snippets.value
            .filterNot { it.id == persistedId }
            .plus(persisted)
            .sortedBy(Snippet::id)
        return persisted
    }

    override suspend fun getBody(id: Long): String? = bodies[id]

    override suspend fun markExecuted(id: Long, executedAt: Instant) {
        val existing = getSnippet(id) ?: return
        snippets.value = snippets.value
            .filterNot { it.id == id }
            .plus(existing.copy(lastRunAt = executedAt))
            .sortedBy(Snippet::id)
    }

    override suspend fun deleteSnippet(id: Long) {
        snippets.value = snippets.value.filterNot { it.id == id }
        bodies.remove(id)
    }
}

private class SnippetFakeHostRepository(
    initialHosts: List<Host> = emptyList(),
) : HostRepository {
    private val hosts = MutableStateFlow(initialHosts.sortedBy(Host::id))

    override fun observeHosts(): Flow<List<Host>> = hosts

    override suspend fun getHost(id: Long): Host? = hosts.value.firstOrNull { it.id == id }

    override suspend fun upsert(host: Host): Host {
        val persisted = host.copy(id = host.id.takeIf { it != 0L } ?: ((hosts.value.maxOfOrNull(Host::id) ?: 0L) + 1L))
        hosts.value = hosts.value
            .filterNot { it.id == persisted.id }
            .plus(persisted)
            .sortedBy(Host::id)
        return persisted
    }

    override suspend fun markUsed(id: Long, usedAt: Instant) = Unit

    override suspend fun deleteHost(id: Long) {
        hosts.value = hosts.value.filterNot { it.id == id }
    }
}

private fun snippetReadyIdentity(
    id: Long,
    name: String,
    kind: IdentityKind,
): Identity = Identity(
    id = id,
    name = name,
    kind = kind,
    hasSecret = true,
    hasPassphrase = false,
    secretStorageState = SecretStorageState.AVAILABLE,
    passphraseStorageState = SecretStorageState.MISSING,
)

private fun closeKeyboardIfShown() {
    runCatching { closeSoftKeyboard() }
}
