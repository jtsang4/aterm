package io.github.jtsang4.aterm

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.model.SnippetSavedTarget
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.ssh.SessionUiState
import io.github.jtsang4.aterm.core.ssh.SshSessionCoordinator
import io.github.jtsang4.aterm.core.terminal.calculateTerminalViewport
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.identities.GeneratedKeyIdentityService
import io.github.jtsang4.aterm.feature.identities.GeneratedKeyMaterial
import java.io.File
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionSshFixtureInstrumentedTest {
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
            application.resetDefaultContainerForTesting()
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(resetAppStateRule)
        .around(composeRule)

    @Test
    fun real_fixture_connection_requires_explicit_trust_then_reuses_it_after_identity_edit_and_relaunch() {
        val firstContainer = AppContainer.create(context)
        val identityId = runBlocking {
            firstContainer.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = buildCoordinator(firstContainer)

        coordinator.connect(hostId)
        val trustPrompt = waitForState(coordinator) { it.pendingTrustDecision != null }
        assertEquals("$FIXTURE_HOST:$FIXTURE_PORT", trustPrompt.pendingTrustDecision?.endpoint)
        coordinator.submitHostTrustDecision(false)
        val rejected = waitForState(coordinator) { it.connectionState == SessionConnectionState.FAILED }
        assertEquals("Host trust rejected.", rejected.statusMessage)
        assertEquals(
            0,
            runBlocking { firstContainer.foundationGraph.knownHostTrustRepository.observeTrustedHosts().first().size },
        )

        coordinator.connect(hostId)
        waitForState(coordinator) { it.pendingTrustDecision != null }
        coordinator.submitHostTrustDecision(true)
        val connected = waitForState(coordinator) { it.connectionState == SessionConnectionState.CONNECTED }
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", connected.statusMessage)
        assertProofEventually(coordinator, FIXTURE_PORT)
        assertEquals(
            1,
            runBlocking { firstContainer.foundationGraph.knownHostTrustRepository.observeTrustedHosts().first().size },
        )

        runBlocking {
            val existing = firstContainer.foundationGraph.identityRepository.getIdentity(identityId)
                ?: error("Fixture identity missing")
            firstContainer.foundationGraph.identityRepository.upsert(
                identity = existing.copy(name = "Renamed fixture password"),
                secrets = null,
            )
        }

        coordinator.disconnect()
        val disconnected = waitForState(coordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
        assertEquals("Disconnected.", disconnected.statusMessage)

        coordinator.connect(hostId)
        val reconnected = waitForState(coordinator) { it.connectionState == SessionConnectionState.CONNECTED }
        assertNull(reconnected.pendingTrustDecision)
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", reconnected.statusMessage)
        assertProofEventually(coordinator, FIXTURE_PORT)

        coordinator.disconnect()
        waitForState(coordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }

        val relaunchedContainer = AppContainer.create(context)
        val relaunchedCoordinator = buildCoordinator(relaunchedContainer)
        val relaunchedIdentity = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.getIdentity(identityId)
        }
        assertEquals("Renamed fixture password", relaunchedIdentity?.name)

        relaunchedCoordinator.connect(hostId)
        val relaunchedConnected = waitForState(relaunchedCoordinator) {
            it.connectionState == SessionConnectionState.CONNECTED
        }
        assertNull(relaunchedConnected.pendingTrustDecision)
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", relaunchedConnected.statusMessage)
        assertProofEventually(relaunchedCoordinator, FIXTURE_PORT)
        assertNotNull(
            runBlocking { relaunchedContainer.foundationGraph.identityRepository.getSecretMaterial(identityId) },
        )
        relaunchedCoordinator.disconnect()
        waitForState(relaunchedCoordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
    }

    @Test
    fun real_fixture_session_ui_proves_trust_identity_edit_and_relaunch_through_visible_app_flows() {
        assertFixtureReachableFromHost()
        val firstContainer = AppContainer.create(context)
        val identityId = runBlocking {
            firstContainer.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val firstCoordinator = firstContainer.sshSessionCoordinator
        application.replaceAppContainerForTesting(firstContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_identities").performClick()
        composeRule.onNodeWithTag("identity_row_$identityId").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.onNodeWithTag("host_row_$hostId").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_session").performClick()
        val firstConnected = connectFromUi(
            coordinator = firstCoordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", firstConnected.statusMessage)
        composeRule.onNodeWithTag("session_active_endpoint")
            .assertTextContains("$FIXTURE_HOST:$FIXTURE_PORT", substring = true)
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
        assertEquals(
            1,
            runBlocking { firstContainer.foundationGraph.knownHostTrustRepository.observeTrustedHosts().first().size },
        )

        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        waitForUiDisconnect(firstCoordinator, "Disconnected.")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            firstCoordinator.observeUiState().value.canSendInput.not() &&
                composeRule.onAllNodesWithTag("session_terminal_truth_banner")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("session_terminal_truth_banner").assertCountEquals(1)
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()

        composeRule.onNodeWithTag("nav_identities").performClick()
        composeRule.onNodeWithTag("identity_edit_$identityId").performClick()
        composeRule.onNodeWithTag("identity_name_field").performTextClearance()
        composeRule.onNodeWithTag("identity_name_field").performTextInput("Renamed fixture password")
        composeRule.onNodeWithTag("identity_editor_save").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("identity_row_$identityId").assertIsDisplayed()
            }.isSuccess
        }

        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_host_identity_$hostId")
                    .assertTextContains("Renamed fixture password", substring = true)
            }.isSuccess
        }
        val reconnectedAfterEdit = connectFromUi(
            coordinator = firstCoordinator,
            hostId = hostId,
            expectedButtonLabel = "Reconnect",
            expectTrustPrompt = false,
        )
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", reconnectedAfterEdit.statusMessage)
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)

        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        waitForUiDisconnect(firstCoordinator, "Disconnected.")

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        val relaunchedIdentity = runBlocking {
            firstContainer.foundationGraph.identityRepository.getIdentity(identityId)
        }
        assertEquals("Renamed fixture password", relaunchedIdentity?.name)
        assertNotNull(
            runBlocking { firstContainer.foundationGraph.identityRepository.getSecretMaterial(identityId) },
        )

        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.onNodeWithTag("session_host_row_$hostId").performScrollTo()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_host_identity_$hostId")
                    .assertTextContains("Renamed fixture password", substring = true)
            }.isSuccess
        }
        assertEquals(
            1,
            runBlocking { firstContainer.foundationGraph.knownHostTrustRepository.observeTrustedHosts().first().size },
        )
        val relaunchedConnected = connectFromUi(
            coordinator = firstCoordinator,
            hostId = hostId,
            expectedButtonLabel = "Reconnect",
            expectTrustPrompt = false,
        )
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", relaunchedConnected.statusMessage)
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        waitForUiDisconnect(firstCoordinator, "Disconnected.")
    }

    @Test
    fun favorites_and_recents_survive_successful_connections_without_duplicate_recent_hosts() {
        assertFixtureReachableFromHost()
        val firstContainer = AppContainer.create(context)
        val primaryIdentityId = runBlocking {
            firstContainer.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Recent fixture password A",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val secondaryIdentityId = runBlocking {
            firstContainer.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Recent fixture password B",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val alphaHostId = runBlocking {
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Fixture Alpha",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = primaryIdentityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val betaHostId = runBlocking {
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Fixture Beta",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = secondaryIdentityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val firstCoordinator = firstContainer.sshSessionCoordinator
        application.replaceAppContainerForTesting(firstContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("host_favorite_toggle_$alphaHostId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_favorite_toggle_$alphaHostId")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("favorite_host_item_0_$alphaHostId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("favorite_host_item_0_$alphaHostId").assertIsDisplayed()

        firstCoordinator.connect(alphaHostId)
        waitForState(firstCoordinator) { it.pendingTrustDecision != null }
        firstCoordinator.submitHostTrustDecision(true)
        waitForState(firstCoordinator, timeoutMillis = 30_000) {
            it.activeHostId == alphaHostId && it.connectionState == SessionConnectionState.CONNECTED
        }
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        firstCoordinator.disconnect()
        waitForState(firstCoordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
        firstCoordinator.connect(betaHostId)
        waitForState(firstCoordinator, timeoutMillis = 30_000) {
            it.activeHostId == betaHostId && it.connectionState == SessionConnectionState.CONNECTED
        }
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        firstCoordinator.disconnect()
        waitForState(firstCoordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
        firstCoordinator.connect(alphaHostId)
        waitForState(firstCoordinator, timeoutMillis = 30_000) {
            it.activeHostId == alphaHostId && it.connectionState == SessionConnectionState.CONNECTED
        }
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        firstCoordinator.disconnect()
        waitForState(firstCoordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("recent_host_item_0_$alphaHostId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("recent_host_item_0_$alphaHostId").assertCountEquals(1)
        composeRule.onAllNodesWithTag("recent_host_item_1_$betaHostId").assertCountEquals(1)
        composeRule.onAllNodesWithTag("recent_host_row_$alphaHostId").assertCountEquals(1)

        val alphaPersisted = runBlocking { firstContainer.foundationGraph.hostRepository.getHost(alphaHostId) }
        val betaPersisted = runBlocking { firstContainer.foundationGraph.hostRepository.getHost(betaHostId) }
        assertTrue(alphaPersisted?.isFavorite == true)
        assertNotNull(alphaPersisted?.lastUsedAt)
        assertNotNull(betaPersisted?.lastUsedAt)
        assertTrue(requireNotNull(alphaPersisted?.lastUsedAt).isAfter(requireNotNull(betaPersisted?.lastUsedAt)))

        val relaunchedContainer = AppContainer.create(context)
        application.replaceAppContainerForTesting(relaunchedContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("favorite_host_item_0_$alphaHostId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("favorite_host_item_0_$alphaHostId").assertCountEquals(1)
        composeRule.onAllNodesWithTag("recent_host_item_0_$alphaHostId").assertCountEquals(1)
        composeRule.onAllNodesWithTag("recent_host_item_1_$betaHostId").assertCountEquals(1)
        composeRule.onAllNodesWithTag("recent_host_row_$alphaHostId").assertCountEquals(1)
    }

    @Test
    fun favorite_repeat_use_flow_reconnects_and_runs_saved_snippet_without_any_login_or_sync_gate() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Favorite repeat password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Favorite repeat host",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                    isFavorite = true,
                ),
            ).id
        }
        val snippetId = runBlocking {
            container.foundationGraph.snippetRepository.upsert(
                snippet = Snippet(
                    title = "Favorite repeat snippet",
                    hostId = hostId,
                    savedTarget = SnippetSavedTarget.SAVED_HOST,
                ),
                body = "printf 'FAVORITE_REPEAT_SNIPPET_OK\\n'",
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        waitForProofText(coordinator, FIXTURE_PORT)
        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        waitForUiDisconnect(coordinator, "Disconnected.")

        val relaunchedContainer = AppContainer.create(context)
        application.replaceAppContainerForTesting(relaunchedContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("favorite_host_item_0_$hostId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("favorite_host_item_0_$hostId").performClick()
        val relaunchedCoordinator = relaunchedContainer.sshSessionCoordinator
        val reconnected = waitForState(relaunchedCoordinator, timeoutMillis = 30_000) {
            it.activeHostId == hostId && it.connectionState == SessionConnectionState.CONNECTED
        }
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", reconnected.statusMessage)
        waitForProofText(relaunchedCoordinator, FIXTURE_PORT)

        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$snippetId")
            .assertTextContains("Saved host: Favorite repeat host", substring = true)
        openSnippetExecutionConfirmation(snippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: Favorite repeat host", substring = true)
        composeRule.onNodeWithTag("snippet_execution_confirm").performClick()

        waitForTranscriptOutputLine(relaunchedCoordinator, "FAVORITE_REPEAT_SNIPPET_OK")
        waitForSnippetHistoryRecord(
            snippetRepository = relaunchedContainer.foundationGraph.snippetRepository,
            expectedLabel = "Favorite repeat host",
        )
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", relaunchedCoordinator.observeUiState().value.statusMessage)
        composeRule.onAllNodesWithText("Login", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Sync", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Team", substring = true).assertCountEquals(0)

        relaunchedCoordinator.disconnect()
        waitForState(relaunchedCoordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
    }

    @Test
    fun deleted_saved_host_snippet_stays_blocked_and_does_not_fall_back_to_active_session() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Snippet fixture host",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val snippetId = runBlocking {
            container.foundationGraph.snippetRepository.upsert(
                snippet = Snippet(
                    title = "Deleted host snippet",
                    hostId = hostId,
                    savedTarget = SnippetSavedTarget.SAVED_HOST,
                ),
                body = "printf 'STALE_TARGET_SHOULD_NOT_RUN\\n'",
            ).id
        }
        val coordinator = container.sshSessionCoordinator

        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        waitForProofText(coordinator, FIXTURE_PORT)
        waitForState(coordinator, timeoutMillis = 5_000) {
            !it.isConnecting &&
                it.pendingTrustDecision == null &&
                it.connectionState == SessionConnectionState.CONNECTED
        }
        val transcriptBeforeDelete = coordinator.observeUiState().value.transcript

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.onNodeWithTag("host_edit_$hostId").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_editor_delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_delete_confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("host_delete_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { container.foundationGraph.hostRepository.getHost(hostId) } == null
        }

        val staleSnippet = runBlocking { container.foundationGraph.snippetRepository.getSnippet(snippetId) }
        assertNull(staleSnippet?.hostId)
        assertEquals(SnippetSavedTarget.SAVED_HOST, staleSnippet?.savedTarget)

        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$snippetId")
            .assertTextContains("Saved host target needs repair", substring = true)
        composeRule.onNodeWithTag("snippet_target_warning_$snippetId")
            .assertTextContains("saved host target is missing or stale", substring = true)

        composeRule.onNodeWithTag("snippet_run_$snippetId").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("snippet_execution_blocked").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("snippet_execution_blocked").assertIsDisplayed()
        composeRule.onAllNodesWithTag("snippet_execution_confirmation").assertCountEquals(0)
        composeRule.onNodeWithTag("snippet_execution_block_reason")
            .assertTextContains("saved host target is missing or stale", substring = true)
        composeRule.onNodeWithTag("snippet_execution_repair").assertIsDisplayed()

        val transcriptAfterBlockedRun = coordinator.observeUiState().value.transcript
        assertEquals(
            transcriptBeforeDelete.contains("STALE_TARGET_SHOULD_NOT_RUN"),
            transcriptAfterBlockedRun.contains("STALE_TARGET_SHOULD_NOT_RUN"),
        )
        assertFalse(transcriptAfterBlockedRun.contains("STALE_TARGET_SHOULD_NOT_RUN"))
    }

    @Test
    fun edited_password_host_and_saved_snippet_target_reconnect_with_updated_fixture_port_after_relaunch() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Port edit password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Port edit host",
                    address = FIXTURE_HOST,
                    port = HOST_SSH_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val snippetId = runBlocking {
            container.foundationGraph.snippetRepository.upsert(
                snippet = Snippet(
                    title = "Port edit snippet",
                    hostId = hostId,
                    savedTarget = SnippetSavedTarget.SAVED_HOST,
                ),
                body = "printf 'UPDATED_PORT_SNIPPET_OK\\n'",
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        val initialFailure = connectFromUiExpectFailure(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectedMessage = "Authentication failed. Verify the linked identity and try again.",
            expectTrustPrompt = true,
            expectedTrustEndpoint = "$FIXTURE_HOST:$HOST_SSH_PORT",
            timeoutMillis = 35_000,
        )
        assertEquals(SessionConnectionState.FAILED, initialFailure.connectionState)

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.onNodeWithTag("host_edit_$hostId").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_port_field").performTextClearance()
        composeRule.onNodeWithTag("host_port_field").performTextInput(FIXTURE_PORT.toString())
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { container.foundationGraph.hostRepository.getHost(hostId) }?.port == FIXTURE_PORT
        }

        val relaunchedContainer = AppContainer.create(context)
        application.replaceAppContainerForTesting(relaunchedContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        val relaunchedCoordinator = relaunchedContainer.sshSessionCoordinator
        val reconnected = connectFromUi(
            coordinator = relaunchedCoordinator,
            hostId = hostId,
            expectedButtonLabel = "Reconnect",
            expectTrustPrompt = true,
        )
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", reconnected.statusMessage)
        waitForProofText(relaunchedCoordinator, FIXTURE_PORT)

        openSnippetsScreen()
        composeRule.onNodeWithTag("snippet_run_target_$snippetId")
            .assertTextContains("Saved host: Port edit host", substring = true)
        openSnippetExecutionConfirmation(snippetId)
        composeRule.onNodeWithTag("snippet_execution_target")
            .assertTextContains("Saved host: Port edit host", substring = true)
        composeRule.onNodeWithTag("snippet_execution_confirm").performClick()

        waitForTranscriptOutputLine(relaunchedCoordinator, "UPDATED_PORT_SNIPPET_OK")
        waitForSnippetHistoryRecord(
            snippetRepository = relaunchedContainer.foundationGraph.snippetRepository,
            expectedLabel = "Port edit host",
        )
        assertEquals(FIXTURE_PORT, runBlocking { relaunchedContainer.foundationGraph.hostRepository.getHost(hostId) }?.port)
        assertEquals(hostId, runBlocking { relaunchedContainer.foundationGraph.snippetRepository.getSnippet(snippetId) }?.hostId)

        relaunchedCoordinator.disconnect()
        waitForState(relaunchedCoordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
    }

    @Test
    fun imported_key_identity_survives_relaunch_and_reaches_real_terminal_session() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val privateKeyMaterial = File(FIXTURE_CLIENT_PRIVATE_KEY_PATH).readText()
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_identities").performClick()
        composeRule.onNodeWithTag("identity_import_key_action").performClick()
        composeRule.onNodeWithTag("identity_import_name_field").performTextInput("Fixture imported key")
        composeRule.onNodeWithTag("identity_import_key_field").performTextInput(privateKeyMaterial)
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking {
                container.foundationGraph.identityRepository.observeIdentities().first().any { it.kind == IdentityKind.IMPORTED_KEY }
            }
        }
        val importedIdentityId = runBlocking {
            container.foundationGraph.identityRepository.observeIdentities().first().first { it.kind == IdentityKind.IMPORTED_KEY }.id
        }

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Imported key fixture")
        composeRule.onNodeWithTag("host_address_field").performTextInput(FIXTURE_HOST)
        composeRule.onNodeWithTag("host_port_field").performTextClearance()
        composeRule.onNodeWithTag("host_port_field").performTextInput(FIXTURE_PORT.toString())
        composeRule.onNodeWithTag("host_username_field").performTextInput(FIXTURE_USERNAME)
        composeRule.onNodeWithTag("host_auth_mode_key").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("host_identity_ready_summary").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_identity_ready_summary")
            .assertTextContains("1 reusable key identity is ready to choose.", substring = true)
        composeRule.onNodeWithTag("host_identity_option_$importedIdentityId").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("host_identity_option_$importedIdentityId")
                    .performScrollTo()
                    .assertIsSelected()
            }.isSuccess
        }
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking {
                container.foundationGraph.hostRepository.observeHosts().first().any { it.label == "Imported key fixture" }
            }
        }

        val hostId = runBlocking {
            container.foundationGraph.hostRepository.observeHosts().first().first { it.label == "Imported key fixture" }.id
        }
        val relaunchedContainer = AppContainer.create(context)
        application.replaceAppContainerForTesting(relaunchedContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        val relaunchedCoordinator = relaunchedContainer.sshSessionCoordinator
        val connected = connectFromUi(
            coordinator = relaunchedCoordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", connected.statusMessage)
        waitForProofText(relaunchedCoordinator, FIXTURE_PORT)
        sendCommandDirectly(relaunchedCoordinator, "printf 'IMPORTED_KEY_SESSION_OK\\n'")
        waitForTranscriptOutputLine(relaunchedCoordinator, "IMPORTED_KEY_SESSION_OK")
        assertEquals(
            FIXTURE_USERNAME,
            runBlocking { relaunchedContainer.foundationGraph.hostRepository.getHost(hostId) }?.username,
        )

        relaunchedCoordinator.disconnect()
        waitForState(relaunchedCoordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
    }

    @Test
    fun generated_key_identity_survives_relaunch_and_reaches_real_terminal_session() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(
            applicationContext = context,
            generatedKeyIdentityService = FixtureGeneratedKeyIdentityService(),
        )
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_identities").performClick()
        composeRule.onNodeWithTag("identity_generate_key_action").performClick()
        composeRule.onNodeWithTag("identity_import_name_field").performTextInput("Fixture generated key")
        composeRule.onNodeWithTag("identity_generate_execute").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("identity_public_key_preview").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking {
                container.foundationGraph.identityRepository.observeIdentities().first().any { it.kind == IdentityKind.GENERATED_KEY }
            }
        }
        val generatedIdentityId = runBlocking {
            container.foundationGraph.identityRepository.observeIdentities().first().first { it.kind == IdentityKind.GENERATED_KEY }.id
        }

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Generated key fixture")
        composeRule.onNodeWithTag("host_address_field").performTextInput(FIXTURE_HOST)
        composeRule.onNodeWithTag("host_port_field").performTextClearance()
        composeRule.onNodeWithTag("host_port_field").performTextInput(FIXTURE_PORT.toString())
        composeRule.onNodeWithTag("host_username_field").performTextInput(FIXTURE_USERNAME)
        composeRule.onNodeWithTag("host_auth_mode_key").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("host_identity_ready_summary").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_identity_ready_summary")
            .assertTextContains("1 reusable key identity is ready to choose.", substring = true)
        composeRule.onNodeWithTag("host_identity_option_$generatedIdentityId").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("host_identity_option_$generatedIdentityId")
                    .performScrollTo()
                    .assertIsSelected()
            }.isSuccess
        }
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking {
                container.foundationGraph.hostRepository.observeHosts().first().any { it.label == "Generated key fixture" }
            }
        }

        val hostId = runBlocking {
            container.foundationGraph.hostRepository.observeHosts().first().first { it.label == "Generated key fixture" }.id
        }
        val relaunchedContainer = AppContainer.create(context)
        application.replaceAppContainerForTesting(relaunchedContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        val relaunchedCoordinator = relaunchedContainer.sshSessionCoordinator
        val connected = connectFromUi(
            coordinator = relaunchedCoordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", connected.statusMessage)
        waitForProofText(relaunchedCoordinator, FIXTURE_PORT)
        sendCommandDirectly(relaunchedCoordinator, "printf 'GENERATED_KEY_SESSION_OK\\n'")
        waitForTranscriptOutputLine(relaunchedCoordinator, "GENERATED_KEY_SESSION_OK")
        assertEquals(
            FIXTURE_USERNAME,
            runBlocking { relaunchedContainer.foundationGraph.hostRepository.getHost(hostId) }?.username,
        )

        relaunchedCoordinator.disconnect()
        waitForState(relaunchedCoordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
    }

    @Test
    fun real_fixture_session_ui_truthfully_reflects_password_replacement_failures_and_recovery() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        waitForProofText(coordinator, FIXTURE_PORT)
        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        waitForUiDisconnect(coordinator, "Disconnected.")

        replacePasswordIdentityFromUi(
            identityId = identityId,
            replacementPassword = "wrong-password",
        )
        assertEquals(
            "wrong-password",
            runBlocking { container.foundationGraph.identityRepository.getSecretMaterial(identityId) }?.primarySecret,
        )

        composeRule.onNodeWithTag("nav_session").performClick()
        val transcriptBeforeFailure = coordinator.observeUiState().value.transcript
        val authFailed = connectFromUiExpectFailure(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Reconnect",
            expectedMessage = "Authentication failed. Verify the linked identity and try again.",
            expectTrustPrompt = false,
        )
        assertEquals(SessionConnectionState.FAILED, authFailed.connectionState)
        assertEquals(transcriptBeforeFailure, authFailed.transcript)
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Authentication failed. Verify the linked identity and try again.", substring = true)
        composeRule.onNodeWithTag("session_terminal_truth_banner")
            .assertTextContains("Authentication failed. Verify the linked identity and try again.", substring = true)
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_send_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_paste_button").assertIsNotEnabled()

        replacePasswordIdentityFromUi(
            identityId = identityId,
            replacementPassword = FIXTURE_PASSWORD,
        )
        assertEquals(
            FIXTURE_PASSWORD,
            runBlocking { container.foundationGraph.identityRepository.getSecretMaterial(identityId) }?.primarySecret,
        )

        composeRule.onNodeWithTag("nav_session").performClick()
        val recovered = connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Reconnect",
            expectTrustPrompt = false,
        )
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", recovered.statusMessage)
        waitForProofText(coordinator, FIXTURE_PORT)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        waitForUiDisconnect(coordinator, "Disconnected.")
    }

    @Test
    fun changed_key_is_blocked_and_endpoint_edits_do_not_reuse_prior_trust() {
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = buildCoordinator(container)

        runBlocking {
            container.foundationGraph.knownHostTrustRepository.upsert(
                KnownHostTrust(
                    host = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    algorithm = "RSA",
                    fingerprint = "SHA256:wrong-key",
                    hostKeyBase64 = "WRONG_FIXTURE_HOST_KEY",
                ),
            )
        }
        coordinator.connect(hostId)
        val changedKey = waitForState(coordinator) { it.connectionState == SessionConnectionState.FAILED }
        assertEquals(
            "Host key changed for $FIXTURE_HOST:$FIXTURE_PORT. Connection blocked.",
            changedKey.statusMessage,
        )
        assertNull(changedKey.pendingTrustDecision)

        runBlocking {
            container.foundationGraph.knownHostTrustRepository.deleteByEndpoint(FIXTURE_HOST, FIXTURE_PORT)
        }
        coordinator.connect(hostId)
        waitForState(coordinator) { it.pendingTrustDecision != null }
        coordinator.submitHostTrustDecision(true)
        waitForState(coordinator) { it.connectionState == SessionConnectionState.CONNECTED }
        coordinator.disconnect()
        waitForState(coordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }

        runBlocking {
            val savedHost = container.foundationGraph.hostRepository.getHost(hostId)
                ?: error("Fixture host missing")
            container.foundationGraph.hostRepository.upsert(
                savedHost.copy(port = HOST_SSH_PORT),
            )
        }

        coordinator.connect(hostId)
        val endpointPrompt = waitForState(coordinator) { it.pendingTrustDecision != null }
        assertEquals("$FIXTURE_HOST:$HOST_SSH_PORT", endpointPrompt.pendingTrustDecision?.endpoint)
        coordinator.submitHostTrustDecision(false)
        val rejected = waitForState(coordinator) { it.connectionState == SessionConnectionState.FAILED }
        assertEquals("Host trust rejected.", rejected.statusMessage)
        assertFalse(
            runBlocking {
                container.foundationGraph.knownHostTrustRepository.findTrustedHost(FIXTURE_HOST, HOST_SSH_PORT) != null
            },
        )
    }

    @Test
    fun auth_dns_and_unreachable_failures_surface_clearly() {
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Wrong fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = "wrong-password"),
            ).id
        }
        val badPasswordHostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Bad password",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val dnsHostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "DNS failure",
                    address = "nonexistent.invalid",
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val unreachableHostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Unreachable host",
                    address = FIXTURE_HOST,
                    port = 65022,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = buildCoordinator(container)

        coordinator.connect(badPasswordHostId)
        waitForState(coordinator) { it.pendingTrustDecision != null }
        coordinator.submitHostTrustDecision(true)
        val authFailed = waitForState(coordinator) { it.connectionState == SessionConnectionState.FAILED }
        assertEquals(
            "Authentication failed. Verify the linked identity and try again.",
            authFailed.statusMessage,
        )

        coordinator.connect(dnsHostId)
        val dnsFailed = waitForState(coordinator) {
            it.connectionState == SessionConnectionState.FAILED &&
                it.statusMessage?.contains("DNS lookup failed") == true
        }
        assertEquals("DNS lookup failed for nonexistent.invalid:$FIXTURE_PORT.", dnsFailed.statusMessage)

        coordinator.connect(unreachableHostId)
        val networkFailed = waitForState(coordinator) {
            it.connectionState == SessionConnectionState.FAILED &&
                it.statusMessage?.contains("Host is unreachable") == true
        }
        assertEquals("Host is unreachable at $FIXTURE_HOST:65022.", networkFailed.statusMessage)
    }

    @Test
    fun timeout_reproducer_stalls_at_auth_and_surfaces_timeout_cleanly() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Timeout fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val timeoutHostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Timeout repro",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_TIMEOUT_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = buildCoordinator(container)

        coordinator.connect(timeoutHostId)
        waitForState(coordinator) { it.pendingTrustDecision != null }
        coordinator.submitHostTrustDecision(true)
        val timedOut = waitForState(coordinator, timeoutMillis = 35_000) {
            it.connectionState == SessionConnectionState.FAILED &&
                it.statusMessage?.contains("Connection timed out while reaching") == true
        }
        assertEquals(
            "Connection timed out while reaching $FIXTURE_HOST:$FIXTURE_PORT.",
            timedOut.statusMessage,
        )
        assertNull(timedOut.pendingTrustDecision)
        assertFalse(timedOut.canSendInput)
        assertTrue(timedOut.transcript.isEmpty())
    }

    @Test
    fun real_session_ui_surfaces_timeout_distinctly_from_other_failure_messages() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val timeoutHostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Timeout repro",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_TIMEOUT_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()

        val dnsFailureMessage = "DNS lookup failed for nonexistent.invalid:$FIXTURE_PORT."
        val unreachableFailureMessage = "Host is unreachable at $FIXTURE_HOST:65022."
        val timeoutFailure = connectFromUiExpectFailure(
            coordinator = coordinator,
            hostId = timeoutHostId,
            expectedButtonLabel = "Connect",
            expectedMessage = "Connection timed out while reaching $FIXTURE_HOST:$FIXTURE_PORT.",
            expectTrustPrompt = true,
            expectedTrustEndpoint = "$FIXTURE_HOST:$FIXTURE_PORT",
            timeoutMillis = 35_000,
        )

        assertNotEquals(dnsFailureMessage, timeoutFailure.statusMessage)
        assertNotEquals(unreachableFailureMessage, timeoutFailure.statusMessage)
        assertFalse(timeoutFailure.isConnecting)
        assertFalse(timeoutFailure.isConnected)
        assertFalse(timeoutFailure.canSendInput)
        assertNull(timeoutFailure.pendingTrustDecision)
        assertTrue(timeoutFailure.transcript.isEmpty())

        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connection timed out while reaching $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        composeRule.onAllNodesWithTag("session_terminal_truth_banner").assertCountEquals(1)
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_send_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_paste_button").assertIsNotEnabled()
        composeRule.onAllNodesWithTag("session_disconnect_button").assertCountEquals(0)
        scrollSessionHostIntoView(timeoutHostId)
        composeRule.onNodeWithTag("session_connect_$timeoutHostId")
            .assertTextContains("Reconnect", substring = true)
    }

    @Test
    fun canceling_during_real_connect_prompt_leaves_one_clean_disconnected_state() {
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = buildCoordinator(container)

        coordinator.connect(hostId)
        val prompting = waitForState(coordinator) {
            it.connectionState == SessionConnectionState.CONNECTING && it.pendingTrustDecision != null
        }
        assertEquals("$FIXTURE_HOST:$FIXTURE_PORT", prompting.pendingTrustDecision?.endpoint)

        coordinator.connect(hostId)
        coordinator.disconnect()

        val canceled = waitForState(coordinator) {
            it.connectionState == SessionConnectionState.DISCONNECTED && it.statusMessage == "Connection canceled."
        }
        assertNull(canceled.pendingTrustDecision)
        assertEquals("", canceled.transcript)
        assertEquals(
            0,
            runBlocking { container.foundationGraph.knownHostTrustRepository.observeTrustedHosts().first().size },
        )

        runBlocking { delay(1_000) }
        val settled = coordinator.observeUiState().value
        assertEquals(SessionConnectionState.DISCONNECTED, settled.connectionState)
        assertEquals("Connection canceled.", settled.statusMessage)
        assertTrue(settled.transcript.isEmpty())
    }

    @Test
    fun rotating_during_real_connect_setup_keeps_one_truthful_connected_state() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        try {
            composeRule.onNodeWithTag("nav_session").performClick()
            tapConnectFromUi(hostId = hostId, expectedButtonLabel = "Connect")
            rotateSessionActivity(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            composeRule.onNodeWithTag("nav_session").performClick()
            waitForConnectingTrustPrompt(
                coordinator = coordinator,
                hostId = hostId,
                expectedTrustEndpoint = "$FIXTURE_HOST:$FIXTURE_PORT",
            )
            composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(1)
            composeRule.onAllNodesWithTag("session_disconnect_button").assertCountEquals(1)
            scrollSessionHostIntoView(hostId)
            composeRule.onNodeWithTag("session_connect_$hostId")
                .assertIsNotEnabled()
                .assertTextContains("Connecting…", substring = true)

            clickTaggedButton("session_trust_accept")
            val connected = waitForState(coordinator, timeoutMillis = 30_000) {
                it.activeHostId == hostId && it.connectionState == SessionConnectionState.CONNECTED
            }
            assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", connected.statusMessage)
            composeRule.onNodeWithTag("session_status_message")
                .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
            composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
            composeRule.onAllNodesWithTag("session_disconnect_button").assertCountEquals(1)
            waitForProofText(coordinator, FIXTURE_PORT)
            coordinator.disconnect()
            waitForUiDisconnect(coordinator, "Disconnected.")
        } finally {
            rotateSessionActivity(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        }
    }

    @Test
    fun backgrounding_and_resuming_during_real_connect_setup_keeps_one_truthful_connected_state() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        tapConnectFromUi(hostId = hostId, expectedButtonLabel = "Connect")
        backgroundAndResumeApp()
        waitForConnectingTrustPrompt(
            coordinator = coordinator,
            hostId = hostId,
            expectedTrustEndpoint = "$FIXTURE_HOST:$FIXTURE_PORT",
        )
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(1)
        composeRule.onAllNodesWithTag("session_disconnect_button").assertCountEquals(1)
        scrollSessionHostIntoView(hostId)
        composeRule.onNodeWithTag("session_connect_$hostId")
            .assertIsNotEnabled()
            .assertTextContains("Connecting…", substring = true)

        clickTaggedButton("session_trust_accept")
        val connected = waitForState(coordinator, timeoutMillis = 30_000) {
            it.activeHostId == hostId && it.connectionState == SessionConnectionState.CONNECTED
        }
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", connected.statusMessage)
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
        composeRule.onAllNodesWithTag("session_disconnect_button").assertCountEquals(1)
        waitForProofText(coordinator, FIXTURE_PORT)
        coordinator.disconnect()
        waitForUiDisconnect(coordinator, "Disconnected.")
    }

    @Test
    fun fixture_orchestration_handles_ime_rotation_background_and_relaunch_with_real_app_shell() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        deviceOrchestrator.openSessionScreen(appPackage = APP_PACKAGE)
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        logStep("device orchestration: connected", coordinator)
        waitForProofText(coordinator, FIXTURE_PORT)

        deviceOrchestrator.showImeForTaggedField("session_input_field")
        assertTrue(
            "Expected the IME to be visible after focusing terminal input.",
            deviceOrchestrator.isImeVisible(),
        )
        logStep("device orchestration: ime visible", coordinator)

        val beforeRotation = waitForTerminalSizeMarker(
            coordinator = coordinator,
            marker = captureRemoteTerminalSize(coordinator, "SIZE_ORCH_BEFORE_ROTATION"),
        )
        Log.i("SessionSshFixtureTest", "device orchestration: size before rotation=$beforeRotation")
        deviceOrchestrator.rotateAndWaitForAppShell(
            orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            appPackage = APP_PACKAGE,
        )
        deviceOrchestrator.openSessionScreen(appPackage = APP_PACKAGE)
        logStep("device orchestration: reopened after rotation", coordinator)
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        waitForProofText(coordinator, FIXTURE_PORT)
        val afterRotation = waitForTerminalSizeMarker(
            coordinator = coordinator,
            marker = captureRemoteTerminalSize(coordinator, "SIZE_ORCH_AFTER_ROTATION"),
        )
        Log.i("SessionSshFixtureTest", "device orchestration: size after rotation=$afterRotation")
        assertNotEquals("Expected device rotation to change the PTY size.", beforeRotation, afterRotation)

        deviceOrchestrator.backgroundAndResumeApp(appPackage = APP_PACKAGE)
        deviceOrchestrator.openSessionScreen(appPackage = APP_PACKAGE)
        logStep("device orchestration: reopened after background", coordinator)
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        waitForProofText(coordinator, FIXTURE_PORT)
        sendCommandDirectly(coordinator, "printf 'ORCHESTRATION_BACKGROUND_RESUME_OK\\n'")
        waitForTranscriptOutputLine(coordinator, "ORCHESTRATION_BACKGROUND_RESUME_OK")
        logStep("device orchestration: background proof complete", coordinator)

        deviceOrchestrator.recreateActivityAndWaitForAppShell(appPackage = APP_PACKAGE)
        deviceOrchestrator.openSessionScreen(appPackage = APP_PACKAGE)
        logStep("device orchestration: reopened after relaunch", coordinator)
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        waitForProofText(coordinator, FIXTURE_PORT)
        composeRule.onNodeWithTag("session_input_field").performScrollTo()
        composeRule.onAllNodesWithTag("session_input_field").assertCountEquals(1)
        composeRule.onNodeWithTag("session_send_button").performScrollTo()
        composeRule.onAllNodesWithTag("session_send_button").assertCountEquals(1)

        coordinator.disconnect()
        waitForUiDisconnect(coordinator, "Disconnected.")
        deviceOrchestrator.rotateAndWaitForAppShell(
            orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            appPackage = APP_PACKAGE,
        )
    }

    @Test
    fun disconnect_truthfully_blocks_live_input_through_real_session_ui() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        waitForProofText(coordinator, FIXTURE_PORT)
        composeRule.onNodeWithTag("session_disconnect_button").performClick()

        val disconnected = waitForState(coordinator, timeoutMillis = 15_000) {
            it.connectionState == SessionConnectionState.DISCONNECTED && !it.canSendInput
        }
        assertFalse(disconnected.reconnectRequired)
        assertTrue(disconnected.transcript.contains("ATERM_REMOTE_PROOF:$FIXTURE_HOST:$FIXTURE_PORT:"))
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Disconnected.", substring = true)
        composeRule.onNodeWithTag("session_terminal_truth_banner")
            .assertTextContains("Disconnected.", substring = true)
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_send_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_paste_button").assertIsNotEnabled()
    }

    @Test
    fun unexpected_fixture_disconnect_trigger_truthfully_requires_reconnect_through_visible_terminal_ui() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )
        waitForProofText(coordinator, FIXTURE_PORT)

        triggerUnexpectedFixtureDisconnect()

        val disconnected = waitForState(coordinator, timeoutMillis = 20_000) {
            it.connectionState == SessionConnectionState.RECONNECT_REQUIRED &&
                it.reconnectRequired &&
                !it.canSendInput
        }
        assertTrue(disconnected.transcript.contains("ATERM_REMOTE_PROOF:$FIXTURE_HOST:$FIXTURE_PORT:"))
        assertEquals(
            "Remote shell closed for $FIXTURE_HOST:$FIXTURE_PORT.",
            disconnected.statusMessage,
        )
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Remote shell closed for $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        composeRule.onNodeWithTag("session_reconnect_required")
            .assertTextContains("Remote shell closed for $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        composeRule.onNodeWithTag("session_terminal_truth_banner")
            .assertTextContains("Remote shell closed for $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        composeRule.onNodeWithTag("session_connect_$hostId")
            .assertTextContains("Reconnect", substring = true)
        composeRule.onAllNodesWithTag("session_disconnect_button").assertCountEquals(0)
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_send_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_paste_button").assertIsNotEnabled()
    }

    @Test
    fun real_fixture_terminal_surface_proves_no_local_echo_paste_resize_and_full_screen_state() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )

        logStep("starting no-local-echo phase", coordinator)
        sendCommandDirectly(coordinator, "stty -echo; printf 'ECHO_DISABLED\\n'")
        waitForTranscriptOutputLine(coordinator, "ECHO_DISABLED")
        sendCommandDirectly(coordinator, "sleep 1; printf 'NO_LOCAL_ECHO_PROOF\\n'")
        runBlocking { delay(200) }
        assertFalse(coordinator.observeUiState().value.transcript.contains("NO_LOCAL_ECHO_PROOF"))
        waitForTranscriptOutputLine(coordinator, "NO_LOCAL_ECHO_PROOF")
        sendCommandDirectly(coordinator, "stty echo; printf 'ECHO_RESTORED\\n'")
        waitForTranscriptOutputLine(coordinator, "ECHO_RESTORED")

        logStep("starting special-key phase", coordinator)
        sendCommandDirectly(
            coordinator,
            "oldstty=\$(stty -g); stty raw -echo; printf 'TAB_READY\\n'; dd bs=1 count=1 2>/dev/null | od -An -t u1; stty \"\$oldstty\"",
        )
        waitForTranscriptOutputLine(coordinator, "TAB_READY")
        logStep("tab ready observed", coordinator)
        clickTaggedButton("session_special_key_Tab")
        waitForTranscriptOutputLine(coordinator, "9")
        logStep("tab byte observed", coordinator)

        sendCommandDirectly(
            coordinator,
            "oldstty=\$(stty -g); stty raw -echo; printf 'ARROW_READY\\n'; dd bs=1 count=3 2>/dev/null | od -An -t u1; stty \"\$oldstty\"",
        )
        waitForTranscriptOutputLine(coordinator, "ARROW_READY")
        logStep("arrow ready observed", coordinator)
        clickTaggedButton("session_special_key_ArrowUp")
        waitForTranscriptRegex(coordinator, Regex("""27\s+91\s+65"""))
        logStep("arrow bytes observed", coordinator)

        sendCommandDirectly(
            coordinator,
            "python3 -c \"import signal,sys,time; print('CTRL_C_READY', flush=True); signal.signal(signal.SIGINT, lambda signum, frame: (print('CTRL_C_RECOVERED', flush=True), sys.exit(0))); time.sleep(10)\"",
        )
        waitForTranscriptOutputLine(coordinator, "CTRL_C_READY")
        runBlocking { delay(300) }
        clickTaggedButton("session_special_key_CtrlC")
        waitForTranscriptOutputLine(coordinator, "CTRL_C_RECOVERED")
        logStep("ctrl-c recovery observed", coordinator)

        logStep("starting paste phase", coordinator)
        val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val pasteCommand = "printf 'PASTE_PROOF\\n'\n"
        composeRule.runOnUiThread {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("aterm-terminal", pasteCommand))
        }
        clickTaggedButton("session_paste_button")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            coordinator.observeUiState().value.transcript.containsOutputLine("PASTE_PROOF") ||
                composeRule.onAllNodesWithTag("session_clipboard_status").fetchSemanticsNodes().isNotEmpty()
        }
        if (!coordinator.observeUiState().value.transcript.containsOutputLine("PASTE_PROOF")) {
            clickTaggedButton("session_paste_button")
        }
        waitForTranscriptOutputLine(coordinator, "PASTE_PROOF")

        logStep("starting fullscreen phase", coordinator)
        sendCommandDirectly(
            coordinator,
            "printf '\\033[?1049h\\033[2J\\033[HFULL_SCREEN_PROOF'; sleep 1; printf '\\033[?1049l\\nFULL_SCREEN_DONE\\n'",
        )
        waitForAlternateScreenState(coordinator, expected = true)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            currentTerminalStatusText().contains("full-screen program active")
        }
        waitForAlternateScreenState(coordinator, expected = false)
        waitForTranscriptOutputLine(coordinator, "FULL_SCREEN_DONE")

        logStep("starting resize phase", coordinator)
        sendCommandDirectly(
            coordinator,
            "read rows cols < <(stty size); printf 'SIZE_BEFORE:%s %s:END\\n' \"\$rows\" \"\$cols\"",
        )
        val sizeBefore = waitForTerminalSizeMarker(coordinator, "SIZE_BEFORE")
        val terminalMetrics = waitForTerminalMetrics()
        val resizedViewport = calculateTerminalViewport(
            contentWidthPx = (sizeBefore.second - 7).coerceAtLeast(8) * terminalMetrics.first,
            contentHeightPx = (sizeBefore.first - 4).coerceAtLeast(6) * terminalMetrics.second,
            cellWidthPx = terminalMetrics.first,
            cellHeightPx = terminalMetrics.second,
        ) ?: error("Expected a non-zero terminal viewport for resize proof.")
        coordinator.resize(resizedViewport)

        sendCommandDirectly(
            coordinator,
            "read rows cols < <(stty size); printf 'SIZE_AFTER:%s %s:END\\n' \"\$rows\" \"\$cols\"",
        )
        val sizeAfter = waitForTerminalSizeMarker(coordinator, "SIZE_AFTER")
        assertTrue(
            "Expected PTY size to change after live terminal resize, but before=$sizeBefore after=$sizeAfter",
            sizeBefore != sizeAfter,
        )
        assertEquals(resizedViewport.rows to resizedViewport.columns, sizeAfter)
    }

    @Test
    fun real_fixture_terminal_ui_proves_visible_input_scrollback_live_bottom_copy_and_multiline_paste() {
        assertFixtureReachableFromHost()
        val container = AppContainer.create(context)
        val identityId = runBlocking {
            container.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            ).id
        }
        val hostId = runBlocking {
            container.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = identityId,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ).id
        }
        val coordinator = container.sshSessionCoordinator
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        connectFromUi(
            coordinator = coordinator,
            hostId = hostId,
            expectedButtonLabel = "Connect",
            expectTrustPrompt = true,
        )

        val typedInputCommand = "printf 'VISIBLE_INPUT_UI_PROOF\\n'"
        logStep("ui terminal proof: sending typed input", coordinator)
        sendCommandFromUi(typedInputCommand)
        waitForTranscriptOutputLine(coordinator, "VISIBLE_INPUT_UI_PROOF")
        waitForCommandEchoBeforeOutput(
            coordinator = coordinator,
            echoedCommand = typedInputCommand,
            outputLine = "VISIBLE_INPUT_UI_PROOF",
        )

        val longOutputCommand =
            "i=1; while [ \$i -le 48 ]; do printf 'SCROLL_LINE_%02d\\n' \"\$i\"; i=\$((i+1)); done; " +
                "printf 'MULTILINE_ALPHA\\nMULTILINE_BETA\\nMULTILINE_GAMMA\\n'; " +
                "sleep 1; printf 'LIVE_BOTTOM_RESUME_PROOF\\n'"
        logStep("ui terminal proof: sending long output", coordinator)
        sendCommandFromUi(longOutputCommand)
        waitForTranscriptOutputLine(coordinator, "SCROLL_LINE_48")
        waitForTranscriptOutputLine(coordinator, "MULTILINE_GAMMA")

        logStep("ui terminal proof: scrolling history", coordinator)
        scrollTerminalHistoryUntilVisible(coordinator, "SCROLL_LINE_01")
        composeRule.onNodeWithTag("session_terminal_status")
            .assertTextContains("viewing history", substring = true)

        logStep("ui terminal proof: staying in history while live output arrives", coordinator)
        runBlocking { delay(1_500) }
        composeRule.onNodeWithTag("session_terminal_status")
            .assertTextContains("viewing history", substring = true)

        logStep("ui terminal proof: jumping back to live bottom", coordinator)
        clickTaggedButton("session_jump_to_live")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            !currentTerminalStatusText().contains("viewing history")
        }
        waitForTranscriptOutputLine(coordinator, "LIVE_BOTTOM_RESUME_PROOF")

        logStep("ui terminal proof: copying transcript", coordinator)
        clickTaggedButton("session_copy_button")
        composeRule.onNodeWithTag("session_clipboard_status")
            .assertTextContains("Copied terminal output", substring = true)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            currentClipboardText()?.contains("LIVE_BOTTOM_RESUME_PROOF") == true
        }
        val copiedTerminalText = currentClipboardText()
            ?: error("Expected terminal copy to populate the clipboard.")
        assertTrue(copiedTerminalText.contains("SCROLL_LINE_01"))
        assertTrue(copiedTerminalText.contains("MULTILINE_ALPHA"))
        assertTrue(copiedTerminalText.contains("LIVE_BOTTOM_RESUME_PROOF"))

        val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val multilinePasteCommand = """
            printf 'PASTE_VISIBLE_LINE_1\n'
            printf 'PASTE_VISIBLE_LINE_2\n'
            printf 'PASTE_VISIBLE_LINE_3\n'
        """.trimIndent() + "\n"
        composeRule.runOnUiThread {
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("aterm-terminal", multilinePasteCommand),
            )
        }
        logStep("ui terminal proof: pasting multiline clipboard", coordinator)
        clickTaggedButton("session_paste_button")
        composeRule.onNodeWithTag("session_clipboard_status")
            .assertTextContains("Pasted from clipboard", substring = true)
        waitForTranscriptOutputLine(coordinator, "PASTE_VISIBLE_LINE_3")
        waitForTranscriptOutputLinesInOrder(
            coordinator = coordinator,
            expectedLines = listOf(
                "PASTE_VISIBLE_LINE_1",
                "PASTE_VISIBLE_LINE_2",
                "PASTE_VISIBLE_LINE_3",
            ),
        )
    }

    private fun buildCoordinator(container: AppContainer): SshSessionCoordinator = SshSessionCoordinator(
        hostRepository = container.foundationGraph.hostRepository,
        identityRepository = container.foundationGraph.identityRepository,
        knownHostTrustRepository = container.foundationGraph.knownHostTrustRepository,
    )

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

    private fun assertProof(state: SessionUiState, port: Int) {
        val transcript = state.transcript
        check(transcript.containsRemoteProofLine(host = FIXTURE_HOST, port = port)) {
            "Transcript did not contain remote proof output for $FIXTURE_HOST:$port: ${transcript.takeLast(400)}"
        }
    }

    private fun assertProofEventually(
        coordinator: SshSessionCoordinator,
        port: Int,
    ) {
        val state = waitForState(coordinator, timeoutMillis = 30_000) {
            it.transcript.containsRemoteProofLine(host = FIXTURE_HOST, port = port)
        }
        assertProof(state, port)
    }

    private fun waitForProofText(
        coordinator: SshSessionCoordinator,
        port: Int,
    ) {
        val state = waitForState(coordinator, timeoutMillis = 30_000) {
            it.transcript.containsRemoteProofReadyState(host = FIXTURE_HOST, port = port)
        }
        check(state.transcript.containsRemoteProofReadyState(host = FIXTURE_HOST, port = port)) {
            "Transcript did not reach proof-ready state for $FIXTURE_HOST:$port: ${state.transcript.takeLast(400)}"
        }
    }

    private fun waitForTranscriptSubstring(
        coordinator: SshSessionCoordinator,
        expected: String,
        timeoutMillis: Long = 30_000L,
    ) {
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            it.transcript.contains(expected)
        }
        check(state.transcript.contains(expected)) {
            "Transcript did not contain \"$expected\": ${state.transcript.takeLast(400)}"
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_transcript")
                    .assertTextContains(expected, substring = true)
            }.isSuccess
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

    private fun waitForTranscriptRegex(
        coordinator: SshSessionCoordinator,
        regex: Regex,
        timeoutMillis: Long = 30_000L,
    ) {
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            regex.containsMatchIn(it.transcript)
        }
        check(regex.containsMatchIn(state.transcript)) {
            "Transcript did not contain regex ${regex.pattern}: ${state.transcript.takeLast(400)}"
        }
    }

    private fun waitForTerminalSizeMarker(
        coordinator: SshSessionCoordinator,
        marker: String,
    ): Pair<Int, Int> {
        val regex = Regex("$marker:(\\d+)\\s+(\\d+):END")
        val state = waitForState(coordinator) {
            regex.containsMatchIn(it.transcript)
        }
        val match = regex.find(state.transcript)
            ?: error("Transcript did not contain size marker $marker: ${state.transcript.takeLast(400)}")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun waitForAlternateScreenState(
        coordinator: SshSessionCoordinator,
        expected: Boolean,
        timeoutMillis: Long = 30_000L,
    ) {
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            it.liveTerminalState.snapshot.alternateScreenActive == expected
        }
        check(state.liveTerminalState.snapshot.alternateScreenActive == expected) {
            "Expected alternateScreenActive=$expected but was ${state.liveTerminalState.snapshot.alternateScreenActive}"
        }
    }

    private fun waitForTerminalMetrics(): Pair<Int, Int> {
        val regex = Regex("Terminal cell: (\\d+)×(\\d+)px")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            regex.containsMatchIn(currentTerminalMetricsText())
        }
        val text = currentTerminalMetricsText()
        val match = regex.find(text) ?: error("Missing terminal metrics text: $text")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun waitForVisibleTerminalLine(
        coordinator: SshSessionCoordinator,
        expected: String,
        timeoutMillis: Long = 10_000L,
    ) {
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            isVisibleTerminalLine(it, expected)
        }
        check(isVisibleTerminalLine(state, expected)) {
            "Expected visible terminal line \"$expected\", but visible lines were: ${state.liveTerminalState.snapshot.visibleLines.map(::sanitizeTerminalLine)}"
        }
    }

    private fun isVisibleTerminalLine(
        state: SessionUiState,
        expected: String,
    ): Boolean = state.liveTerminalState.snapshot.visibleLines
        .map(::sanitizeTerminalLine)
        .any { line -> line.contains(expected) }

    private fun scrollTerminalHistoryUntilVisible(
        coordinator: SshSessionCoordinator,
        expected: String,
        maxPageUps: Int = 12,
    ) {
        repeat(maxPageUps) {
            if (isVisibleTerminalLine(coordinator.observeUiState().value, expected)) {
                return
            }
            clickTaggedButton("session_scrollback_up")
            composeRule.waitForIdle()
        }
        waitForVisibleTerminalLine(coordinator, expected)
    }

    private fun currentTerminalMetricsText(): String = composeRule.onNodeWithTag("session_terminal_metrics")
        .fetchSemanticsNode()
        .config
        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        .joinToString(separator = "") { it.text }

    private fun currentTerminalStatusText(): String = composeRule.onNodeWithTag("session_terminal_status")
        .fetchSemanticsNode()
        .config
        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        .joinToString(separator = "") { it.text }

    private fun tapConnectFromUi(
        hostId: Long,
        expectedButtonLabel: String,
    ) {
        val connectButtonTag = "session_connect_$hostId"
        scrollSessionHostIntoView(hostId)
        composeRule.onNodeWithTag(connectButtonTag).performScrollTo()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag(connectButtonTag)
                    .assertIsDisplayed()
                    .assertTextContains(expectedButtonLabel, substring = true)
            }.isSuccess
        }
        composeRule.onNodeWithTag(connectButtonTag).performClick()
    }

    private fun waitForConnectingTrustPrompt(
        coordinator: SshSessionCoordinator,
        hostId: Long,
        expectedTrustEndpoint: String,
    ) {
        val state = waitForState(coordinator, timeoutMillis = 20_000) {
            it.activeHostId == hostId &&
                it.connectionState == SessionConnectionState.CONNECTING &&
                it.pendingTrustDecision?.endpoint == expectedTrustEndpoint
        }
        assertEquals(SessionConnectionState.CONNECTING, state.connectionState)
        assertEquals(expectedTrustEndpoint, state.pendingTrustDecision?.endpoint)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("session_trust_prompt").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("session_trust_endpoint").assertCountEquals(1)
        composeRule.onAllNodesWithTag("session_state_label").assertCountEquals(1)
        composeRule.onAllNodesWithTag("session_status_message").assertCountEquals(1)
    }

    private fun sendCommandFromUi(command: String) {
        composeRule.onNodeWithTag("session_input_field").performTextClearance()
        composeRule.onNodeWithTag("session_input_field").performTextInput(command)
        composeRule.onNodeWithTag("session_send_button").performClick()
    }

    private fun clickTaggedButton(testTag: String) {
        composeRule.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun sendCommandDirectly(
        coordinator: SshSessionCoordinator,
        command: String,
    ) {
        coordinator.sendText("$command\n")
    }

    private fun logStep(
        label: String,
        coordinator: SshSessionCoordinator,
    ) {
        Log.i("SessionSshFixtureTest", "$label :: ${coordinator.observeUiState().value.transcript.takeLast(300)}")
    }

    private fun waitForCommandEchoBeforeOutput(
        coordinator: SshSessionCoordinator,
        echoedCommand: String,
        outputLine: String,
        timeoutMillis: Long = 30_000L,
    ) {
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            val lines = sanitizedTranscriptLines(it.transcript)
            val echoedIndex = lines.indexOfFirst { line -> line.contains(echoedCommand) }
            val outputIndex = lines.indexOfFirst { line -> line == outputLine }
            echoedIndex >= 0 && outputIndex > echoedIndex
        }
        val lines = sanitizedTranscriptLines(state.transcript)
        val echoedIndex = lines.indexOfFirst { line -> line.contains(echoedCommand) }
        val outputIndex = lines.indexOfFirst { line -> line == outputLine }
        check(echoedIndex >= 0 && outputIndex > echoedIndex) {
            "Expected echoed command \"$echoedCommand\" before output \"$outputLine\", but transcript lines were: ${lines.takeLast(40)}"
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

    private fun currentClipboardText(): String? {
        val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(composeRule.activity)
            ?.toString()
    }

    private fun connectFromUi(
        coordinator: SshSessionCoordinator,
        hostId: Long,
        expectedButtonLabel: String,
        expectTrustPrompt: Boolean,
    ): SessionUiState {
        val connectButtonTag = "session_connect_$hostId"
        val initialState = coordinator.observeUiState().value
        scrollSessionHostIntoView(hostId)
        composeRule.onNodeWithTag(connectButtonTag).performScrollTo()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag(connectButtonTag)
                    .assertIsDisplayed()
                    .assertTextContains(expectedButtonLabel, substring = true)
            }.isSuccess
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
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag("session_trust_accept").fetchSemanticsNodes().isNotEmpty()
            }
            clickTaggedButton("session_trust_accept")
            waitForState(coordinator, timeoutMillis = 10_000) { it.pendingTrustDecision == null }
            composeRule.waitForIdle()
        }
        val state = waitForState(coordinator, timeoutMillis = 30_000) {
            it.activeHostId == hostId && it.connectionState == SessionConnectionState.CONNECTED
        }
        check(state.connectionState == SessionConnectionState.CONNECTED) {
            "Expected connected UI session for $FIXTURE_HOST:$FIXTURE_PORT but was ${state.connectionState} with status=${state.statusMessage} transcript=${state.transcript.take(200)}"
        }
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        assertProofEventually(coordinator, FIXTURE_PORT)
        return state
    }

    private fun connectFromUiExpectFailure(
        coordinator: SshSessionCoordinator,
        hostId: Long,
        expectedButtonLabel: String,
        expectedMessage: String,
        expectTrustPrompt: Boolean,
        expectedTrustEndpoint: String? = null,
        timeoutMillis: Long = 10_000L,
    ): SessionUiState {
        val connectButtonTag = "session_connect_$hostId"
        val initialState = coordinator.observeUiState().value
        scrollSessionHostIntoView(hostId)
        composeRule.onNodeWithTag(connectButtonTag).performScrollTo()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag(connectButtonTag)
                    .assertIsDisplayed()
                    .assertTextContains(expectedButtonLabel, substring = true)
            }.isSuccess
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
            expectedTrustEndpoint?.let { endpoint ->
                composeRule.onNodeWithTag("session_trust_endpoint")
                    .assertTextContains(endpoint, substring = true)
            }
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag("session_trust_accept").fetchSemanticsNodes().isNotEmpty()
            }
            clickTaggedButton("session_trust_accept")
            waitForState(coordinator, timeoutMillis = 10_000) { it.pendingTrustDecision == null }
            composeRule.waitForIdle()
        }
        val state = waitForState(coordinator, timeoutMillis = timeoutMillis) {
            it.activeHostId == hostId && it.connectionState == SessionConnectionState.FAILED
        }
        assertEquals(expectedMessage, state.statusMessage)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains(expectedMessage, substring = true)
            }.isSuccess
        }
        composeRule.onNodeWithTag(connectButtonTag).assertTextContains("Reconnect", substring = true)
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

    private fun replacePasswordIdentityFromUi(
        identityId: Long,
        replacementPassword: String,
    ) {
        composeRule.onNodeWithTag("nav_identities").performClick()
        composeRule.onNodeWithTag("identity_edit_$identityId").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("identity_password_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("identity_password_field").performTextClearance()
        composeRule.onNodeWithTag("identity_password_field").performTextInput(replacementPassword)
        composeRule.onNodeWithTag("identity_editor_save").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("identity_password_editor").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("screen_identities").assertIsDisplayed()
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
        runBlocking { delay(250) }
    }

    private fun openSnippetsScreen() {
        deviceOrchestrator.waitForAppShell(APP_PACKAGE)
        composeRule.onNodeWithTag("nav_snippets").performClick()
        waitForSnippetsScreen()
    }

    private fun waitForSnippetsScreen() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("screen_snippets").assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun openSnippetExecutionConfirmation(snippetId: Long) {
        composeRule.onNodeWithTag("snippet_run_$snippetId").performScrollTo().performClick()
        waitForSnippetExecutionConfirmation()
    }

    private fun waitForSnippetExecutionConfirmation() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("snippet_execution_confirmation").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun waitForSnippetHistoryRecord(
        snippetRepository: io.github.jtsang4.aterm.core.domain.repository.SnippetRepository,
        expectedLabel: String,
    ) {
        val reached = runCatching {
            runBlocking {
                withTimeout(10_000L) {
                    while (true) {
                        if (snippetRepository.observeExecutionHistory().first().any { entry ->
                                entry.targetLabel == expectedLabel
                            }
                        ) {
                            return@withTimeout
                        }
                        delay(100)
                    }
                }
            }
            true
        }.getOrDefault(false)
        if (!reached) {
            val labels = runBlocking {
                snippetRepository.observeExecutionHistory().first().map { it.targetLabel }
            }
            error(
                "Expected snippet history record for \"$expectedLabel\" but saw ${labels.joinToString(prefix = "[", postfix = "]")}.",
            )
        }
    }

    private fun rotateSessionActivity(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = orientation
        }
        composeRule.waitForIdle()
    }

    private fun backgroundAndResumeApp() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.moveTaskToBack(true)
        }
        runBlocking { delay(500) }
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("app_shell").assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun captureRemoteTerminalSize(
        coordinator: SshSessionCoordinator,
        marker: String,
    ): String {
        sendCommandDirectly(
            coordinator,
            "read rows cols < <(stty size); printf '$marker:%s %s:END\\n' \"\$rows\" \"\$cols\"",
        )
        return marker
    }

    private fun assertFixtureReachableFromHost() {
        Socket(FIXTURE_HOST, FIXTURE_PORT).use { socket ->
            check(socket.isConnected) { "Fixture socket did not connect to $FIXTURE_HOST:$FIXTURE_PORT" }
        }
    }

    private fun triggerUnexpectedFixtureDisconnect() {
        sendCommandFromUi(FIXTURE_DISCONNECT_COMMAND)
    }

    private companion object {
        const val FIXTURE_HOST = "10.0.2.2"
        const val FIXTURE_PORT = 3122
        const val HOST_SSH_PORT = 22
        const val FIXTURE_USERNAME = "atermtester"
        const val FIXTURE_TIMEOUT_USERNAME = "atermtimeout"
        const val FIXTURE_PASSWORD = "aterm-password-fixture"
        const val FIXTURE_DISCONNECT_COMMAND = "aterm-fixture-disconnect"
        const val FIXTURE_CLIENT_PRIVATE_KEY_PATH = "/data/local/tmp/aterm-fixture-client_key"
        const val APP_PACKAGE = "io.github.jtsang4.aterm"
    }
}

private class FixtureGeneratedKeyIdentityService : GeneratedKeyIdentityService() {
    override fun generate(): GeneratedKeyMaterial = GeneratedKeyMaterial(
        privateKeyMaterial = File("/data/local/tmp/aterm-fixture-client_key").readText(),
        publicKey = File("/data/local/tmp/aterm-fixture-client_key.pub").readText().trim(),
    )
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
