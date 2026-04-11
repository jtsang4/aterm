package io.github.jtsang4.aterm

import android.content.Context
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
import androidx.test.core.app.ApplicationProvider
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
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import io.github.jtsang4.aterm.core.ssh.PendingTrustDecision
import io.github.jtsang4.aterm.core.ssh.SessionController
import io.github.jtsang4.aterm.core.ssh.SessionUiState
import io.github.jtsang4.aterm.feature.session.SessionsScreen
import java.io.File
import java.security.KeyPair
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.util.security.SecurityUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionRealConnectionFlowsInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var passwordIdentityRepository: SessionTestIdentityRepository
    private lateinit var hostRepository: SessionTestHostRepository
    private lateinit var knownHostTrustRepository: SessionTestKnownHostTrustRepository
    private lateinit var controller: ScriptedSessionController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("aterm.db")
        File(context.filesDir.parentFile, "datastore").deleteRecursively()
    }

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
        state.value = state.value.copy(
            connectionState = io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.DISCONNECTED,
            statusMessage = "Disconnected.",
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
            state.value = state.value.copy(transcript = state.value.transcript + "\n" + input.trim())
        }
    }

    fun setScripts(newScripts: Map<Long, ConnectScript>) {
        scripts = newScripts
    }

    private fun SessionTestKnownHostTrustRepository.findTrustedHostBlocking(host: String, port: Int): KnownHostTrust? =
        runBlocking { findTrustedHost(host, port) }
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
