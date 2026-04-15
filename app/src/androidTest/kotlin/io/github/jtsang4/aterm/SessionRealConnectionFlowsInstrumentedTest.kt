package io.github.jtsang4.aterm

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.model.SecretStorageState
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import io.github.jtsang4.aterm.core.domain.repository.SessionMetadataRepository
import io.github.jtsang4.aterm.core.ssh.PendingTrustDecision
import io.github.jtsang4.aterm.core.ssh.SessionController
import io.github.jtsang4.aterm.core.domain.model.SessionMetadata
import io.github.jtsang4.aterm.core.ssh.SessionUiState
import io.github.jtsang4.aterm.core.terminal.TerminalBuffer
import io.github.jtsang4.aterm.core.terminal.TerminalSpecialKey
import io.github.jtsang4.aterm.core.terminal.TerminalUiState
import io.github.jtsang4.aterm.core.terminal.TerminalViewport
import io.github.jtsang4.aterm.feature.session.SessionsScreen
import java.security.KeyPair
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.util.security.SecurityUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionRealConnectionFlowsInstrumentedTest {
    private val resetRule = TestPersistenceResetRule()
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(resetRule)
        .around(composeRule)

    private lateinit var passwordIdentityRepository: SessionTestIdentityRepository
    private lateinit var hostRepository: SessionTestHostRepository
    private lateinit var knownHostTrustRepository: SessionTestKnownHostTrustRepository
    private lateinit var controller: ScriptedSessionController

    private val context
        get() = resetRule.context

    @After
    fun tearDown() {
    }

    @Test
    fun real_password_connect_requires_trust_then_reuses_it_and_survives_identity_metadata_edit() {
        val port = 2222

        passwordIdentityRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Session password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "secret-password")),
        )
        hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Password host",
                    address = "10.0.2.2",
                    port = port,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        knownHostTrustRepository = SessionTestKnownHostTrustRepository()
        controller = ScriptedSessionController(
            hostRepository = hostRepository,
            trustRepository = knownHostTrustRepository,
            scripts = mapOf(
                1L to ConnectScript.Success(
                    endpoint = "10.0.2.2:$port",
                    proof = "ATERM_REMOTE_PROOF:10.0.2.2:$port:password-shell",
                    trust = SessionTrustPayload(
                        endpoint = "10.0.2.2:$port",
                        fingerprint = "SHA256:first-fingerprint",
                        algorithm = "RSA",
                        hostKeyBase64 = "host-key-password-$port",
                    ),
                ),
            ),
        )

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = passwordIdentityRepository,
                knownHostTrustRepository = knownHostTrustRepository,
                coordinator = controller,
            )
        }

        connectAndTrust(hostId = 1)
        composeRule.onNodeWithTag("session_transcript")
            .assertTextContains("ATERM_REMOTE_PROOF:10.0.2.2:$port:password-shell", substring = true)
        assertEquals(1, knownHostTrustRepository.snapshot().size)

        runBlocking {
            passwordIdentityRepository.upsert(
                passwordIdentityRepository.getIdentity(1)!!.copy(name = "Renamed password"),
                secrets = null,
            )
        }
        controller.setScripts(
            mapOf(
                1L to ConnectScript.Success(
                    endpoint = "10.0.2.2:$port",
                    proof = "ATERM_REMOTE_PROOF:10.0.2.2:$port:password-shell",
                    trust = null,
                ),
            ),
        )
        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Disconnected.", substring = true)
            }.isSuccess
        }

        composeRule.onNodeWithTag("session_connect_1").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Connected to 10.0.2.2:$port.", substring = true)
            }.isSuccess
        }
        composeRule.onNodeWithTag("session_transcript")
            .assertTextContains("ATERM_REMOTE_PROOF:10.0.2.2:$port:password-shell", substring = true)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
    }

    @Test
    fun host_endpoint_edit_requires_fresh_trust_and_changed_key_is_blocked() {
        val port = 2223

        passwordIdentityRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Session password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "secret-password")),
        )
        hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Original endpoint",
                    address = "10.0.2.2",
                    port = port,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
                Host(
                    id = 2,
                    label = "Repointed endpoint",
                    address = "127.0.0.1",
                    port = port,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        knownHostTrustRepository = SessionTestKnownHostTrustRepository()
        controller = ScriptedSessionController(
            hostRepository = hostRepository,
            trustRepository = knownHostTrustRepository,
            scripts = mapOf(
                1L to ConnectScript.Success(
                    endpoint = "10.0.2.2:$port",
                    proof = "ATERM_REMOTE_PROOF:10.0.2.2:$port:password-shell",
                    trust = SessionTrustPayload(
                        endpoint = "10.0.2.2:$port",
                        fingerprint = "SHA256:original-endpoint",
                        algorithm = "RSA",
                        hostKeyBase64 = "host-key-original-$port",
                    ),
                ),
                2L to ConnectScript.Success(
                    endpoint = "127.0.0.1:$port",
                    proof = "ATERM_REMOTE_PROOF:127.0.0.1:$port:password-shell",
                    trust = SessionTrustPayload(
                        endpoint = "127.0.0.1:$port",
                        fingerprint = "SHA256:second-endpoint",
                        algorithm = "RSA",
                        hostKeyBase64 = "host-key-second-$port",
                    ),
                ),
            ),
        )

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = passwordIdentityRepository,
                knownHostTrustRepository = knownHostTrustRepository,
                coordinator = controller,
            )
        }

        connectAndTrust(hostId = 1)
        composeRule.onNodeWithTag("session_disconnect_button").performClick()

        composeRule.onNodeWithTag("session_connect_2").performScrollTo()
        composeRule.onNodeWithTag("session_connect_2").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_trust_prompt").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("session_trust_endpoint")
            .assertTextContains("127.0.0.1:$port", substring = true)
        composeRule.onNodeWithTag("session_trust_accept").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Connected to 127.0.0.1:$port.", substring = true)
            }.isSuccess
        }
        assertEquals(2, knownHostTrustRepository.snapshot().size)

        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        controller.setScripts(
            mapOf(
                2L to ConnectScript.ChangedHostKey(
                    endpoint = "127.0.0.1:$port",
                ),
            ),
        )

        composeRule.onNodeWithTag("session_connect_2").performScrollTo()
        composeRule.onNodeWithTag("session_connect_2").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Host key changed for 127.0.0.1:$port. Connection blocked.", substring = true)
            }.isSuccess
        }
    }

    @Test
    fun terminal_surface_supports_scrollback_and_terminal_chrome() {
        val port = 2299

        passwordIdentityRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Session password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "secret-password")),
        )
        hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Terminal host",
                    address = "10.0.2.2",
                    port = port,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        knownHostTrustRepository = SessionTestKnownHostTrustRepository()
        controller = ScriptedSessionController(
            hostRepository = hostRepository,
            trustRepository = knownHostTrustRepository,
            scripts = mapOf(
                1L to ConnectScript.Success(
                    endpoint = "10.0.2.2:$port",
                    proof = listOf(
                        "line1",
                        "line2",
                        "line3",
                        "line4",
                        "line5",
                        "line6",
                        "line7",
                        "line8",
                        "line9",
                        "line10",
                        "line11",
                        "line12",
                        "line13",
                        "line14",
                        "line15",
                        "line16",
                        "line17",
                        "line18",
                        "line19",
                        "line20",
                        "line21",
                        "line22",
                        "line23",
                        "line24",
                        "line25",
                        "line26",
                        "line27",
                        "line28",
                        "line29",
                        "line30",
                        "line31",
                        "line32",
                        "line33",
                        "line34",
                        "line35",
                        "line36",
                        "line37",
                        "line38",
                        "line39",
                        "line40",
                    ).joinToString(separator = "\n"),
                    trust = SessionTrustPayload(
                        endpoint = "10.0.2.2:$port",
                        fingerprint = "SHA256:terminal-flow",
                        algorithm = "RSA",
                        hostKeyBase64 = "host-key-terminal-$port",
                    ),
                ),
            ),
        )

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = passwordIdentityRepository,
                knownHostTrustRepository = knownHostTrustRepository,
                coordinator = controller,
            )
        }

        connectAndTrust(hostId = 1)

        composeRule.onNodeWithTag("session_terminal_surface").assertIsDisplayed()
        composeRule.onNodeWithTag("session_terminal_status")
            .assertTextContains("Scrollback:", substring = true)
        composeRule.onNodeWithTag("session_transcript")
            .assertTextContains("line26", substring = true)
        composeRule.onNodeWithTag("session_special_key_Tab").assertExists()
        composeRule.onNodeWithTag("session_special_key_CtrlC").assertExists()
        composeRule.onNodeWithTag("session_copy_button").assertExists()
        composeRule.onNodeWithTag("session_paste_button").assertExists()

        composeRule.onNodeWithTag("session_scrollback_up").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("session_terminal_status")
                    .assertTextContains("viewing history", substring = true)
            }.isSuccess
        }

        composeRule.onNodeWithTag("session_jump_to_live").performClick()
        composeRule.onNodeWithTag("session_terminal_status")
            .assertTextContains("Scrollback:", substring = true)
        composeRule.onNodeWithTag("session_transcript")
            .assertTextContains("line26", substring = true)

        composeRule.onNodeWithTag("session_input_field").assertExists()
        composeRule.onNodeWithTag("session_send_button").assertExists()
    }

    @Test
    fun authentication_failure_surfaces_clearly() {
        val port = 2224

        val wrongPasswordRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Wrong password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "wrong-password")),
        )
        val hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Wrong password host",
                    address = "10.0.2.2",
                    port = port,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
                Host(
                    id = 2,
                    label = "Dns failure",
                    address = "nonexistent.invalid",
                    port = port,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
                Host(
                    id = 3,
                    label = "Unreachable host",
                    address = "10.0.2.2",
                    port = 65022,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        val trustRepository = SessionTestKnownHostTrustRepository()
        val controller = ScriptedSessionController(
            hostRepository = hostRepository,
            trustRepository = trustRepository,
            scripts = mapOf(
                1L to ConnectScript.AuthenticationFailure(
                    trust = SessionTrustPayload(
                        endpoint = "10.0.2.2:$port",
                        fingerprint = "SHA256:wrong-password",
                        algorithm = "RSA",
                        hostKeyBase64 = "host-key-auth-fail-$port",
                    ),
                ),
                2L to ConnectScript.DnsFailure,
                3L to ConnectScript.NetworkFailure,
            ),
        )

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = wrongPasswordRepository,
                knownHostTrustRepository = trustRepository,
                coordinator = controller,
            )
        }

        composeRule.onNodeWithTag("session_connect_1").performScrollTo()
        composeRule.onNodeWithTag("session_connect_1").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_trust_prompt").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("session_trust_accept").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Authentication failed.", substring = true)
            }.isSuccess
        }
    }

    @Test
    fun dns_failure_surfaces_clearly() {
        val port = 2224

        val wrongPasswordRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Wrong password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "wrong-password")),
        )
        val hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 2,
                    label = "Dns failure",
                    address = "nonexistent.invalid",
                    port = port,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        val trustRepository = SessionTestKnownHostTrustRepository()
        val controller = ScriptedSessionController(
            hostRepository = hostRepository,
            trustRepository = trustRepository,
            scripts = mapOf(2L to ConnectScript.DnsFailure),
        )

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = wrongPasswordRepository,
                knownHostTrustRepository = trustRepository,
                coordinator = controller,
            )
        }

        composeRule.onNodeWithTag("session_connect_2").performScrollTo()
        composeRule.onNodeWithTag("session_connect_2").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("DNS lookup failed", substring = true)
            }.isSuccess
        }
    }

    @Test
    fun network_failure_surfaces_clearly() {
        val wrongPasswordRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Wrong password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "wrong-password")),
        )
        val hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 3,
                    label = "Unreachable host",
                    address = "10.0.2.2",
                    port = 65022,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        val trustRepository = SessionTestKnownHostTrustRepository()
        val controller = ScriptedSessionController(
            hostRepository = hostRepository,
            trustRepository = trustRepository,
            scripts = mapOf(3L to ConnectScript.NetworkFailure),
        )

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = wrongPasswordRepository,
                knownHostTrustRepository = trustRepository,
                coordinator = controller,
            )
        }

        composeRule.onNodeWithTag("session_connect_3").performScrollTo()
        composeRule.onNodeWithTag("session_connect_3").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Host is unreachable", substring = true)
            }.isSuccess
        }
    }

    @Test
    fun timeout_failure_surfaces_clearly() {
        val passwordRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Timeout password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "timeout-password")),
        )
        val hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 4,
                    label = "Timeout host",
                    address = "10.0.2.2",
                    port = 3122,
                    username = "atermtimeout",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        val trustRepository = SessionTestKnownHostTrustRepository()
        val controller = ScriptedSessionController(
            hostRepository = hostRepository,
            trustRepository = trustRepository,
            scripts = mapOf(4L to ConnectScript.TimeoutFailure),
        )

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = passwordRepository,
                knownHostTrustRepository = trustRepository,
                coordinator = controller,
            )
        }

        composeRule.onNodeWithTag("session_connect_4").performScrollTo()
        composeRule.onNodeWithTag("session_connect_4").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Connection timed out while reaching", substring = true)
            }.isSuccess
        }
    }

    @Test
    fun repeated_connect_taps_and_cancel_yield_one_truthful_final_state() {
        passwordIdentityRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Session password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "secret-password")),
        )
        hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Slow host",
                    address = "10.0.2.2",
                    port = 3130,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        knownHostTrustRepository = SessionTestKnownHostTrustRepository()
        val releaseConnect = CountDownLatch(1)
        val startedConnect = CountDownLatch(1)
        val slowController = SlowScriptedSessionController(
            hostRepository = hostRepository,
            releaseConnect = releaseConnect,
            startedConnect = startedConnect,
            scripts = mapOf(
                1L to ConnectScript.Success(
                    endpoint = "10.0.2.2:3130",
                    proof = "ATERM_REMOTE_PROOF:10.0.2.2:3130:slow-shell",
                    trust = null,
                ),
            ),
        )

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = passwordIdentityRepository,
                knownHostTrustRepository = knownHostTrustRepository,
                coordinator = slowController,
            )
        }

        composeRule.onNodeWithTag("session_connect_1").performScrollTo()
        composeRule.onNodeWithTag("session_connect_1").performClick()
        check(startedConnect.await(5, TimeUnit.SECONDS)) { "Connect did not start" }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            slowController.observeUiState().value.connectionState == io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTING
        }
        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
        composeRule.onNodeWithTag("session_disconnect_button").assertIsDisplayed()
        composeRule.onNodeWithTag("session_connect_1").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_disconnect_button").performClick()
        releaseConnect.countDown()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            slowController.observeUiState().value.connectionState == io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.DISCONNECTED
        }
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connection canceled.", substring = true)
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
        assertEquals(1, slowController.connectCalls.get())
        assertEquals(0, slowController.completedConnections.get())
    }

    @Test
    fun activity_recreation_while_connecting_keeps_single_connecting_state() {
        val releaseConnect = CountDownLatch(1)
        val startedConnect = CountDownLatch(1)
        val showSessionScreen = mutableStateOf(true)
        passwordIdentityRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Session password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "secret-password")),
        )
        hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Slow host",
                    address = "10.0.2.2",
                    port = 3131,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        knownHostTrustRepository = SessionTestKnownHostTrustRepository()
        val slowController = SlowScriptedSessionController(
            hostRepository = hostRepository,
            releaseConnect = releaseConnect,
            startedConnect = startedConnect,
            scripts = mapOf(
                1L to ConnectScript.Success(
                    endpoint = "10.0.2.2:3131",
                    proof = "ATERM_REMOTE_PROOF:10.0.2.2:3131:slow-shell",
                    trust = null,
                ),
            ),
        )

        composeRule.setContent {
            if (showSessionScreen.value) {
                SessionsScreen(
                    hostRepository = hostRepository,
                    identityRepository = passwordIdentityRepository,
                    knownHostTrustRepository = knownHostTrustRepository,
                    coordinator = slowController,
                )
            } else {
                Text("Session screen hidden")
            }
        }

        composeRule.onNodeWithTag("session_connect_1").performScrollTo()
        composeRule.onNodeWithTag("session_connect_1").performClick()
        check(startedConnect.await(5, TimeUnit.SECONDS)) { "Connect did not start" }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            slowController.observeUiState().value.connectionState ==
                io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTING
        }

        composeRule.runOnUiThread {
            showSessionScreen.value = false
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("session_status_card").fetchSemanticsNodes().isEmpty()
        }
        composeRule.runOnUiThread {
            showSessionScreen.value = true
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_state_label")
                    .assertTextContains("connecting", substring = true)
            }.isSuccess
        }
        composeRule.onAllNodesWithTag("session_disconnect_button").assertCountEquals(1)
        composeRule.onAllNodesWithTag("session_connect_1").assertCountEquals(1)
        assertEquals(1, slowController.connectCalls.get())

        releaseConnect.countDown()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            slowController.observeUiState().value.connectionState ==
                io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTED
        }
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to 10.0.2.2:3131.", substring = true)
        assertEquals(1, slowController.completedConnections.get())
    }

    @Test
    fun reconnect_required_state_disables_live_input_and_survives_screen_recreation() {
        passwordIdentityRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Session password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "secret-password")),
        )
        hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Disconnected host",
                    address = "10.0.2.2",
                    port = 3132,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        knownHostTrustRepository = SessionTestKnownHostTrustRepository()
        val metadataRepository = SessionTestSessionMetadataRepository(
            initialSessions = listOf(
                SessionMetadata(
                    id = 1,
                    hostId = 1,
                    state = SessionConnectionState.RECONNECT_REQUIRED,
                    title = "Disconnected host",
                    connectedAt = Instant.now(),
                    disconnectedAt = Instant.now(),
                    reconnectRequired = true,
                    lastError = "Remote shell closed",
                ),
            ),
        )
        val controller = RecoveryStateSessionController(
            hostRepository = hostRepository,
            metadataRepository = metadataRepository,
        )
        val showSessionScreen = mutableStateOf(true)

        composeRule.setContent {
            if (showSessionScreen.value) {
                SessionsScreen(
                    hostRepository = hostRepository,
                    identityRepository = passwordIdentityRepository,
                    knownHostTrustRepository = knownHostTrustRepository,
                    coordinator = controller,
                )
            } else {
                Text("Session screen hidden")
            }
        }

        composeRule.onNodeWithTag("session_reconnect_required")
            .assertTextContains("Remote shell closed", substring = true)
        composeRule.onNodeWithTag("session_terminal_truth_banner")
            .assertTextContains("Remote shell closed", substring = true)
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()
        composeRule.onNodeWithTag("session_paste_button").assertIsNotEnabled()
        composeRule.onNodeWithText("Reconnect").assertIsDisplayed()

        composeRule.runOnUiThread {
            showSessionScreen.value = false
        }
        composeRule.runOnUiThread {
            showSessionScreen.value = true
        }

        composeRule.onNodeWithTag("session_reconnect_required")
            .assertTextContains("Remote shell closed", substring = true)
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()
    }

    @Test
    fun terminal_resize_events_propagate_to_controller() {
        passwordIdentityRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Session password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "secret-password")),
        )
        hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Resize host",
                    address = "10.0.2.2",
                    port = 3133,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        knownHostTrustRepository = SessionTestKnownHostTrustRepository()
        val controller = ResizeRecordingSessionController()

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = passwordIdentityRepository,
                knownHostTrustRepository = knownHostTrustRepository,
                coordinator = controller,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.resizeEvents.isNotEmpty()
        }
        val viewport = controller.resizeEvents.last()
        val bounds = composeRule.onNodeWithTag("session_terminal_surface").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width.toInt(), viewport.widthPx)
        assertEquals(bounds.height.toInt(), viewport.heightPx)
        assertEquals((viewport.widthPx / 9).coerceAtLeast(1), viewport.columns)
        assertEquals((viewport.heightPx / 18).coerceAtLeast(1), viewport.rows)
    }

    @Test
    fun send_paste_and_special_keys_do_not_locally_echo_before_remote_output() {
        passwordIdentityRepository = SessionTestIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Session password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                    secretStorageState = SecretStorageState.AVAILABLE,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "secret-password")),
        )
        hostRepository = SessionTestHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Live host",
                    address = "10.0.2.2",
                    port = 3134,
                    username = "tester",
                    identityId = 1,
                    authKind = HostAuthKind.PASSWORD,
                ),
            ),
        )
        knownHostTrustRepository = SessionTestKnownHostTrustRepository()
        val controller = NoLocalEchoSessionController(hostRepository = hostRepository)

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = passwordIdentityRepository,
                knownHostTrustRepository = knownHostTrustRepository,
                coordinator = controller,
            )
        }

        composeRule.onNodeWithTag("session_special_key_CtrlC").assertExists()
        composeRule.onNodeWithTag("session_special_key_Tab").assertExists()
        composeRule.onNodeWithTag("session_special_key_ArrowUp").assertExists()
        composeRule.onNodeWithTag("session_paste_button").assertExists()
        composeRule.onNodeWithTag("session_send_button").assertExists()

        composeRule.runOnIdle {
            controller.sendSpecialKey(TerminalSpecialKey.CtrlC)
            controller.sendSpecialKey(TerminalSpecialKey.Tab)
            controller.sendSpecialKey(TerminalSpecialKey.ArrowUp)
            controller.pasteText("printf 'from paste'\n")
            controller.sendText("echo hello\n")
        }

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "CTRL_C",
                    "TAB",
                    "ARROW_UP",
                    "printf 'from paste'\n",
                    "echo hello\n",
                ),
                controller.sentInputs.map(::debugInput),
            )
        }
        composeRule.onNodeWithTag("session_transcript").assertTextContains("No terminal transcript yet.", substring = true)
    }

    private fun connectAndTrust(hostId: Long) {
        composeRule.onNodeWithTag("session_connect_$hostId").performScrollTo()
        composeRule.onNodeWithTag("session_connect_$hostId").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_trust_prompt").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("session_trust_accept").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_status_message")
                    .assertTextContains("Connected", substring = true)
            }.isSuccess
        }
    }
}

private class SessionTestHostRepository(
    initialHosts: List<Host>,
) : HostRepository {
    private val hosts = MutableStateFlow(initialHosts)

    override fun observeHosts(): Flow<List<Host>> = hosts

    override suspend fun getHost(id: Long): Host? = hosts.value.firstOrNull { it.id == id }

    override suspend fun upsert(host: Host): Host {
        val persisted = host.copy(id = host.id.takeIf { it != 0L } ?: ((hosts.value.maxOfOrNull(Host::id) ?: 0L) + 1L))
        hosts.value = hosts.value.filterNot { it.id == persisted.id } + persisted
        return persisted
    }

    override suspend fun setFavorite(id: Long, isFavorite: Boolean) {
        hosts.value = hosts.value.map { host ->
            if (host.id == id) host.copy(isFavorite = isFavorite) else host
        }
    }

    override suspend fun markUsed(id: Long, usedAt: Instant) = Unit

    override suspend fun deleteHost(id: Long) {
        hosts.value = hosts.value.filterNot { it.id == id }
    }
}

private class SessionTestIdentityRepository(
    initialIdentities: List<Identity>,
    initialSecrets: Map<Long, IdentitySecretMaterial>,
) : IdentityRepository {
    private val identities = MutableStateFlow(initialIdentities)
    private val secrets = initialSecrets.toMutableMap()

    override fun observeIdentities(): Flow<List<Identity>> = identities

    override suspend fun getIdentity(id: Long): Identity? = identities.value.firstOrNull { it.id == id }

    override suspend fun upsert(identity: Identity, secrets: IdentitySecretMaterial?): Identity {
        val persisted = identity.copy(
            hasSecret = identity.hasSecret || secrets?.primarySecret != null,
            secretStorageState = if (identity.hasSecret || secrets?.primarySecret != null) {
                SecretStorageState.AVAILABLE
            } else {
                SecretStorageState.MISSING
            },
            passphraseStorageState = if (identity.hasPassphrase) {
                SecretStorageState.AVAILABLE
            } else {
                SecretStorageState.MISSING
            },
        )
        identities.value = identities.value.filterNot { it.id == persisted.id } + persisted
        if (secrets != null) {
            this.secrets[persisted.id] = secrets
        }
        return persisted
    }

    override suspend fun getSecretMaterial(id: Long): IdentitySecretMaterial? = secrets[id]

    override suspend fun deleteIdentity(id: Long) {
        identities.value = identities.value.filterNot { it.id == id }
        secrets.remove(id)
    }
}

private class SessionTestKnownHostTrustRepository : KnownHostTrustRepository {
    private val trusts = MutableStateFlow<List<KnownHostTrust>>(emptyList())

    override fun observeTrustedHosts(): Flow<List<KnownHostTrust>> = trusts

    override suspend fun findTrustedHost(host: String, port: Int): KnownHostTrust? =
        trusts.value.firstOrNull { it.host == host && it.port == port }

    override suspend fun upsert(trust: KnownHostTrust): KnownHostTrust {
        trusts.value = trusts.value.filterNot { it.host == trust.host && it.port == trust.port } + trust
        return trust
    }

    override suspend fun deleteByEndpoint(host: String, port: Int) {
        trusts.value = trusts.value.filterNot { it.host == host && it.port == port }
    }

    fun snapshot(): List<KnownHostTrust> = trusts.value
}

private class SessionTestSessionMetadataRepository(
    initialSessions: List<SessionMetadata>,
) : SessionMetadataRepository {
    private val sessions = MutableStateFlow(initialSessions)

    override fun observeSessions(): Flow<List<SessionMetadata>> = sessions

    override suspend fun getSession(id: Long): SessionMetadata? = sessions.value.firstOrNull { it.id == id }

    override suspend fun upsert(sessionMetadata: SessionMetadata): SessionMetadata {
        val persisted = sessionMetadata.copy(
            id = sessionMetadata.id.takeIf { it != 0L } ?: ((sessions.value.maxOfOrNull(SessionMetadata::id) ?: 0L) + 1L),
        )
        sessions.value = sessions.value.filterNot { it.id == persisted.id } + persisted
        return persisted
    }

    override suspend fun deleteSession(id: Long) {
        sessions.value = sessions.value.filterNot { it.id == id }
    }

    override suspend fun clear() {
        sessions.value = emptyList()
    }
}

private sealed interface ConnectScript {
    data class Success(
        val endpoint: String,
        val proof: String,
        val trust: SessionTrustPayload?,
    ) : ConnectScript

    data class AuthenticationFailure(
        val trust: SessionTrustPayload?,
    ) : ConnectScript

    data object DnsFailure : ConnectScript

    data object NetworkFailure : ConnectScript

    data object TimeoutFailure : ConnectScript

    data class ChangedHostKey(
        val endpoint: String,
    ) : ConnectScript
}

private data class SessionTrustPayload(
    val endpoint: String,
    val fingerprint: String,
    val algorithm: String,
    val hostKeyBase64: String,
)

private class ScriptedSessionController(
    private val hostRepository: HostRepository,
    private val trustRepository: SessionTestKnownHostTrustRepository,
    scripts: Map<Long, ConnectScript>,
) : SessionController {
    private val state = MutableStateFlow(SessionUiState())
    private var scripts = scripts
    private var pendingHostId: Long? = null
    private var pendingTrust: SessionTrustPayload? = null
    private val terminalBuffer = TerminalBuffer()
    private val terminalUiState = MutableStateFlow(TerminalUiState(snapshot = terminalBuffer.snapshot()))

    override fun observeUiState() = state

    override fun connect(hostId: Long) {
        val host = runBlocking { hostRepository.getHost(hostId) } ?: return
        val script = scripts[hostId] ?: return
        when (script) {
            is ConnectScript.Success -> {
                val trusted = trustRepository.findTrustedHostBlocking(host.address, host.port)
                if (script.trust != null && trusted == null) {
                    pendingHostId = hostId
                    pendingTrust = script.trust
                    state.value = state.value.copy(
                        activeHostId = host.id,
                        activeHostLabel = host.label,
                        endpoint = host.endpoint,
                        connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTING,
                        statusMessage = "Trust required for ${host.endpoint}.",
                        pendingTrustDecision = PendingTrustDecision(
                            hostId = host.id,
                            hostLabel = host.label,
                            address = host.address,
                            port = host.port,
                            algorithm = script.trust.algorithm,
                            fingerprint = script.trust.fingerprint,
                            hostKeyBase64 = script.trust.hostKeyBase64,
                        ),
                    )
                } else {
                    terminalBuffer.clear()
                    terminalBuffer.append(script.proof)
                    emitTerminalState(canSendInput = true)
                    state.value = state.value.copy(
                        activeHostId = host.id,
                        activeHostLabel = host.label,
                        endpoint = host.endpoint,
                        connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTED,
                        statusMessage = "Connected to ${script.endpoint}.",
                        transcript = script.proof,
                        liveTerminalState = terminalUiState.value,
                        pendingTrustDecision = null,
                        lastError = null,
                    )
                }
            }

            is ConnectScript.AuthenticationFailure -> {
                val trusted = trustRepository.findTrustedHostBlocking(host.address, host.port)
                if (script.trust != null && trusted == null) {
                    pendingHostId = hostId
                    pendingTrust = script.trust
                    state.value = state.value.copy(
                        activeHostId = host.id,
                        activeHostLabel = host.label,
                        endpoint = host.endpoint,
                        connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTING,
                        statusMessage = "Trust required for ${host.endpoint}.",
                        pendingTrustDecision = PendingTrustDecision(
                            hostId = host.id,
                            hostLabel = host.label,
                            address = host.address,
                            port = host.port,
                            algorithm = script.trust.algorithm,
                            fingerprint = script.trust.fingerprint,
                            hostKeyBase64 = script.trust.hostKeyBase64,
                        ),
                    )
                } else {
                    state.value = state.value.copy(
                        activeHostId = host.id,
                        activeHostLabel = host.label,
                        endpoint = host.endpoint,
                        connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.FAILED,
                        statusMessage = "Authentication failed. Verify the linked identity and try again.",
                        pendingTrustDecision = null,
                        lastError = "Authentication failed. Verify the linked identity and try again.",
                    )
                }
            }

            ConnectScript.DnsFailure -> {
                state.value = state.value.copy(
                    activeHostId = host.id,
                    activeHostLabel = host.label,
                    endpoint = host.endpoint,
                    connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.FAILED,
                    statusMessage = "DNS lookup failed for ${host.endpoint}.",
                    lastError = "DNS lookup failed for ${host.endpoint}.",
                    pendingTrustDecision = null,
                )
            }

            ConnectScript.NetworkFailure -> {
                state.value = state.value.copy(
                    activeHostId = host.id,
                    activeHostLabel = host.label,
                    endpoint = host.endpoint,
                    connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.FAILED,
                    statusMessage = "Host is unreachable at ${host.endpoint}.",
                    lastError = "Host is unreachable at ${host.endpoint}.",
                    pendingTrustDecision = null,
                )
            }

            ConnectScript.TimeoutFailure -> {
                state.value = state.value.copy(
                    activeHostId = host.id,
                    activeHostLabel = host.label,
                    endpoint = host.endpoint,
                    connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.FAILED,
                    statusMessage = "Connection timed out while reaching ${host.endpoint}.",
                    lastError = "Connection timed out while reaching ${host.endpoint}.",
                    pendingTrustDecision = null,
                )
            }

            is ConnectScript.ChangedHostKey -> {
                state.value = state.value.copy(
                    activeHostId = host.id,
                    activeHostLabel = host.label,
                    endpoint = host.endpoint,
                    connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.FAILED,
                    statusMessage = "Host key changed for ${script.endpoint}. Connection blocked.",
                    lastError = "Host key changed for ${script.endpoint}. Connection blocked.",
                    pendingTrustDecision = null,
                )
            }
        }
    }

    override fun disconnect() {
        emitTerminalState(canSendInput = false)
        state.value = state.value.copy(
            connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.DISCONNECTED,
            statusMessage = "Disconnected.",
            liveTerminalState = terminalUiState.value,
            pendingTrustDecision = null,
        )
    }

    override fun submitHostTrustDecision(accept: Boolean) {
        val hostId = pendingHostId ?: return
        val trust = pendingTrust
        val host = runBlocking { hostRepository.getHost(hostId) } ?: return
        pendingHostId = null
        pendingTrust = null
        if (!accept) {
            state.value = state.value.copy(
                connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.FAILED,
                statusMessage = "Host trust rejected.",
                pendingTrustDecision = null,
            )
            return
        }
        if (trust != null) {
            runBlocking {
                trustRepository.upsert(
                    KnownHostTrust(
                        host = host.address,
                        port = host.port,
                        algorithm = trust.algorithm,
                        fingerprint = trust.fingerprint,
                        hostKeyBase64 = trust.hostKeyBase64,
                    ),
                )
            }
        }
        connect(hostId)
    }

    override fun sendInput(input: String) {
        if (state.value.connectionState == io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTED) {
            terminalBuffer.append(input)
            emitTerminalState(canSendInput = true)
            state.value = state.value.copy(transcript = state.value.transcript + input)
        }
    }

    override fun scrollPageUp() {
        terminalBuffer.scrollPageUp()
        emitTerminalState(canSendInput = state.value.canSendInput)
    }

    override fun scrollPageDown() {
        terminalBuffer.scrollPageDown()
        emitTerminalState(canSendInput = state.value.canSendInput)
    }

    override fun jumpToBottom() {
        terminalBuffer.jumpToBottom()
        emitTerminalState(canSendInput = state.value.canSendInput)
    }

    override fun resize(columns: Int, rows: Int) {
        terminalBuffer.resize(columns, rows)
        emitTerminalState(canSendInput = state.value.canSendInput)
    }

    fun setScripts(newScripts: Map<Long, ConnectScript>) {
        scripts = newScripts
    }

    private fun SessionTestKnownHostTrustRepository.findTrustedHostBlocking(host: String, port: Int): KnownHostTrust? =
        runBlocking { findTrustedHost(host, port) }

    private fun emitTerminalState(canSendInput: Boolean) {
        terminalUiState.value = TerminalUiState(
            snapshot = terminalBuffer.snapshot(),
            canSendInput = canSendInput,
        )
        state.value = state.value.copy(liveTerminalState = terminalUiState.value)
    }
}

private class RecoveryStateSessionController(
    private val hostRepository: HostRepository,
    metadataRepository: SessionMetadataRepository,
) : SessionController {
    private val state = MutableStateFlow(SessionUiState())

    init {
        val session = runBlocking { metadataRepository.observeSessions().first().first() }
        val host = runBlocking { hostRepository.getHost(session.hostId) } ?: error("Missing host")
        state.value = SessionUiState(
            activeHostId = host.id,
            activeHostLabel = host.label,
            endpoint = host.endpoint,
            connectionState = session.state,
            statusMessage = "Previous live session for ${host.endpoint} needs reconnect after app recovery.",
            lastError = session.lastError,
            reconnectRequired = session.reconnectRequired,
            disconnectReason = session.lastError,
        )
    }

    override fun observeUiState(): StateFlow<SessionUiState> = state

    override fun connect(hostId: Long) = Unit

    override fun disconnect() = Unit

    override fun submitHostTrustDecision(accept: Boolean) = Unit

    override fun sendInput(input: String) = Unit
}

private class ResizeRecordingSessionController : SessionController {
    private val state = MutableStateFlow(SessionUiState())
    val resizeEvents = mutableListOf<TerminalViewport>()

    override fun observeUiState(): StateFlow<SessionUiState> = state

    override fun connect(hostId: Long) = Unit

    override fun disconnect() = Unit

    override fun submitHostTrustDecision(accept: Boolean) = Unit

    override fun sendInput(input: String) = Unit

    override fun resize(viewport: TerminalViewport) {
        resizeEvents += viewport
    }
}

private class NoLocalEchoSessionController(
    hostRepository: HostRepository,
) : SessionController {
    private val connectedHost = runBlocking { hostRepository.getHost(1) } ?: error("Missing host")
    val sentInputs = mutableListOf<String>()
    private val state = MutableStateFlow(
        SessionUiState(
            activeHostId = connectedHost.id,
            activeHostLabel = connectedHost.label,
            endpoint = connectedHost.endpoint,
            connectionState = SessionConnectionState.CONNECTED,
            statusMessage = "Connected to ${connectedHost.endpoint}.",
            liveTerminalState = TerminalUiState(
                snapshot = TerminalBuffer().snapshot(),
                canSendInput = true,
            ),
        ),
    )

    override fun observeUiState(): StateFlow<SessionUiState> = state

    override fun connect(hostId: Long) = Unit

    override fun disconnect() = Unit

    override fun submitHostTrustDecision(accept: Boolean) = Unit

    override fun sendInput(input: String) {
        sentInputs += input
    }
}

private fun debugInput(input: String): String = when (input) {
    TerminalSpecialKey.CtrlC.encoded -> "CTRL_C"
    TerminalSpecialKey.Tab.encoded -> "TAB"
    TerminalSpecialKey.ArrowUp.encoded -> "ARROW_UP"
    TerminalSpecialKey.ArrowDown.encoded -> "ARROW_DOWN"
    TerminalSpecialKey.ArrowLeft.encoded -> "ARROW_LEFT"
    TerminalSpecialKey.ArrowRight.encoded -> "ARROW_RIGHT"
    TerminalSpecialKey.Esc.encoded -> "ESC"
    else -> input
}


private class SlowScriptedSessionController(
    private val hostRepository: HostRepository,
    private val releaseConnect: CountDownLatch,
    private val startedConnect: CountDownLatch,
    scripts: Map<Long, ConnectScript>,
) : SessionController {
    private val state = MutableStateFlow(SessionUiState())
    private val scripts = scripts
    val connectCalls = AtomicInteger(0)
    val completedConnections = AtomicInteger(0)

    override fun observeUiState(): StateFlow<SessionUiState> = state

    override fun connect(hostId: Long) {
        connectCalls.incrementAndGet()
        val host = runBlocking { hostRepository.getHost(hostId) } ?: return
        val script = scripts[hostId] ?: return
        startedConnect.countDown()
        state.value = state.value.copy(
            activeHostId = host.id,
            activeHostLabel = host.label,
            endpoint = host.endpoint,
            connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTING,
            statusMessage = "Connecting to ${host.endpoint}…",
            pendingTrustDecision = null,
            lastError = null,
            transcript = "",
        )
        Thread {
            releaseConnect.await(10, TimeUnit.SECONDS)
            if (state.value.connectionState != io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTING) {
                return@Thread
            }
            when (script) {
                is ConnectScript.Success -> {
                    completedConnections.incrementAndGet()
                    state.value = state.value.copy(
                        activeHostId = host.id,
                        activeHostLabel = host.label,
                        endpoint = host.endpoint,
                        connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.CONNECTED,
                        statusMessage = "Connected to ${script.endpoint}.",
                        transcript = script.proof,
                        pendingTrustDecision = null,
                        lastError = null,
                    )
                }

                else -> error("Slow controller only supports success scripts")
            }
        }.start()
    }

    override fun disconnect() {
        state.value = state.value.copy(
            connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.DISCONNECTED,
            statusMessage = "Connection canceled.",
            pendingTrustDecision = null,
        )
    }

    override fun submitHostTrustDecision(accept: Boolean) = Unit

    override fun sendInput(input: String) = Unit
}
