package io.github.jtsang4.aterm.core.ssh

import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketAddress
import java.security.KeyPair
import java.security.PublicKey
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.channel.PtyChannelConfiguration
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.util.security.SecurityUtils

class SshSessionCoordinator(
    private val hostRepository: HostRepository,
    private val identityRepository: IdentityRepository,
    private val knownHostTrustRepository: KnownHostTrustRepository,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SessionController {
    private val connectMutex = Mutex()
    private val stateMutex = Mutex()
    private val uiState = MutableStateFlow(SessionUiState())
    private var runtimeConnection: RuntimeConnection? = null
    private var pendingDecisionAccepted: Boolean? = null
    private var pendingDecisionLatch: CountDownLatch? = null

    override fun observeUiState(): StateFlow<SessionUiState> = uiState.asStateFlow()

    override fun connect(hostId: Long) {
        ioScope.launch {
            connectMutex.withLock {
                connectInternal(hostId)
            }
        }
    }

    override fun disconnect() {
        ioScope.launch {
            disconnectInternal(
                state = SessionConnectionState.DISCONNECTED,
                statusMessage = "Disconnected.",
                lastError = null,
            )
        }
    }

    override fun submitHostTrustDecision(accept: Boolean) {
        pendingDecisionAccepted = accept
        pendingDecisionLatch?.countDown()
    }

    override fun sendInput(input: String) {
        ioScope.launch {
            val connection = runtimeConnection ?: return@launch
            runCatching {
                connection.stdin.write(input.encodeToByteArray())
                connection.stdin.flush()
                appendTranscript(input)
            }.onFailure { throwable ->
                failConnection(
                    hostId = connection.host.id,
                    host = connection.host,
                    message = "Input failed: ${throwable.message ?: "Unable to send input."}",
                )
            }
        }
    }

    private suspend fun connectInternal(hostId: Long) {
        val result = runCatching {
            val host = requireNotNull(hostRepository.getHost(hostId)) {
                "Host is missing."
            }
            val identityId = requireNotNull(host.identityId) {
                "The linked identity is missing. Repair this host before connecting."
            }
            val identity = requireNotNull(identityRepository.getIdentity(identityId)) {
                "The linked identity is missing. Repair this host before connecting."
            }
            if (!identity.isAuthenticationReady) {
                error("The linked identity needs repair before connecting.")
            }
            val secrets = identityRepository.getSecretMaterial(identity.id)
                ?: error("Stored identity secret is unavailable.")

            disconnectInternal(
                state = SessionConnectionState.DISCONNECTED,
                statusMessage = null,
                lastError = null,
                clearTranscript = false,
            )

            updateState(
                host = host,
                state = SessionConnectionState.CONNECTING,
                statusMessage = "Connecting to ${host.endpoint}…",
                transcript = "",
                lastError = null,
                pendingTrustDecision = null,
            )

            val client = SshClient.setUpDefaultClient().apply {
                serverKeyVerifier = BlockingTrustVerifier(host)
                start()
            }
            val session = client.connect(host.username, host.address, host.port)
                .verify(CONNECT_TIMEOUT_MILLIS)
                .session
            authenticate(session, identity, secrets)

            val channel = session.createShellChannel(PtyChannelConfiguration(), emptyMap<String, Any>()).apply {
                setRedirectErrorStream(true)
                open().verify(CHANNEL_OPEN_TIMEOUT_MILLIS)
            }
            val connection = RuntimeConnection(
                host = host,
                client = client,
                session = session,
                channel = channel,
                stdin = requireNotNull(channel.invertedIn) { "SSH input stream is unavailable." },
                stdout = requireNotNull(channel.invertedOut) { "SSH output stream is unavailable." },
            )
            runtimeConnection = connection

            updateState(
                host = host,
                state = SessionConnectionState.CONNECTED,
                statusMessage = "Connected to ${host.endpoint}.",
                pendingTrustDecision = null,
                lastError = null,
                transcript = "",
            )
            hostRepository.markUsed(host.id, Instant.now())
            startReader(connection)
            sendInput("${proofCommand(host)}\n")
        }

        result.onFailure { throwable ->
            val host = hostRepository.getHost(hostId)
            val message = throwable.toUserMessage(host)
            if (host != null) {
                failConnection(hostId, host, message)
            } else {
                stateMutex.withLock {
                    uiState.value = uiState.value.copy(
                        connectionState = SessionConnectionState.FAILED,
                        statusMessage = message,
                        lastError = message,
                        pendingTrustDecision = null,
                    )
                }
            }
        }
    }

    private suspend fun authenticate(
        session: ClientSession,
        identity: Identity,
        secrets: IdentitySecretMaterial,
    ) {
        when (identity.kind) {
            IdentityKind.PASSWORD -> {
                val password = secrets.primarySecret ?: error("Password secret is unavailable.")
                session.addPasswordIdentity(password)
            }

            IdentityKind.IMPORTED_KEY,
            IdentityKind.GENERATED_KEY,
            -> {
                val privateKey = secrets.primarySecret ?: error("Private key secret is unavailable.")
                session.addPublicKeyIdentity(loadKeyPair(privateKey, secrets.passphrase))
            }
        }
        session.auth().verify(AUTH_TIMEOUT_MILLIS)
    }

    private fun startReader(connection: RuntimeConnection) {
        ioScope.launch {
            val buffer = ByteArray(1024)
            runCatching {
                while (true) {
                    val read = connection.stdout.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    appendTranscript(String(buffer, 0, read))
                }
            }.onFailure { throwable ->
                failConnection(
                    hostId = connection.host.id,
                    host = connection.host,
                    message = "Session ended: ${throwable.message ?: "Remote shell closed."}",
                )
            }
        }
    }

    private suspend fun appendTranscript(text: String) {
        stateMutex.withLock {
            uiState.value = uiState.value.copy(
                transcript = uiState.value.transcript + text,
            )
        }
    }

    private suspend fun disconnectInternal(
        state: SessionConnectionState,
        statusMessage: String?,
        lastError: String?,
        clearTranscript: Boolean = false,
    ) {
        stateMutex.withLock {
            pendingDecisionLatch?.countDown()
            pendingDecisionLatch = null
            pendingDecisionAccepted = null
            runtimeConnection?.let { connection ->
                runCatching { connection.channel.close(false) }
                runCatching { connection.session.close(false) }
                runCatching { connection.client.stop() }
            }
            runtimeConnection = null
            uiState.value = uiState.value.copy(
                connectionState = state,
                statusMessage = statusMessage,
                lastError = lastError,
                pendingTrustDecision = null,
                transcript = if (clearTranscript) "" else uiState.value.transcript,
            )
        }
    }

    private suspend fun updateState(
        host: Host,
        state: SessionConnectionState,
        statusMessage: String?,
        transcript: String = uiState.value.transcript,
        lastError: String? = uiState.value.lastError,
        pendingTrustDecision: PendingTrustDecision? = uiState.value.pendingTrustDecision,
    ) {
        stateMutex.withLock {
            uiState.value = uiState.value.copy(
                activeHostId = host.id,
                activeHostLabel = host.label,
                endpoint = host.endpoint,
                connectionState = state,
                statusMessage = statusMessage,
                transcript = transcript,
                lastError = lastError,
                pendingTrustDecision = pendingTrustDecision,
            )
        }
    }

    private suspend fun failConnection(hostId: Long, host: Host, message: String) {
        disconnectInternal(
            state = SessionConnectionState.FAILED,
            statusMessage = message,
            lastError = message,
            clearTranscript = false,
        )
        updateState(
            host = host,
            state = SessionConnectionState.FAILED,
            statusMessage = message,
            lastError = message,
        )
    }

    private inner class BlockingTrustVerifier(
        private val host: Host,
    ) : ServerKeyVerifier {
        override fun verifyServerKey(
            clientSession: ClientSession,
            remoteAddress: SocketAddress,
            serverKey: PublicKey,
        ): Boolean {
            val encoded = serverKey.encoded ?: return false
            val hostKeyBase64 = Base64.getEncoder().encodeToString(encoded)
            val fingerprint = BuiltinDigests.sha256.create().let { digest ->
                digest.update(encoded)
                "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest.digest())}"
            }
            val trusted = runBlocking {
                knownHostTrustRepository.findTrustedHost(host.address, host.port)
            }
            if (trusted == null) {
                val decision = PendingTrustDecision(
                    hostId = host.id,
                    hostLabel = host.label,
                    address = host.address,
                    port = host.port,
                    algorithm = serverKey.algorithm,
                    fingerprint = fingerprint,
                    hostKeyBase64 = hostKeyBase64,
                )
                uiState.value = uiState.value.copy(
                    pendingTrustDecision = decision,
                    statusMessage = "Trust required for ${host.endpoint}.",
                )
                pendingDecisionAccepted = null
                pendingDecisionLatch = CountDownLatch(1)
                pendingDecisionLatch?.await(TRUST_DECISION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                val accepted = pendingDecisionAccepted == true
                if (accepted) {
                    runBlocking {
                        knownHostTrustRepository.upsert(
                            KnownHostTrust(
                                host = host.address,
                                port = host.port,
                                algorithm = serverKey.algorithm,
                                fingerprint = fingerprint,
                                hostKeyBase64 = hostKeyBase64,
                            ),
                        )
                    }
                    uiState.value = uiState.value.copy(
                        pendingTrustDecision = null,
                        statusMessage = "Trusted ${host.endpoint}. Continuing…",
                    )
                }
                return accepted
            }
            if (trusted.hostKeyBase64 != hostKeyBase64) {
                uiState.value = uiState.value.copy(
                    pendingTrustDecision = null,
                    statusMessage = "Host key changed for ${host.endpoint}. Connection blocked.",
                    lastError = "Host key changed for ${host.endpoint}.",
                )
                return false
            }
            return true
        }
    }

    private fun loadKeyPair(privateKey: String, passphrase: String?): KeyPair {
        val pairs = ByteArrayInputStream(privateKey.encodeToByteArray()).use { input ->
            SecurityUtils.loadKeyPairIdentities(
                null,
                IMPORT_RESOURCE,
                input,
                passphrase?.takeIf(String::isNotBlank)?.let(FilePasswordProvider::of)
                    ?: FilePasswordProvider.EMPTY,
            )
        }?.toList().orEmpty()
        return pairs.firstOrNull() ?: error("Private key could not be loaded.")
    }

    private fun Throwable.toUserMessage(host: Host?): String {
        val endpoint = host?.endpoint ?: uiState.value.endpoint ?: "the saved endpoint"
        val rawMessage = message.orEmpty()
        return when {
            rawMessage.contains("Host key changed", ignoreCase = true) ->
                "Host key changed for $endpoint. Connection blocked."
            rawMessage.contains("Permission denied", ignoreCase = true) ||
                rawMessage.contains("auth", ignoreCase = true) ->
                "Authentication failed. Verify the linked identity and try again."
            rawMessage.contains("unknownhost", ignoreCase = true) ||
                rawMessage.contains("unresolved", ignoreCase = true) ->
                "DNS lookup failed for $endpoint."
            rawMessage.contains("timed out", ignoreCase = true) ||
                rawMessage.contains("timeout", ignoreCase = true) ->
                "Connection timed out while reaching $endpoint."
            rawMessage.contains("refused", ignoreCase = true) ||
                rawMessage.contains("unreachable", ignoreCase = true) ||
                rawMessage.contains("no route", ignoreCase = true) ->
                "Host is unreachable at $endpoint."
            rawMessage.contains("repair", ignoreCase = true) ||
                rawMessage.contains("missing", ignoreCase = true) ->
                rawMessage
            else -> rawMessage.ifBlank { "Connection failed." }
        }
    }

    private fun proofCommand(host: Host): String =
        "printf 'ATERM_REMOTE_PROOF:${host.address}:${host.port}:'; hostname"

    private data class RuntimeConnection(
        val host: Host,
        val client: SshClient,
        val session: ClientSession,
        val channel: ChannelShell,
        val stdin: OutputStream,
        val stdout: InputStream,
    )

    companion object {
        private val IMPORT_RESOURCE = NamedResource { "saved-private-key" }
        private const val CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val AUTH_TIMEOUT_MILLIS = 5_000L
        private const val CHANNEL_OPEN_TIMEOUT_MILLIS = 5_000L
        private const val TRUST_DECISION_TIMEOUT_MILLIS = 30_000L
    }
}
