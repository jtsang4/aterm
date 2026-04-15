package io.github.jtsang4.aterm

import android.content.Context
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.model.SnippetSavedTarget
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.ssh.SessionUiState
import io.github.jtsang4.aterm.core.ssh.SshSessionCoordinator
import io.github.jtsang4.aterm.di.AppContainer
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnippetRealFixtureExecutionInstrumentedTest {
    private lateinit var context: Context
    private lateinit var application: AtermApplication
    private lateinit var device: UiDevice
    private lateinit var deviceOrchestrator: SessionDeviceOrchestrator

    private val resetAppStateRule = object : ExternalResource() {
        override fun before() {
            context = ApplicationProvider.getApplicationContext()
            application = context as AtermApplication
            device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            deviceOrchestrator = SessionDeviceOrchestrator(
                composeRule = composeRule,
                context = context,
                device = device,
            )
            resetTestPersistenceState(context)
        }

        override fun after() {
            if (::deviceOrchestrator.isInitialized) {
                deviceOrchestrator.restoreDeviceState()
            }
            resetTestPersistenceState(context)
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(resetAppStateRule)
        .around(composeRule)

    @Test
    fun canceling_real_fixture_confirmation_dispatches_nothing_and_records_no_success_history() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = seedFixtureIdentity(container, "Cancel identity")
        val hostId = seedFixtureHost(container, identityId = identityId, label = "Cancel fixture host")
        val snippetId = seedSnippet(
            container = container,
            title = "Cancel snippet",
            body = "printf 'CANCEL_SHOULD_NOT_RUN\\n'",
            savedTarget = SnippetSavedTarget.ACTIVE_SESSION,
        )
        val coordinator = container.sshSessionCoordinator

        launchAppWithContainer(container)
        openSessionScreen()
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        waitForProofText(coordinator, FIXTURE_PORT)
        val transcriptBeforeCancel = coordinator.observeUiState().value.transcript

        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$snippetId")
            .assertTextContains("Active session: Cancel fixture host", substring = true)
        waitForSnippetTargetReady(snippetId)
        openExecutionConfirmation(snippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Active session: Cancel fixture host", substring = true)
        composeRule.onNodeWithTag("snippet_execution_body_preview")
            .assertTextContains("CANCEL_SHOULD_NOT_RUN", substring = true)
        clickTaggedNode("snippet_execution_cancel")

        runBlocking { delay(750) }
        composeRule.onNodeWithTag("screen_snippets").assertIsDisplayed()
        composeRule.onAllNodesWithTag("snippet_execution_notice").assertCountEquals(0)
        assertFalse(coordinator.observeUiState().value.transcript.length < transcriptBeforeCancel.length)
        assertFalse(coordinator.observeUiState().value.transcript.contains("CANCEL_SHOULD_NOT_RUN"))
        assertEquals(0, runBlocking { container.foundationGraph.snippetRepository.observeExecutionHistory().first().size })
        assertNull(runBlocking { container.foundationGraph.snippetRepository.getSnippet(snippetId) }?.lastRunAt)
        disconnectIfConnected(coordinator)
    }

    @Test
    fun saved_host_execution_uses_the_selected_fixture_host_and_preserves_multiline_order() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = seedFixtureIdentity(container, "Selected host identity")
        val activeHostId = seedFixtureHost(container, identityId = identityId, label = "Active fixture host")
        val selectedHostId = seedFixtureHost(container, identityId = identityId, label = "Selected fixture host")
        val snippetId = seedSnippet(
            container = container,
            title = "Selected host multiline snippet",
            body = "printf 'SELECTED_HOST_LINE_1\\n'\nprintf 'SELECTED_HOST_LINE_2\\n'\nprintf 'SELECTED_HOST_LINE_3\\n'",
            savedTarget = SnippetSavedTarget.SAVED_HOST,
            hostId = selectedHostId,
        )
        val coordinator = container.sshSessionCoordinator

        launchAppWithContainer(container)
        openSessionScreen()
        connectFromUi(
            coordinator = coordinator,
            hostId = activeHostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        waitForProofText(coordinator, FIXTURE_PORT)

        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$snippetId")
            .assertTextContains("Saved host: Selected fixture host", substring = true)
        openExecutionConfirmation(snippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: Selected fixture host", substring = true)
        composeRule.onNodeWithTag("snippet_execution_body_preview")
            .assertTextContains("SELECTED_HOST_LINE_1", substring = true)
        clickTaggedNode("snippet_execution_confirm")
        acceptSnippetTrustIfVisible()

        waitForSnippetHistorySize(container, 1)
        assertEquals(selectedHostId, coordinator.observeUiState().value.activeHostId)
        waitForTranscriptOutputLinesInOrder(
            coordinator = coordinator,
            expectedLines = listOf(
                "SELECTED_HOST_LINE_1",
                "SELECTED_HOST_LINE_2",
                "SELECTED_HOST_LINE_3",
            ),
        )
        waitForSnippetsScreen()
        waitForSnippetHistorySize(container, 1)
        scrollToHistoryEntry(0)
        composeRule.onNodeWithTag("snippet_history_title_0")
            .assertTextContains("Selected host multiline snippet", substring = true)
        composeRule.onNodeWithTag("snippet_history_target_0")
            .assertTextContains("Saved host: Selected fixture host", substring = true)
        disconnectIfConnected(coordinator)
    }

    @Test
    fun active_session_target_refreshes_with_live_session_changes_reuses_that_session_and_suppresses_duplicate_confirm_runs() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = seedFixtureIdentity(container, "Active session identity")
        val firstHostId = seedFixtureHost(container, identityId = identityId, label = "First active fixture host")
        val snippetId = seedSnippet(
            container = container,
            title = "Reuse active session snippet",
            body = "printf 'ACTIVE_SESSION_REUSE_PROOF\\n'",
            savedTarget = SnippetSavedTarget.ACTIVE_SESSION,
        )
        val coordinator = container.sshSessionCoordinator

        launchAppWithContainer(container)
        openSessionScreen()
        connectFromUi(
            coordinator = coordinator,
            hostId = firstHostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        waitForProofText(coordinator, FIXTURE_PORT)

        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$snippetId")
            .assertTextContains("Active session: First active fixture host", substring = true)
        waitForSnippetTargetReady(snippetId)

        openSessionScreen()
        clickTaggedNode("session_disconnect_button")
        waitForUiDisconnect(coordinator, "Disconnected.")

        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$snippetId")
            .assertTextContains("Active session: First active fixture host", substring = true)
        composeRule.onNodeWithTag("snippet_target_warning_$snippetId")
            .assertTextContains("current session is no longer live", substring = true)

        openSessionScreen()
        connectFromUi(
            coordinator = coordinator,
            hostId = firstHostId,
            expectedButtonLabel = null,
            expectTrustPrompt = false,
        )
        waitForProofText(coordinator, FIXTURE_PORT)
        val activeHostBeforeRun = coordinator.observeUiState().value.activeHostId

        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$snippetId")
            .assertTextContains("Active session: First active fixture host", substring = true)
        waitForSnippetTargetReady(snippetId)
        openExecutionConfirmation(snippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Active session: First active fixture host", substring = true)
        repeat(2) {
            runCatching {
                composeRule.onNodeWithTag("snippet_execution_confirm")
                    .performSemanticsAction(SemanticsActions.OnClick)
            }
        }

        waitForSnippetHistorySize(container, 1)
        waitForOutputLineOccurrences(
            coordinator = coordinator,
            expected = "ACTIVE_SESSION_REUSE_PROOF",
            expectedCount = 1,
        )
        runBlocking { delay(750) }
        assertEquals(activeHostBeforeRun, coordinator.observeUiState().value.activeHostId)
        assertEquals(
            1,
            coordinator.observeUiState().value.transcript.countOutputLineOccurrences("ACTIVE_SESSION_REUSE_PROOF"),
        )
        waitForSnippetHistorySize(container, 1)
        disconnectIfConnected(coordinator)
    }

    @Test
    fun failed_saved_host_dispatch_does_not_record_success_history_but_payload_proof_success_does() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val failingIdentityId = seedFixtureIdentity(
            container = container,
            name = "Failing fixture identity",
            password = "wrong-password",
        )
        val successIdentityId = seedFixtureIdentity(container, "Successful fixture identity")
        val failingHostId = seedFixtureHost(
            container = container,
            identityId = failingIdentityId,
            label = "Failing fixture host",
        )
        val successHostId = seedFixtureHost(
            container = container,
            identityId = successIdentityId,
            label = "Successful fixture host",
        )
        val failingSnippetId = seedSnippet(
            container = container,
            title = "Failure should stay unproven",
            body = "printf 'UNPROVEN_FAILURE_SHOULD_NOT_RECORD\\n'",
            savedTarget = SnippetSavedTarget.SAVED_HOST,
            hostId = failingHostId,
        )
        val successfulSnippetId = seedSnippet(
            container = container,
            title = "Payload proof success",
            body = "printf 'PROVEN_SUCCESS_PAYLOAD\\n'",
            savedTarget = SnippetSavedTarget.SAVED_HOST,
            hostId = successHostId,
        )
        val coordinator = container.sshSessionCoordinator

        launchAppWithContainer(container)
        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$failingSnippetId")
            .assertTextContains("Saved host: Failing fixture host", substring = true)
        openExecutionConfirmation(failingSnippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: Failing fixture host", substring = true)
        composeRule.onNodeWithTag("snippet_execution_body_preview")
            .assertTextContains("UNPROVEN_FAILURE_SHOULD_NOT_RECORD", substring = true)
        clickTaggedNode("snippet_execution_confirm")
        acceptSnippetTrustIfVisible()
        waitForExecutionError("Authentication failed. Verify the linked identity and try again.")
        assertEquals(0, runBlocking { container.foundationGraph.snippetRepository.observeExecutionHistory().first().size })
        assertNull(runBlocking { container.foundationGraph.snippetRepository.getSnippet(failingSnippetId) }?.lastRunAt)
        composeRule.onNodeWithTag("snippet_execution_cancel").performClick()

        scrollSnippetRowIntoView(successfulSnippetId)
        composeRule.onNodeWithTag("snippet_run_target_$successfulSnippetId")
            .assertTextContains("Saved host: Successful fixture host", substring = true)
        openExecutionConfirmation(successfulSnippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: Successful fixture host", substring = true)
        composeRule.onNodeWithTag("snippet_execution_body_preview")
            .assertTextContains("PROVEN_SUCCESS_PAYLOAD", substring = true)
        clickTaggedNode("snippet_execution_confirm")
        acceptSnippetTrustIfVisible()

        waitForSnippetHistorySize(container, 1)
        waitForTranscriptOutputLine(coordinator, "PROVEN_SUCCESS_PAYLOAD")
        scrollToHistoryEntry(0)
        composeRule.onNodeWithTag("snippet_history_title_0")
            .assertTextContains("Payload proof success", substring = true)
        composeRule.onNodeWithTag("snippet_history_target_0")
            .assertTextContains("Saved host: Successful fixture host", substring = true)
        disconnectIfConnected(coordinator)
    }

    @Test
    fun successful_runs_show_recent_history_newest_first_with_target_context() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = seedFixtureIdentity(container, "Recent history identity")
        val alphaHostId = seedFixtureHost(container, identityId = identityId, label = "Alpha fixture host")
        val betaHostId = seedFixtureHost(container, identityId = identityId, label = "Beta fixture host")
        val alphaSnippetId = seedSnippet(
            container = container,
            title = "Recent target proof",
            body = "printf 'RECENT_HISTORY_ALPHA\\n'",
            savedTarget = SnippetSavedTarget.SAVED_HOST,
            hostId = alphaHostId,
        )
        val betaSnippetId = seedSnippet(
            container = container,
            title = "Recent target proof",
            body = "printf 'RECENT_HISTORY_BETA\\n'",
            savedTarget = SnippetSavedTarget.SAVED_HOST,
            hostId = betaHostId,
        )
        val coordinator = container.sshSessionCoordinator

        launchAppWithContainer(container)
        openSnippetsScreen()

        openExecutionConfirmation(alphaSnippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: Alpha fixture host", substring = true)
        clickTaggedNode("snippet_execution_confirm")
        acceptSnippetTrustIfVisible()
        waitForSnippetHistorySize(container, 1)
        waitForTranscriptOutputLine(coordinator, "RECENT_HISTORY_ALPHA")

        openExecutionConfirmation(betaSnippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: Beta fixture host", substring = true)
        clickTaggedNode("snippet_execution_confirm")
        acceptSnippetTrustIfVisible()

        waitForSnippetHistorySize(container, 2)
        waitForTranscriptOutputLine(coordinator, "RECENT_HISTORY_BETA")
        scrollToHistoryEntry(0)
        composeRule.onNodeWithTag("snippet_history_title_0")
            .assertTextContains("Recent target proof", substring = true)
        composeRule.onNodeWithTag("snippet_history_target_0")
            .assertTextContains("Saved host: Beta fixture host", substring = true)
        scrollToHistoryEntry(1)
        composeRule.onNodeWithTag("snippet_history_title_1")
            .assertTextContains("Recent target proof", substring = true)
        composeRule.onNodeWithTag("snippet_history_target_1")
            .assertTextContains("Saved host: Alpha fixture host", substring = true)
        disconnectIfConnected(coordinator)
    }

    @Test
    fun latest_saved_content_history_relaunch_and_deletion_stay_coherent_on_real_fixture_surface() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = seedFixtureIdentity(container, "History coherence identity")
        val hostId = seedFixtureHost(container, identityId = identityId, label = "History coherence host")
        val snippetId = seedSnippet(
            container = container,
            title = "History coherence snippet",
            body = "printf 'OLD_HISTORY_CONTENT_SHOULD_NOT_RUN\\n'",
            savedTarget = SnippetSavedTarget.SAVED_HOST,
            hostId = hostId,
        )
        val coordinator = container.sshSessionCoordinator

        logSnippetStep("history coherence: launch app", coordinator)
        launchAppWithContainer(container)
        logSnippetStep("history coherence: open snippets", coordinator)
        openSnippetsScreen()
        logSnippetStep("history coherence: open editor", coordinator)
        openSnippetEditor(snippetId)
        composeRule.onNodeWithTag("snippet_body_field").performTextClearance()
        composeRule.onNodeWithTag("snippet_body_field").performTextInput("printf 'LATEST_HISTORY_CONTENT_PROOF\\n'")
        composeRule.onNodeWithTag("snippet_editor_save").performScrollTo().performClick()
        waitForSnippetsScreen()
        logSnippetStep("history coherence: saved latest content", coordinator)
        assertEquals(
            "printf 'LATEST_HISTORY_CONTENT_PROOF\\n'",
            runBlocking { container.foundationGraph.snippetRepository.getBody(snippetId) },
        )

        logSnippetStep("history coherence: open confirmation", coordinator)
        openExecutionConfirmation(snippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: History coherence host", substring = true)
        composeRule.onNodeWithTag("snippet_execution_body_preview")
            .assertTextContains("LATEST_HISTORY_CONTENT_PROOF", substring = true)
        clickTaggedNode("snippet_execution_confirm")
        acceptSnippetTrustIfVisible()

        waitForSnippetHistorySize(container, 1)
        waitForTranscriptOutputLine(coordinator, "LATEST_HISTORY_CONTENT_PROOF")
        logSnippetStep("history coherence: execution proof observed", coordinator)
        assertFalse(coordinator.observeUiState().value.transcript.contains("OLD_HISTORY_CONTENT_SHOULD_NOT_RUN"))
        scrollToHistoryEntry(0)
        composeRule.onNodeWithTag("snippet_history_title_0")
            .assertTextContains("History coherence snippet", substring = true)
        composeRule.onNodeWithTag("snippet_history_status_0")
            .assertTextContains("Snippet still available", substring = true)

        disconnectIfConnected(coordinator)
        logSnippetStep("history coherence: disconnected before background", coordinator)
        deviceOrchestrator.backgroundAndResumeApp(appPackage = APP_PACKAGE)
        logSnippetStep("history coherence: resumed app", coordinator)
        openSnippetsScreen()
        logSnippetStep("history coherence: snippets reopened after background", coordinator)
        scrollToHistoryEntry(0)
        composeRule.onNodeWithTag("snippet_history_title_0")
            .assertTextContains("History coherence snippet", substring = true)
        composeRule.onNodeWithTag("snippet_history_status_0")
            .assertTextContains("Snippet still available", substring = true)
        logSnippetStep("history coherence: reopen editor after background", coordinator)
        openSnippetEditor(snippetId)
        composeRule.onNodeWithTag("snippet_editor_delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("snippet_delete_confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_delete_confirm").performClick()
        logSnippetStep("history coherence: deleted snippet", coordinator)

        composeRule.onAllNodesWithTag("snippet_row_$snippetId").assertCountEquals(0)
        scrollToHistoryEntry(0)
        composeRule.onNodeWithTag("snippet_history_title_0")
            .assertTextContains("History coherence snippet", substring = true)
        composeRule.onNodeWithTag("snippet_history_status_0")
            .assertTextContains("Snippet deleted", substring = true)
        composeRule.onAllNodesWithTag("snippet_edit_$snippetId").assertCountEquals(0)
    }

    private fun launchAppWithContainer(container: AppContainer) {
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        deviceOrchestrator.waitForAppShell(APP_PACKAGE)
    }

    private fun openSessionScreen() {
        deviceOrchestrator.openSessionScreen(APP_PACKAGE)
    }

    private fun openSnippetsScreen() {
        deviceOrchestrator.waitForAppShell(APP_PACKAGE)
        clickTaggedNode("nav_snippets")
        waitForSnippetsScreen()
    }

    private fun waitForSnippetsScreen() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("screen_snippets").assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForExecutionConfirmation() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("snippet_execution_confirmation").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun waitForExecutionError(
        expectedMessage: String,
        timeoutMillis: Long = 10_000L,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag("snippet_execution_error")
                    .assertTextContains(expectedMessage, substring = true)
            }.isSuccess
        }
    }

    private fun openExecutionConfirmation(snippetId: Long) {
        ensureSnippetActionReady(snippetId, "snippet_run_$snippetId")
        composeRule.onNodeWithTag("snippet_run_$snippetId").performClick()
        waitForExecutionConfirmation()
    }

    private fun openSnippetEditor(snippetId: Long) {
        ensureSnippetActionReady(snippetId, "snippet_edit_$snippetId")
        composeRule.onNodeWithTag("snippet_edit_$snippetId").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("snippet_editor").assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun scrollSnippetRowIntoView(snippetId: Long) {
        val rowTag = "snippet_row_$snippetId"
        dismissExecutionNoticeIfVisible()
        repeat(8) {
            if (composeRule.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag(rowTag).performScrollTo()
                return
            }
            if (composeRule.onAllNodesWithTag("snippet_list").fetchSemanticsNodes().isNotEmpty()) {
                runCatching {
                    composeRule.onNodeWithTag("snippet_list").performTouchInput { swipeUp() }
                }
            }
            device.swipe(
                device.displayWidth / 2,
                (device.displayHeight * 3) / 4,
                device.displayWidth / 2,
                device.displayHeight / 3,
                20,
            )
            device.waitForIdle()
            composeRule.waitForIdle()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(rowTag).performScrollTo()
    }

    private fun scrollToHistoryEntry(index: Int) {
        val entryTag = "snippet_history_entry_$index"
        if (composeRule.onAllNodesWithTag(entryTag).fetchSemanticsNodes().isNotEmpty()) {
            return
        }
        composeRule.onNodeWithTag("snippet_list").performScrollToNode(hasTestTag(entryTag))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(entryTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ensureSnippetActionReady(
        snippetId: Long,
        testTag: String,
    ) {
        scrollSnippetRowIntoView(snippetId)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(testTag).performScrollTo()
    }

    private fun dismissExecutionNoticeIfVisible() {
        if (composeRule.onAllNodesWithTag("snippet_execution_notice_dismiss").fetchSemanticsNodes().isNotEmpty()) {
            clickTaggedNode("snippet_execution_notice_dismiss")
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag("snippet_execution_notice").fetchSemanticsNodes().isEmpty()
            }
        }
    }

    private fun filterSnippetsByQuery(query: String) {
        composeRule.onNodeWithTag("snippet_search_field").performTextClearance()
        composeRule.onNodeWithTag("snippet_search_field").performTextInput(query)
        device.pressBack()
        device.waitForIdle()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("snippet_search_empty_state").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForSnippetTargetReady(snippetId: Long) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("snippet_target_warning_$snippetId").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun logSnippetStep(
        label: String,
        coordinator: SshSessionCoordinator,
    ) {
        Log.i("SnippetRealFixtureTest", "$label :: ${coordinator.observeUiState().value.transcript.takeLast(300)}")
    }

    private fun clickTaggedNode(testTag: String) {
        composeRule.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun acceptSnippetTrustIfVisible() {
        val trustPromptVisible = runCatching {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag("snippet_execution_trust_prompt").fetchSemanticsNodes().isNotEmpty()
            }
            true
        }.getOrDefault(false)
        if (trustPromptVisible) {
            composeRule.onNodeWithTag("snippet_execution_trust_endpoint")
                .assertTextContains("$FIXTURE_HOST:$FIXTURE_PORT", substring = true)
            clickTaggedNode("snippet_execution_trust_accept")
        }
    }

    private fun seedFixtureIdentity(
        container: AppContainer,
        name: String,
        password: String = FIXTURE_PASSWORD,
    ): Long = runBlocking {
        container.foundationGraph.identityRepository.upsert(
            identity = Identity(
                name = name,
                kind = IdentityKind.PASSWORD,
            ),
            secrets = IdentitySecretMaterial(primarySecret = password),
        ).id
    }

    private fun seedFixtureHost(
        container: AppContainer,
        identityId: Long,
        label: String,
    ): Long = runBlocking {
        container.foundationGraph.hostRepository.upsert(
            Host(
                label = label,
                address = FIXTURE_HOST,
                port = FIXTURE_PORT,
                username = FIXTURE_USERNAME,
                identityId = identityId,
                authKind = HostAuthKind.PASSWORD,
            ),
        ).id
    }

    private fun seedSnippet(
        container: AppContainer,
        title: String,
        body: String,
        savedTarget: SnippetSavedTarget,
        hostId: Long? = null,
    ): Long = runBlocking {
        container.foundationGraph.snippetRepository.upsert(
            snippet = Snippet(
                title = title,
                hostId = hostId,
                savedTarget = savedTarget,
            ),
            body = body,
        ).id
    }

    private fun waitForSnippetHistorySize(
        container: AppContainer,
        expectedSize: Int,
    ) {
        var waitFailure: Throwable? = null
        val reached = runCatching {
            runBlocking {
                withTimeout(30_000L) {
                    while (true) {
                        if (container.foundationGraph.snippetRepository.observeExecutionHistory().first().size == expectedSize) {
                            return@withTimeout
                        }
                        delay(100)
                    }
                }
            }
            true
        }.onFailure { waitFailure = it }.getOrDefault(false)
        if (!reached) {
            val currentHistorySize = runBlocking {
                container.foundationGraph.snippetRepository.observeExecutionHistory().first().size
            }
            val executionError = currentNodeTextOrNull("snippet_execution_error")
            val trustPromptEndpoint = currentNodeTextOrNull("snippet_execution_trust_endpoint")
            val confirmationTarget = currentNodeTextOrNull("snippet_execution_target")
            val progressMessage = currentNodeTextOrNull("snippet_execution_progress")
            val transcriptTail = container.sshSessionCoordinator.observeUiState().value.transcript.takeLast(600)
            error(
                buildString {
                    append("Expected snippet history size $expectedSize but was $currentHistorySize.")
                    executionError?.let { append(" executionError=$it.") }
                    trustPromptEndpoint?.let { append(" trustPrompt=$it.") }
                    confirmationTarget?.let { append(" confirmationTarget=$it.") }
                    progressMessage?.let { append(" progress=$it.") }
                    waitFailure?.let {
                        append(" waitFailure=${it::class.java.simpleName}:${it.message}.")
                    }
                    if (transcriptTail.isNotBlank()) {
                        append(" transcriptTail=$transcriptTail.")
                    }
                },
            )
        }
    }

    private fun currentNodeTextOrNull(testTag: String): String? = runCatching {
        if (composeRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().isEmpty()) {
            null
        } else {
            composeRule.onNodeWithTag(testTag)
                .fetchSemanticsNode()
                .config
                .getOrElse(SemanticsProperties.Text) { emptyList() }
                .joinToString(separator = "") { it.text }
                .ifBlank { null }
        }
    }.getOrNull()

    private fun connectFromUi(
        coordinator: SshSessionCoordinator,
        hostId: Long,
        expectedButtonLabel: String?,
        expectTrustPrompt: Boolean,
    ): SessionUiState {
        val connectButtonTag = "session_connect_$hostId"
        val initialState = coordinator.observeUiState().value
        scrollSessionHostIntoView(hostId)
        composeRule.onNodeWithTag(connectButtonTag).performScrollTo()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag(connectButtonTag).assertIsDisplayed()
            }.isSuccess
        }
        expectedButtonLabel?.let { label ->
            composeRule.onNodeWithTag(connectButtonTag)
                .assertTextContains(label, substring = true)
        }
        composeRule.onNodeWithTag(connectButtonTag).performClick()
        waitForState(coordinator) {
            it.activeHostId == hostId &&
                (
                    it.connectionState == SessionConnectionState.CONNECTING ||
                        it.pendingTrustDecision != null ||
                        it.connectionState != initialState.connectionState ||
                        it.statusMessage != initialState.statusMessage ||
                        it.transcript != initialState.transcript
                    )
        }
        if (expectTrustPrompt) {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                runCatching {
                    composeRule.onNodeWithTag("session_trust_prompt").assertIsDisplayed()
                }.isSuccess
            }
            composeRule.onNodeWithTag("session_trust_endpoint")
                .assertTextContains("$FIXTURE_HOST:$FIXTURE_PORT", substring = true)
            composeRule.onNodeWithTag("session_trust_accept")
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForState(coordinator, timeoutMillis = 10_000) { it.pendingTrustDecision == null }
        }
        val state = waitForState(coordinator, timeoutMillis = 30_000) {
            it.activeHostId == hostId && it.connectionState == SessionConnectionState.CONNECTED
        }
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        return state
    }

    private fun scrollSessionHostIntoView(hostId: Long) {
        val rowTag = "session_host_row_$hostId"
        repeat(8) {
            if (composeRule.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag(rowTag).performScrollTo()
                return
            }
            composeRule.onNodeWithTag("session_host_list").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(rowTag).performScrollTo()
    }

    private fun waitForUiDisconnect(
        coordinator: SshSessionCoordinator,
        expectedMessage: String,
    ) {
        val state = waitForState(coordinator, timeoutMillis = 10_000) {
            it.connectionState == SessionConnectionState.DISCONNECTED && it.statusMessage == expectedMessage
        }
        assertEquals(expectedMessage, state.statusMessage)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains(expectedMessage, substring = true)
            }.isSuccess
        }
    }

    private fun disconnectIfConnected(coordinator: SshSessionCoordinator) {
        if (coordinator.observeUiState().value.connectionState != SessionConnectionState.CONNECTED) {
            return
        }
        openSessionScreen()
        if (composeRule.onAllNodesWithTag("session_disconnect_button").fetchSemanticsNodes().isNotEmpty()) {
            clickTaggedNode("session_disconnect_button")
            waitForUiDisconnect(coordinator, "Disconnected.")
        }
    }

    private fun waitForState(
        coordinator: SshSessionCoordinator,
        timeoutMillis: Long = 60_000L,
        predicate: (SessionUiState) -> Boolean,
    ): SessionUiState = runBlocking {
        withTimeout(timeoutMillis) {
            var current: SessionUiState
            do {
                current = coordinator.observeUiState().value
                if (!predicate(current)) {
                    delay(100)
                }
            } while (!predicate(current))
            current
        }
    }

    private fun waitForProofText(
        coordinator: SshSessionCoordinator,
        port: Int,
    ) {
        waitForState(coordinator, timeoutMillis = 30_000) {
            it.transcript.containsRemoteProofReadyState(host = FIXTURE_HOST, port = port)
        }
    }

    private fun waitForTranscriptOutputLine(
        coordinator: SshSessionCoordinator,
        expected: String,
        timeoutMillis: Long = 30_000L,
    ) {
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            it.transcript.containsOutputLine(expected)
        }
        check(state.transcript.containsOutputLine(expected)) {
            "Transcript did not contain output line \"$expected\": ${state.transcript.takeLast(400)}"
        }
    }

    private fun waitForOutputLineOccurrences(
        coordinator: SshSessionCoordinator,
        expected: String,
        expectedCount: Int,
        timeoutMillis: Long = 30_000L,
    ) {
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            it.transcript.countOutputLineOccurrences(expected) >= expectedCount
        }
        check(state.transcript.countOutputLineOccurrences(expected) >= expectedCount) {
            "Transcript did not contain $expectedCount occurrences of \"$expected\": ${state.transcript.takeLast(400)}"
        }
    }

    private fun waitForTranscriptOutputLinesInOrder(
        coordinator: SshSessionCoordinator,
        expectedLines: List<String>,
        timeoutMillis: Long = 30_000L,
    ) {
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            containsOrderedOutputLines(sanitizedTranscriptLines(it.transcript), expectedLines)
        }
        check(containsOrderedOutputLines(sanitizedTranscriptLines(state.transcript), expectedLines)) {
            "Transcript did not contain expected output lines in order ${expectedLines.joinToString()}: ${state.transcript.takeLast(400)}"
        }
    }

    private fun assertFixtureReachableFromHost() {
        Socket(FIXTURE_HOST, FIXTURE_PORT).use { socket ->
            check(socket.isConnected) { "Fixture socket did not connect to $FIXTURE_HOST:$FIXTURE_PORT" }
        }
    }

    private companion object {
        const val FIXTURE_HOST = "10.0.2.2"
        const val FIXTURE_PORT = 3122
        const val FIXTURE_USERNAME = "atermtester"
        const val FIXTURE_PASSWORD = "aterm-password-fixture"
        const val APP_PACKAGE = "io.github.jtsang4.aterm"
    }
}

private fun String.containsOutputLine(value: String): Boolean =
    countOutputLineOccurrences(value) > 0

private fun String.countOutputLineOccurrences(value: String): Int =
    sanitizedTranscriptLines(this).count { it == value }

private fun sanitizedTranscriptLines(text: String): List<String> =
    text.lineSequence()
        .map(::sanitizeTerminalLine)
        .filter { it.isNotEmpty() }
        .toList()

private fun sanitizeTerminalLine(text: String): String =
    text.replace(Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]"""), "").trim()

private fun String.containsRemoteProofLine(host: String, port: Int): Boolean =
    sanitizedTranscriptLines(this).any { line ->
        line.startsWith("ATERM_REMOTE_PROOF:$host:$port:")
    }

private fun String.containsRemoteProofReadyState(host: String, port: Int): Boolean {
    val lines = sanitizedTranscriptLines(this)
    val proofIndex = lines.indexOfLast { line -> line.startsWith("ATERM_REMOTE_PROOF:$host:$port:") }
    if (proofIndex < 0) {
        return false
    }
    return lines.drop(proofIndex + 1).any { line -> line.matches(Regex("""bash-[\d.]+[#$]""")) }
}

private fun containsOrderedOutputLines(
    lines: List<String>,
    expectedLines: List<String>,
): Boolean {
    var nextIndex = 0
    for (line in lines) {
        if (nextIndex < expectedLines.size && line == expectedLines[nextIndex]) {
            nextIndex += 1
        }
    }
    return nextIndex == expectedLines.size
}
