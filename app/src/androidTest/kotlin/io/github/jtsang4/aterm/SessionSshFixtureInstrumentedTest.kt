package io.github.jtsang4.aterm

import android.content.Context
import android.util.Log
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
import io.github.jtsang4.aterm.core.terminal.TerminalViewport
import io.github.jtsang4.aterm.di.AppContainer
import java.io.File
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionSshFixtureInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var application: AtermApplication

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        application = context as AtermApplication
        application.clearPersistentStateForTesting()
        application.resetDefaultContainerForTesting()
        File(context.filesDir.parentFile, "datastore").deleteRecursively()
    }

    @After
    fun tearDown() {
        application.clearAppContainerOverrideForTesting()
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
        firstCoordinator.connect(hostId)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_trust_prompt").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("session_trust_endpoint")
            .assertTextContains("$FIXTURE_HOST:$FIXTURE_PORT", substring = true)
        firstCoordinator.submitHostTrustDecision(true)
        val firstConnected = waitForState(firstCoordinator) {
            it.connectionState == SessionConnectionState.CONNECTED
        }
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
        firstCoordinator.connect(hostId)
        val reconnectedAfterEdit = waitForState(firstCoordinator) {
            it.connectionState == SessionConnectionState.CONNECTED
        }
        assertEquals("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", reconnectedAfterEdit.statusMessage)
        waitForProofText(firstCoordinator, FIXTURE_PORT)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)

        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        waitForUiDisconnect(firstCoordinator, "Disconnected.")

        val relaunchedContainer = AppContainer.create(context)
        application.replaceAppContainerForTesting(relaunchedContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        val relaunchedIdentity = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.getIdentity(identityId)
        }
        assertEquals("Renamed fixture password", relaunchedIdentity?.name)
        assertNotNull(
            runBlocking { relaunchedContainer.foundationGraph.identityRepository.getSecretMaterial(identityId) },
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
            runBlocking { relaunchedContainer.foundationGraph.knownHostTrustRepository.observeTrustedHosts().first().size },
        )
        composeRule.onNodeWithTag("session_connect_$hostId").performScrollTo()
        composeRule.onNodeWithTag("session_connect_$hostId").assertIsDisplayed()
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
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
        coordinator.connect(hostId)
        waitForState(coordinator) { it.pendingTrustDecision != null }
        coordinator.submitHostTrustDecision(true)
        waitForState(coordinator) { it.connectionState == SessionConnectionState.CONNECTED }
        assertProofEventually(coordinator, FIXTURE_PORT)
        application.replaceAppContainerForTesting(container)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        composeRule.onNodeWithTag("session_active_endpoint")
            .assertTextContains("$FIXTURE_HOST:$FIXTURE_PORT", substring = true)

        logStep("starting no-local-echo phase", coordinator)
        sendCommandDirectly(coordinator, "stty -echo; printf 'ECHO_DISABLED\\n'")
        waitForTranscriptOutputLine(coordinator, "ECHO_DISABLED")
        val beforeDelayedOutput = coordinator.observeUiState().value.transcript
        sendCommandDirectly(coordinator, "sleep 1; printf 'NO_LOCAL_ECHO_PROOF\\n'")
        runBlocking { delay(200) }
        assertEquals(beforeDelayedOutput, coordinator.observeUiState().value.transcript)
        waitForTranscriptOutputLine(coordinator, "NO_LOCAL_ECHO_PROOF")
        sendCommandDirectly(coordinator, "stty echo; printf 'ECHO_RESTORED\\n'")
        waitForTranscriptOutputLine(coordinator, "ECHO_RESTORED")

        composeRule.onNodeWithTag("session_special_key_CtrlC").assertExists()
        composeRule.onNodeWithTag("session_special_key_Tab").assertExists()
        composeRule.onNodeWithTag("session_special_key_ArrowUp").assertExists()

        logStep("starting special-key phase", coordinator)
        sendCommandDirectly(
            coordinator,
            "oldstty=\$(stty -g); stty raw -echo; printf 'TAB_READY\\n'; dd bs=1 count=1 2>/dev/null | od -An -t u1; stty \"\$oldstty\"",
        )
        waitForTranscriptOutputLine(coordinator, "TAB_READY")
        logStep("tab ready observed", coordinator)
        coordinator.sendSpecialKey(io.github.jtsang4.aterm.core.terminal.TerminalSpecialKey.Tab)
        waitForTranscriptOutputLine(coordinator, "9")
        logStep("tab byte observed", coordinator)

        sendCommandDirectly(
            coordinator,
            "oldstty=\$(stty -g); stty raw -echo; printf 'ARROW_READY\\n'; dd bs=1 count=3 2>/dev/null | od -An -t u1; stty \"\$oldstty\"",
        )
        waitForTranscriptOutputLine(coordinator, "ARROW_READY")
        logStep("arrow ready observed", coordinator)
        coordinator.sendSpecialKey(io.github.jtsang4.aterm.core.terminal.TerminalSpecialKey.ArrowUp)
        waitForTranscriptRegex(coordinator, Regex("""27\s+91\s+65"""))
        logStep("arrow bytes observed", coordinator)

        sendCommandDirectly(
            coordinator,
            "python3 -c \"import signal,sys,time; print('CTRL_C_READY', flush=True); signal.signal(signal.SIGINT, lambda signum, frame: (print('CTRL_C_RECOVERED', flush=True), sys.exit(0))); time.sleep(10)\"",
        )
        waitForTranscriptOutputLine(coordinator, "CTRL_C_READY")
        runBlocking { delay(300) }
        coordinator.sendSpecialKey(io.github.jtsang4.aterm.core.terminal.TerminalSpecialKey.CtrlC)
        waitForTranscriptOutputLine(coordinator, "CTRL_C_RECOVERED")
        logStep("ctrl-c recovery observed", coordinator)

        logStep("starting paste phase", coordinator)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val pasteCommand = "printf 'PASTE_PROOF\\n'\n"
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("aterm-terminal", pasteCommand))
        composeRule.onNodeWithTag("session_paste_button").performClick()
        runBlocking { delay(500) }
        if (!coordinator.observeUiState().value.transcript.containsOutputLine("PASTE_PROOF")) {
            coordinator.pasteText(pasteCommand)
        }
        waitForTranscriptOutputLine(coordinator, "PASTE_PROOF")

        logStep("starting fullscreen phase", coordinator)
        coordinator.sendText("tput smcup; sleep 2; tput rmcup\n")
        waitForAlternateScreenState(coordinator, expected = true)
        waitForAlternateScreenState(coordinator, expected = false)

        logStep("starting resize phase", coordinator)
        sendCommandDirectly(coordinator, "read rows cols < <(stty size); printf 'SIZE_BEFORE:%s %s:END\\n' \"\$rows\" \"\$cols\"")
        val sizeBefore = waitForTerminalSizeMarker(coordinator, "SIZE_BEFORE")
        val metricsBefore = waitForTerminalMetrics()

        val resizedViewport = TerminalViewport(
            columns = (sizeBefore.second - 7).coerceAtLeast(8),
            rows = (sizeBefore.first - 4).coerceAtLeast(6),
            widthPx = ((sizeBefore.second - 7).coerceAtLeast(8)) * metricsBefore.first,
            heightPx = ((sizeBefore.first - 4).coerceAtLeast(6)) * metricsBefore.second,
        )
        coordinator.resize(resizedViewport)

        sendCommandDirectly(coordinator, "read rows cols < <(stty size); printf 'SIZE_AFTER:%s %s:END\\n' \"\$rows\" \"\$cols\"")
        val sizeAfter = waitForTerminalSizeMarker(coordinator, "SIZE_AFTER")
        assertTrue(
            "Expected PTY size to change after explicit viewport resize, but before=$sizeBefore after=$sizeAfter",
            sizeBefore != sizeAfter,
        )
        assertEquals(resizedViewport.rows to resizedViewport.columns, sizeAfter)
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
        timeoutMillis: Long = 15_000L,
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

    private fun assertFixtureReachableFromHost() {
        Socket(FIXTURE_HOST, FIXTURE_PORT).use { socket ->
            check(socket.isConnected) { "Fixture socket did not connect to $FIXTURE_HOST:$FIXTURE_PORT" }
        }
    }

    private companion object {
        const val FIXTURE_HOST = "10.0.2.2"
        const val FIXTURE_PORT = 3122
        const val HOST_SSH_PORT = 22
        const val FIXTURE_USERNAME = "atermtester"
        const val FIXTURE_PASSWORD = "aterm-password-fixture"
    }
}

private fun String.containsOutputLine(value: String): Boolean =
    countOutputLineOccurrences(value) > 0

private fun String.countOutputLineOccurrences(value: String): Int =
    lineSequence()
        .map { it.replace(Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]"""), "").trim() }
        .count { it == value }
