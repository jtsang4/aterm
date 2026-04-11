package io.github.jtsang4.aterm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.ssh.SessionUiState
import io.github.jtsang4.aterm.core.ssh.SshSessionCoordinator
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.hosts.HostsScreen
import io.github.jtsang4.aterm.feature.identities.IdentitiesScreen
import io.github.jtsang4.aterm.feature.session.SessionsScreen
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionSshFixtureInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var displayedContainer: MutableState<AppContainer>
    private lateinit var displayedCoordinator: MutableState<SshSessionCoordinator>
    private lateinit var displayedSurface: MutableState<FixtureSurface>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("aterm.db")
        File(context.filesDir.parentFile, "datastore").deleteRecursively()
    }

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
    }

    @Test
    fun real_fixture_session_ui_proves_trust_identity_edit_and_relaunch_through_visible_app_flows() {
        val firstContainer = AppContainer.create(context)
        runBlocking {
            firstContainer.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Fixture password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
            )
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    label = "SSH fixture",
                    address = FIXTURE_HOST,
                    port = FIXTURE_PORT,
                    username = FIXTURE_USERNAME,
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            )
        }
        val firstCoordinator = buildCoordinator(firstContainer)
        displayedContainer = mutableStateOf(firstContainer)
        displayedCoordinator = mutableStateOf(firstCoordinator)
        displayedSurface = mutableStateOf(FixtureSurface.IDENTITIES)

        composeRule.setContent {
            when (displayedSurface.value) {
                FixtureSurface.IDENTITIES -> IdentitiesScreen(
                    identityRepository = displayedContainer.value.foundationGraph.identityRepository,
                )

                FixtureSurface.HOSTS -> HostsScreen(
                    hostRepository = displayedContainer.value.foundationGraph.hostRepository,
                    identityRepository = displayedContainer.value.foundationGraph.identityRepository,
                )

                FixtureSurface.SESSIONS -> SessionsScreen(
                    hostRepository = displayedContainer.value.foundationGraph.hostRepository,
                    identityRepository = displayedContainer.value.foundationGraph.identityRepository,
                    knownHostTrustRepository = displayedContainer.value.foundationGraph.knownHostTrustRepository,
                    coordinator = displayedCoordinator.value,
                )
            }
        }
        composeRule.onNodeWithTag("identity_row_1").assertIsDisplayed()

        switchSurface(FixtureSurface.HOSTS)
        composeRule.onNodeWithTag("host_row_1").assertIsDisplayed()

        switchSurface(FixtureSurface.SESSIONS)
        connectFromUi(coordinator = firstCoordinator, hostId = 1, expectTrustPrompt = true)
        composeRule.onNodeWithTag("session_active_endpoint")
            .assertTextContains("$FIXTURE_HOST:$FIXTURE_PORT", substring = true)
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
        assertEquals(
            1,
            runBlocking { firstContainer.foundationGraph.knownHostTrustRepository.observeTrustedHosts().first().size },
        )

        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Disconnected.", substring = true)
            }.isSuccess
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            firstCoordinator.observeUiState().value.canSendInput.not() &&
                composeRule.onAllNodesWithTag("session_terminal_truth_banner")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("session_terminal_truth_banner").assertCountEquals(1)
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()

        switchSurface(FixtureSurface.IDENTITIES)
        composeRule.onNodeWithTag("identity_edit_1").performClick()
        composeRule.onNodeWithTag("identity_name_field").performTextClearance()
        composeRule.onNodeWithTag("identity_name_field").performTextInput("Renamed fixture password")
        composeRule.onNodeWithTag("identity_editor_save").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("identity_row_1").assertIsDisplayed()
            }.isSuccess
        }

        switchSurface(FixtureSurface.SESSIONS)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_host_identity_1")
                    .assertTextContains("Renamed fixture password", substring = true)
            }.isSuccess
        }
        connectFromUi(coordinator = firstCoordinator, hostId = 1, expectTrustPrompt = false)
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)

        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Disconnected.", substring = true)
            }.isSuccess
        }

        val relaunchedContainer = AppContainer.create(context)
        val relaunchedCoordinator = buildCoordinator(relaunchedContainer)
        composeRule.runOnIdle {
            displayedContainer.value = relaunchedContainer
            displayedCoordinator.value = relaunchedCoordinator
        }
        val relaunchedIdentity = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.getIdentity(1)
        }
        assertEquals("Renamed fixture password", relaunchedIdentity?.name)
        assertNotNull(
            runBlocking { relaunchedContainer.foundationGraph.identityRepository.getSecretMaterial(1) },
        )

        switchSurface(FixtureSurface.SESSIONS)
        composeRule.onNodeWithTag("session_host_row_1").performScrollTo()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_host_identity_1")
                    .assertTextContains("Renamed fixture password", substring = true)
            }.isSuccess
        }
        connectFromUi(coordinator = relaunchedCoordinator, hostId = 1, expectTrustPrompt = false)
        waitForProofText(relaunchedCoordinator, FIXTURE_PORT)
        composeRule.onNodeWithTag("session_disconnect_button").assertIsDisplayed()
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
    fun remote_disconnect_marks_session_reconnect_required_and_preserves_history_as_non_live() {
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
        waitForState(coordinator) { it.pendingTrustDecision != null }
        coordinator.submitHostTrustDecision(true)
        val connected = waitForState(coordinator) { it.connectionState == SessionConnectionState.CONNECTED }
        assertTrue(connected.canSendInput)
        assertProofEventually(coordinator, FIXTURE_PORT)

        coordinator.disconnect()
        val disconnected = waitForState(coordinator) { it.connectionState == SessionConnectionState.DISCONNECTED }
        assertFalse(disconnected.canSendInput)
        assertTrue(disconnected.transcript.contains("ATERM_REMOTE_PROOF:$FIXTURE_HOST:$FIXTURE_PORT:"))
    }

    private fun buildCoordinator(container: AppContainer): SshSessionCoordinator = SshSessionCoordinator(
        hostRepository = container.foundationGraph.hostRepository,
        identityRepository = container.foundationGraph.identityRepository,
        knownHostTrustRepository = container.foundationGraph.knownHostTrustRepository,
    )

    private fun waitForState(
        coordinator: SshSessionCoordinator,
        timeoutMillis: Long = 30_000L,
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
        check(transcript.contains("ATERM_REMOTE_PROOF:$FIXTURE_HOST:$port:")) {
            "Transcript did not contain remote proof for $FIXTURE_HOST:$port: $transcript"
        }
    }

    private fun assertProofEventually(
        coordinator: SshSessionCoordinator,
        port: Int,
    ) {
        val state = waitForState(coordinator, timeoutMillis = 30_000) {
            it.transcript.contains("ATERM_REMOTE_PROOF:$FIXTURE_HOST:$port:")
        }
        assertProof(state, port)
    }

    private fun waitForProofText(
        coordinator: SshSessionCoordinator,
        port: Int,
    ) {
        assertProofEventually(coordinator, port)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_transcript")
                    .assertTextContains("ATERM_REMOTE_PROOF:$FIXTURE_HOST:$port:", substring = true)
            }.isSuccess
        }
    }

    private fun switchSurface(surface: FixtureSurface) {
        composeRule.runOnIdle {
            displayedSurface.value = surface
        }
        val expectedTag = when (surface) {
            FixtureSurface.IDENTITIES -> "screen_identities"
            FixtureSurface.HOSTS -> "screen_hosts"
            FixtureSurface.SESSIONS -> "screen_session"
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { composeRule.onNodeWithTag(expectedTag).assertIsDisplayed() }.isSuccess
        }
    }

    private fun connectFromUi(
        coordinator: SshSessionCoordinator,
        hostId: Long,
        expectTrustPrompt: Boolean,
    ) {
        composeRule.onNodeWithTag("session_connect_$hostId").performScrollTo()
        composeRule.onNodeWithTag("session_connect_$hostId").performClick()
        waitForState(coordinator) {
            it.activeHostId == hostId &&
                (
                    it.connectionState == SessionConnectionState.CONNECTING ||
                        it.connectionState == SessionConnectionState.CONNECTED ||
                        it.connectionState == SessionConnectionState.FAILED ||
                        it.connectionState == SessionConnectionState.RECONNECT_REQUIRED ||
                        it.pendingTrustDecision != null
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
            composeRule.onNodeWithTag("session_trust_accept").performClick()
        }
        val state = waitForState(coordinator, timeoutMillis = 30_000) {
            it.activeHostId == hostId &&
                it.connectionState != SessionConnectionState.CONNECTING &&
                it.connectionState != SessionConnectionState.DISCONNECTED
        }
        check(state.connectionState == SessionConnectionState.CONNECTED) {
            "Expected connected UI session for $FIXTURE_HOST:$FIXTURE_PORT but was ${state.connectionState} with status=${state.statusMessage} transcript=${state.transcript.take(200)}"
        }
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        assertProofEventually(coordinator, FIXTURE_PORT)
    }

    private companion object {
        const val FIXTURE_HOST = "10.0.2.2"
        const val FIXTURE_PORT = 3122
        const val HOST_SSH_PORT = 22
        const val FIXTURE_USERNAME = "atermtester"
        const val FIXTURE_PASSWORD = "aterm-password-fixture"
    }

    private enum class FixtureSurface {
        IDENTITIES,
        HOSTS,
        SESSIONS,
    }
}
