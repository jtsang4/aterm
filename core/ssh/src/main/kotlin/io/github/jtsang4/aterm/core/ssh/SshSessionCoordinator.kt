package io.github.jtsang4.aterm.core.ssh
import io.github.jtsang4.aterm.core.domain.PrivateKeyMaterialFormat
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.connectionBlockedMessage
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import io.github.jtsang4.aterm.core.domain.repository.SessionMetadataRepository
import io.github.jtsang4.aterm.core.domain.model.SessionMetadata
import io.github.jtsang4.aterm.core.terminal.AuthoritativeTerminalSession
import io.github.jtsang4.aterm.core.terminal.TerminalSpecialKey
import io.github.jtsang4.aterm.core.terminal.TerminalUiState
import io.github.jtsang4.aterm.core.terminal.TerminalViewport
import io.github.jtsang4.aterm.core.terminal.calculateTerminalViewport
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.SocketAddress
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.channel.PtyChannelConfiguration
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.future.Cancellable
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils

class SshSessionCoordinator(
    private val hostRepository: HostRepository,
    private val identityRepository: IdentityRepository,
    private val knownHostTrustRepository: KnownHostTrustRepository,
    private val sessionMetadataRepository: SessionMetadataRepository? = null,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SessionController {
    private val authoritativeTerminalSession = AuthoritativeTerminalSession(
        TerminalViewport(
            columns = DEFAULT_TERMINAL_COLUMNS,
            rows = DEFAULT_TERMINAL_ROWS,
            widthPx = DEFAULT_TERMINAL_COLUMNS * CELL_WIDTH_PIXELS,
            heightPx = DEFAULT_TERMINAL_ROWS * CELL_HEIGHT_PIXELS,
        ),
    )
    private val connectMutex = Mutex()
    private val stateMutex = Mutex()
    private val inputWriteMutex = Mutex()
    private val uiState = MutableStateFlow(SessionUiState())
    private val terminalUiState = MutableStateFlow(TerminalUiState())
    private var runtimeConnection: RuntimeConnection? = null
    private var currentAttempt: ConnectAttempt? = null
    private var pendingDecisionAccepted: Boolean? = null
    private var pendingDecisionLatch: CountDownLatch? = null
    private var latestTerminalColumns: Int = DEFAULT_TERMINAL_COLUMNS
    private var latestTerminalRows: Int = DEFAULT_TERMINAL_ROWS
    private var latestTerminalWidthPx: Int = DEFAULT_TERMINAL_COLUMNS * CELL_WIDTH_PIXELS
    private var latestTerminalHeightPx: Int = DEFAULT_TERMINAL_ROWS * CELL_HEIGHT_PIXELS
    private var currentSessionMetadataId: Long? = null
    @Volatile
    private var pendingResizeJob: Job? = null

    init {
        authoritativeTerminalSession.addListener(
            object : AuthoritativeTerminalSession.Listener {
                override fun onTerminalSnapshotChanged(snapshot: io.github.jtsang4.aterm.core.terminal.TerminalSnapshot) = Unit

                override fun onTerminalTextChanged(text: String) {
                    ioScope.launch {
                        stateMutex.withLock {
                            if (uiState.value.transcript != text) {
                                uiState.value = uiState.value.copy(transcript = text)
                            }
                        }
                    }
                }
            },
        )
        emitTerminalState(canSendInput = false)
        restorePersistedSessionTruth()
    }

    fun close() {
        runBlocking {
            stateMutex.withLock {
                val attempt = currentAttempt
                currentAttempt = null
                attempt?.cancel()
            }
            disconnectInternal(
                state = SessionConnectionState.DISCONNECTED,
                statusMessage = null,
                lastError = null,
                clearTranscript = false,
                reconnectRequired = false,
                disconnectReason = null,
            )
        }
        ioScope.cancel()
    }

    override fun observeUiState(): StateFlow<SessionUiState> = uiState.asStateFlow()

    override fun connect(hostId: Long) {
        ioScope.launch {
            val attempt = stateMutex.withLock {
                if (currentAttempt != null) {
                    null
                } else {
                    ConnectAttempt(id = nextAttemptId.getAndIncrement()).also { currentAttempt = it }
                }
            } ?: return@launch
            connectMutex.withLock {
                try {
                    connectInternal(hostId, attempt)
                } finally {
                    stateMutex.withLock {
                        if (currentAttempt?.id == attempt.id) {
                            currentAttempt = null
                        }
                    }
                }
            }
        }
    }

    override fun disconnect() {
        ioScope.launch {
            val cancelingConnect = stateMutex.withLock {
                val attempt = currentAttempt
                currentAttempt = null
                attempt?.cancel()
                uiState.value.isConnecting
            }
            val host = uiState.value.activeHostId?.let { hostRepository.getHost(it) }
            disconnectInternal(
                state = SessionConnectionState.DISCONNECTED,
                statusMessage = if (cancelingConnect) "Connection canceled." else "Disconnected.",
                lastError = null,
            )
            if (host != null) {
                persistSessionState(
                    host = host,
                    state = SessionConnectionState.DISCONNECTED,
                    reconnectRequired = false,
                    lastError = null,
                    disconnectedAt = Instant.now(),
                )
            }
        }
    }

    override fun submitHostTrustDecision(accept: Boolean) {
        pendingDecisionAccepted = accept
        pendingDecisionLatch?.countDown()
    }

    override fun sendInput(input: String) {
        ioScope.launch {
            val connection = runtimeConnection ?: return@launch
            writeInput(connection, input).onFailure { throwable ->
                failConnection(
                    host = connection.host,
                    message = "Input failed: ${throwable.message ?: "Unable to send input."}",
                )
            }
        }
    }

    override suspend fun dispatchToActiveSession(input: String): SessionDispatchResult {
        val connection = runtimeConnection
            ?: return SessionDispatchResult.Failure("No active session is available.")
        if (!uiState.value.isTerminalLive) {
            return SessionDispatchResult.Failure(
                uiState.value.disconnectReason ?: "The current session is no longer live.",
            )
        }
        return writeInput(connection, input).map {
            SessionDispatchResult.Success
        }.getOrElse { throwable ->
            failConnection(
                host = connection.host,
                message = "Input failed: ${throwable.message ?: "Unable to send input."}",
                reconnectRequired = true,
            )
            SessionDispatchResult.Failure(
                uiState.value.disconnectReason ?: "Unable to dispatch into the active session.",
            )
        }
    }

    private suspend fun writeInput(
        connection: RuntimeConnection,
        input: String,
    ): Result<Unit> = runCatching {
        pendingResizeJob?.join()
        inputWriteMutex.withLock {
            withContext(Dispatchers.IO) {
                connection.stdin.write(input.encodeToByteArray())
                connection.stdin.flush()
            }
        }
    }

    override fun scrollPageUp() {
        authoritativeTerminalSession.scrollPageUp()
        emitTerminalState(canSendInput = uiState.value.canSendInput)
    }

    override fun scrollPageDown() {
        authoritativeTerminalSession.scrollPageDown()
        emitTerminalState(canSendInput = uiState.value.canSendInput)
    }

    override fun jumpToBottom() {
        authoritativeTerminalSession.jumpToBottom()
        emitTerminalState(canSendInput = uiState.value.canSendInput)
    }

    override fun resize(columns: Int, rows: Int) {
        val safeColumns = columns.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)
        latestTerminalColumns = safeColumns
        latestTerminalRows = safeRows
        latestTerminalWidthPx = safeColumns * CELL_WIDTH_PIXELS
        latestTerminalHeightPx = safeRows * CELL_HEIGHT_PIXELS
        authoritativeTerminalSession.resize(
            TerminalViewport(
                columns = safeColumns,
                rows = safeRows,
                widthPx = latestTerminalWidthPx,
                heightPx = latestTerminalHeightPx,
            ),
        )
        emitTerminalState(canSendInput = uiState.value.canSendInput)
        val resizeJob = ioScope.launch {
            runtimeConnection?.sendResize(
                columns = safeColumns,
                rows = safeRows,
                widthPx = latestTerminalWidthPx,
                heightPx = latestTerminalHeightPx,
            )
        }
        pendingResizeJob = resizeJob
        resizeJob.invokeOnCompletion {
            if (pendingResizeJob === resizeJob) {
                pendingResizeJob = null
            }
        }
    }

    override fun resize(viewport: TerminalViewport) {
        val safeColumns = viewport.columns.coerceAtLeast(1)
        val safeRows = viewport.rows.coerceAtLeast(1)
        latestTerminalColumns = safeColumns
        latestTerminalRows = safeRows
        latestTerminalWidthPx = viewport.widthPx.coerceAtLeast(safeColumns)
        latestTerminalHeightPx = viewport.heightPx.coerceAtLeast(safeRows)
        authoritativeTerminalSession.resize(
            TerminalViewport(
                columns = safeColumns,
                rows = safeRows,
                widthPx = latestTerminalWidthPx,
                heightPx = latestTerminalHeightPx,
            ),
        )
        emitTerminalState(canSendInput = uiState.value.canSendInput)
        val resizeJob = ioScope.launch {
            runtimeConnection?.sendResize(
                columns = safeColumns,
                rows = safeRows,
                widthPx = latestTerminalWidthPx,
                heightPx = latestTerminalHeightPx,
            )
        }
        pendingResizeJob = resizeJob
        resizeJob.invokeOnCompletion {
            if (pendingResizeJob === resizeJob) {
                pendingResizeJob = null
            }
        }
    }

    override fun sendText(text: String) = sendInput(text)

    override fun sendSpecialKey(key: TerminalSpecialKey) = sendInput(key.encoded)

    override fun pasteText(text: String) = sendInput(text)

    override fun setTerminalFontScale(scale: Float) {
        authoritativeTerminalSession.updateFontScale(scale)
        emitTerminalState(canSendInput = uiState.value.canSendInput)
        val viewport = calculateTerminalViewport(
            contentWidthPx = latestTerminalWidthPx,
            contentHeightPx = latestTerminalHeightPx,
            cellWidthPx = terminalUiState.value.cellWidthPx,
            cellHeightPx = terminalUiState.value.cellHeightPx,
        ) ?: return
        resize(viewport)
    }

    private suspend fun connectInternal(hostId: Long, attempt: ConnectAttempt) {
        val result = runCatching {
            attempt.ensureActive()
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
                error(identity.connectionBlockedMessage())
            }
            val secrets = identityRepository.getSecretMaterial(identity.id)
                ?: error("Stored identity secret is unavailable.")

            attempt.ensureActive()
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
                reconnectRequired = false,
                disconnectReason = null,
            )
            replaceTerminalContent("")
            persistSessionState(
                host = host,
                state = SessionConnectionState.CONNECTING,
                reconnectRequired = false,
                lastError = null,
                disconnectedAt = null,
            )

            attempt.ensureActive()
            ensureAndroidFriendlySshdDefaults()
            val client = SshClient.setUpDefaultClient().apply {
                serverKeyVerifier = BlockingTrustVerifier(host, attempt)
                start()
            }
            attempt.registerCancelAction { runCatching { client.stop() } }
            val connectFuture = client.connect(host.username, host.address, host.port)
            attempt.register(connectFuture)
            val session = connectFuture.verify(CONNECT_TIMEOUT_MILLIS)
                .session
            attempt.ensureActive()
            attempt.registerCancelAction { runCatching { session.close(false) } }
            authenticate(session, identity, secrets, attempt)

            val channel = session.createShellChannel(
                PtyChannelConfiguration().apply {
                    ptyType = "xterm-256color"
                    ptyColumns = latestTerminalColumns
                    ptyLines = latestTerminalRows
                    ptyWidth = latestTerminalWidthPx
                    ptyHeight = latestTerminalHeightPx
                },
                emptyMap<String, Any>(),
            ).apply {
                setUsePty(true)
                setupSensibleDefaultPty()
                ptyType = "xterm-256color"
                ptyColumns = latestTerminalColumns
                ptyLines = latestTerminalRows
                ptyWidth = latestTerminalWidthPx
                ptyHeight = latestTerminalHeightPx
                setRedirectErrorStream(true)
                val openFuture = open()
                attempt.register(openFuture)
                openFuture.verify(CHANNEL_OPEN_TIMEOUT_MILLIS)
            }
            attempt.ensureActive()
            val connection = RuntimeConnection(
                host = host,
                client = client,
                session = session,
                channel = channel,
                stdin = requireNotNull(channel.invertedIn) { "SSH input stream is unavailable." },
                stdout = requireNotNull(channel.invertedOut) { "SSH output stream is unavailable." },
            )
            attempt.registerCancelAction {
                connection.closing.set(true)
                runCatching { connection.channel.close(false) }
                runCatching { connection.session.close(false) }
                runCatching { connection.client.stop() }
            }
            runtimeConnection = connection

            attempt.ensureActive()
            updateState(
                host = host,
                state = SessionConnectionState.CONNECTED,
                statusMessage = "Connected to ${host.endpoint}.",
                pendingTrustDecision = null,
                lastError = null,
                transcript = "",
                reconnectRequired = false,
                disconnectReason = null,
            )
            emitTerminalState(canSendInput = true)
            persistSessionState(
                host = host,
                state = SessionConnectionState.CONNECTED,
                reconnectRequired = false,
                lastError = null,
                connectedAt = Instant.now(),
                disconnectedAt = null,
            )
            hostRepository.markUsed(host.id, Instant.now())
            startReader(connection)
            writeInput(connection, "${proofCommand(host)}\n").onFailure { throwable ->
                failConnection(
                    host = host,
                    message = "Input failed: ${throwable.message ?: "Unable to send input."}",
                    reconnectRequired = true,
                )
            }
        }

        result.onFailure { throwable ->
            if (throwable is ConnectCanceledException || attempt.isCanceled) {
                return@onFailure
            }
            val host = hostRepository.getHost(hostId)
            val message = throwable.toUserMessage(host)
            if (host != null) {
                failConnection(host, message)
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
        attempt: ConnectAttempt,
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
        val authFuture = session.auth()
        attempt.register(authFuture)
        authFuture.verify(authenticationTimeoutMillis(identity))
        attempt.ensureActive()
    }

    private fun startReader(connection: RuntimeConnection) {
        ioScope.launch {
            val buffer = ByteArray(1024)
            val failure = runCatching {
                while (true) {
                    val read = connection.stdout.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    appendTranscript(String(buffer, 0, read))
                }
            }.exceptionOrNull()

            if (connection.closing.get() || runtimeConnection !== connection) {
                return@launch
            }

            if (failure != null) {
                failConnection(
                    host = connection.host,
                    message = "Session ended: ${failure.message ?: "Remote shell closed."}",
                    reconnectRequired = true,
                )
            } else {
                failConnection(
                    host = connection.host,
                    message = "Remote shell closed for ${connection.host.endpoint}.",
                    reconnectRequired = true,
                )
            }
        }
    }

    private suspend fun appendTranscript(text: String) {
        authoritativeTerminalSession.appendRemoteText(text)
        emitTerminalState(canSendInput = uiState.value.canSendInput)
        stateMutex.withLock {
            uiState.value = uiState.value.copy(
                transcript = authoritativeTerminalSession.completeText(),
            )
        }
    }

    private suspend fun disconnectInternal(
        state: SessionConnectionState,
        statusMessage: String?,
        lastError: String?,
        clearTranscript: Boolean = false,
        reconnectRequired: Boolean = false,
        disconnectReason: String? = statusMessage,
    ) {
        stateMutex.withLock {
            pendingDecisionLatch?.countDown()
            pendingDecisionLatch = null
            pendingDecisionAccepted = null
            runtimeConnection?.let { connection ->
                connection.closing.set(true)
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
                reconnectRequired = reconnectRequired,
                disconnectReason = disconnectReason,
            )
        }
        emitTerminalState(canSendInput = false)
    }

    private suspend fun updateState(
        host: Host,
        state: SessionConnectionState,
        statusMessage: String?,
        transcript: String = uiState.value.transcript,
        lastError: String? = uiState.value.lastError,
        pendingTrustDecision: PendingTrustDecision? = uiState.value.pendingTrustDecision,
        reconnectRequired: Boolean = uiState.value.reconnectRequired,
        disconnectReason: String? = uiState.value.disconnectReason,
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
                reconnectRequired = reconnectRequired,
                disconnectReason = disconnectReason,
            )
        }
    }

    private suspend fun failConnection(
        host: Host,
        message: String,
        reconnectRequired: Boolean = false,
    ) {
        val failureState = if (reconnectRequired) {
            SessionConnectionState.RECONNECT_REQUIRED
        } else {
            SessionConnectionState.FAILED
        }
        disconnectInternal(
            state = failureState,
            statusMessage = message,
            lastError = message,
            clearTranscript = false,
            reconnectRequired = reconnectRequired,
            disconnectReason = message,
        )
        updateState(
            host = host,
            state = failureState,
            statusMessage = message,
            lastError = message,
            reconnectRequired = reconnectRequired,
            disconnectReason = message,
        )
        persistSessionState(
            host = host,
            state = failureState,
            reconnectRequired = reconnectRequired,
            lastError = message,
            disconnectedAt = Instant.now(),
        )
    }

    private inner class BlockingTrustVerifier(
        private val host: Host,
        private val attempt: ConnectAttempt,
    ) : ServerKeyVerifier {
        override fun verifyServerKey(
            clientSession: ClientSession,
            remoteAddress: SocketAddress,
            serverKey: PublicKey,
        ): Boolean {
            attempt.ensureActive()
            val encoded = serverKey.encoded ?: return false
            val hostKeyBase64 = Base64.getEncoder().encodeToString(encoded)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(encoded).let { digest ->
                "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
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
                attempt.registerCancelAction { pendingDecisionLatch?.countDown() }
                pendingDecisionLatch?.await(TRUST_DECISION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                attempt.ensureActive()
                val accepted = pendingDecisionAccepted
                pendingDecisionLatch = null
                if (accepted == true) {
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
                } else {
                    throw IllegalStateException("Host trust rejected.")
                }
                return accepted == true
            }
            if (trusted.hostKeyBase64 != hostKeyBase64) {
                throw IllegalStateException("Host key changed for ${host.endpoint}.")
            }
            return true
        }
    }

    private fun ensureAndroidFriendlySshdDefaults() {
        if (userHomeConfigured.compareAndSet(false, true)) {
            PathUtils.setUserHomeFolderResolver {
                Paths.get(File(System.getProperty("java.io.tmpdir") ?: ".").absolutePath)
                    .toAbsolutePath()
                    .normalize()
            }
        }
    }

    private fun loadKeyPair(privateKey: String, passphrase: String?): KeyPair {
        ensureBouncyCastleRegistered()
        if (looksLikePemPrivateKey(privateKey)) {
            return loadPemKeyPair(privateKey, passphrase)
        }
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

    private fun loadPemKeyPair(
        privateKey: String,
        passphrase: String?,
    ): KeyPair {
        val converter = JcaPEMKeyConverter().setProvider(BOUNCY_CASTLE_PROVIDER)
        return PEMParser(StringReader(privateKey)).use { parser ->
            when (val parsed = parser.readObject() ?: error("Private key could not be loaded.")) {
                is PEMEncryptedKeyPair -> {
                    val password = passphrase?.takeIf(String::isNotBlank)?.toCharArray()
                        ?: error("Private key could not be loaded.")
                    converter.getKeyPair(
                        parsed.decryptKeyPair(
                            JcePEMDecryptorProviderBuilder()
                                .setProvider(BOUNCY_CASTLE_PROVIDER)
                                .build(password),
                        ),
                    )
                }

                is PEMKeyPair -> converter.getKeyPair(parsed)

                is PKCS8EncryptedPrivateKeyInfo -> {
                    val password = passphrase?.takeIf(String::isNotBlank)?.toCharArray()
                        ?: error("Private key could not be loaded.")
                    val decrypted = parsed.decryptPrivateKeyInfo(
                        JceOpenSSLPKCS8DecryptorProviderBuilder()
                            .setProvider(BOUNCY_CASTLE_PROVIDER)
                            .build(password),
                    )
                    keyPairFromPrivateKeyInfo(converter, decrypted)
                }

                is PrivateKeyInfo -> keyPairFromPrivateKeyInfo(converter, parsed)

                else -> error("Private key could not be loaded.")
            }
        }
    }

    private fun keyPairFromPrivateKeyInfo(
        converter: JcaPEMKeyConverter,
        privateKeyInfo: PrivateKeyInfo,
    ): KeyPair {
        val privateKey = converter.getPrivateKey(privateKeyInfo)
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = when (privateKey) {
            is RSAPrivateCrtKey -> {
                keyFactory.generatePublic(
                    RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
                )
            }

            else -> error("Private key could not be loaded.")
        }
        return KeyPair(publicKey, privateKey)
    }

    private fun normalizeKeyPair(keyPair: KeyPair): KeyPair {
        val privateKey = keyPair.private
        return when (privateKey) {
            is RSAPrivateCrtKey -> {
                val keyFactory = KeyFactory.getInstance("RSA")
                val normalizedPublicKey = keyFactory.generatePublic(
                    RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
                )
                val normalizedPrivateKey = keyFactory.generatePrivate(
                    RSAPrivateCrtKeySpec(
                        privateKey.modulus,
                        privateKey.publicExponent,
                        privateKey.privateExponent,
                        privateKey.primeP,
                        privateKey.primeQ,
                        privateKey.primeExponentP,
                        privateKey.primeExponentQ,
                        privateKey.crtCoefficient,
                    ),
                )
                KeyPair(normalizedPublicKey, normalizedPrivateKey)
            }

            else -> keyPair
        }
    }

    private fun Throwable.toUserMessage(host: Host?): String {
        val endpoint = host?.endpoint ?: uiState.value.endpoint ?: "the saved endpoint"
        val rawMessage = message.orEmpty()
        return when {
            rawMessage.contains("trust rejected", ignoreCase = true) ->
                "Host trust rejected."
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

    private fun emitTerminalState(canSendInput: Boolean) {
        val rendererMetrics = authoritativeTerminalSession.rendererMetrics()
        val state = TerminalUiState(
            snapshot = authoritativeTerminalSession.snapshot(),
            canSendInput = canSendInput,
            authoritativeSession = authoritativeTerminalSession,
            cellWidthPx = rendererMetrics.cellWidthPx.toInt().coerceAtLeast(1),
            cellHeightPx = rendererMetrics.cellHeightPx.coerceAtLeast(1),
            fontScale = authoritativeTerminalSession.currentFontScale(),
        )
        terminalUiState.value = state
        uiState.update { current ->
            current.copy(liveTerminalState = state)
        }
    }

    private fun replaceTerminalContent(transcript: String) {
        authoritativeTerminalSession.reset(
            TerminalViewport(
                columns = latestTerminalColumns,
                rows = latestTerminalRows,
                widthPx = latestTerminalWidthPx,
                heightPx = latestTerminalHeightPx,
            ),
        )
        if (transcript.isNotEmpty()) {
            authoritativeTerminalSession.appendRemoteText(transcript)
        }
        emitTerminalState(canSendInput = uiState.value.canSendInput)
    }

    private fun restorePersistedSessionTruth() {
        val repository = sessionMetadataRepository ?: return
        ioScope.launch {
            val lastSession = repository.observeSessions().first().lastOrNull()
                ?: return@launch
            val host = hostRepository.getHost(lastSession.hostId) ?: return@launch
            if (lastSession.state == SessionConnectionState.CONNECTED) {
                currentSessionMetadataId = lastSession.id
                updateState(
                    host = host,
                    state = SessionConnectionState.RECONNECT_REQUIRED,
                    statusMessage = "Previous live session for ${host.endpoint} needs reconnect after app recovery.",
                    lastError = lastSession.lastError,
                    reconnectRequired = true,
                    disconnectReason = "App was recreated; reconnect required.",
                )
                persistSessionState(
                    host = host,
                    state = SessionConnectionState.RECONNECT_REQUIRED,
                    reconnectRequired = true,
                    lastError = lastSession.lastError ?: "App was recreated; reconnect required.",
                    connectedAt = lastSession.connectedAt,
                    disconnectedAt = Instant.now(),
                )
            } else {
                currentSessionMetadataId = lastSession.id
                updateState(
                    host = host,
                    state = lastSession.state,
                    statusMessage = lastSession.lastError ?: uiState.value.statusMessage,
                    lastError = lastSession.lastError,
                    reconnectRequired = lastSession.reconnectRequired,
                    disconnectReason = lastSession.lastError,
                )
            }
        }
    }

    private suspend fun persistSessionState(
        host: Host,
        state: SessionConnectionState,
        reconnectRequired: Boolean,
        lastError: String?,
        connectedAt: Instant? = null,
        disconnectedAt: Instant? = null,
    ) {
        val repository = sessionMetadataRepository ?: return
        currentSessionMetadataId?.let { existingId ->
            val existing = repository.getSession(existingId)
            if (existing == null) {
                currentSessionMetadataId = null
            }
        }
        val persisted = repository.upsert(
            SessionMetadata(
                id = currentSessionMetadataId ?: 0,
                hostId = host.id,
                state = state,
                title = host.label,
                connectedAt = connectedAt,
                disconnectedAt = disconnectedAt,
                reconnectRequired = reconnectRequired,
                lastError = lastError,
            ),
        )
        currentSessionMetadataId = persisted.id
    }

    private suspend fun RuntimeConnection.sendResize(
        columns: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        runCatching {
            inputWriteMutex.withLock {
                channel.sendWindowChange(
                    columns,
                    rows,
                    widthPx,
                    heightPx,
                )
            }
        }.onFailure { throwable ->
            failConnection(
                host = host,
                message = "Session ended: ${throwable.message ?: "PTY resize failed."}",
                reconnectRequired = true,
            )
        }
    }

    private data class RuntimeConnection(
        val host: Host,
        val client: SshClient,
        val session: ClientSession,
        val channel: ChannelShell,
        val stdin: OutputStream,
        val stdout: InputStream,
        val closing: AtomicBoolean = AtomicBoolean(false),
    )

    private class ConnectAttempt(
        val id: Long,
    ) {
        private val canceled = AtomicBoolean(false)
        private val cancelActions = CopyOnWriteArrayList<() -> Unit>()

        val isCanceled: Boolean
            get() = canceled.get()

        fun register(cancellable: Cancellable) {
            registerCancelAction { runCatching { cancellable.cancel() } }
        }

        fun registerCancelAction(action: () -> Unit) {
            if (canceled.get()) {
                action()
                return
            }
            cancelActions += action
            if (canceled.get() && cancelActions.remove(action)) {
                action()
            }
        }

        fun ensureActive() {
            if (canceled.get()) {
                throw ConnectCanceledException()
            }
        }

        fun cancel() {
            if (!canceled.compareAndSet(false, true)) {
                return
            }
            cancelActions.forEach { action -> action() }
            cancelActions.clear()
        }
    }

    private class ConnectCanceledException : IllegalStateException("Connection canceled.")

    companion object {
        private const val TAG = "SshSessionCoordinator"
        private const val BOUNCY_CASTLE_PROVIDER = "BC"
        private val IMPORT_RESOURCE = NamedResource { "saved-private-key" }
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val AUTH_TIMEOUT_MILLIS = 20_000L
        private const val IMPORTED_KEY_AUTH_TIMEOUT_MILLIS = 35_000L
        private const val CHANNEL_OPEN_TIMEOUT_MILLIS = 20_000L
        private const val TRUST_DECISION_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_TERMINAL_COLUMNS = 80
        private const val DEFAULT_TERMINAL_ROWS = 24
        private const val CELL_WIDTH_PIXELS = 9
        private const val CELL_HEIGHT_PIXELS = 18
        private val nextAttemptId = AtomicLong(1L)
        private val userHomeConfigured = AtomicBoolean(false)

        private fun ensureBouncyCastleRegistered() {
            val currentProvider = Security.getProvider(BOUNCY_CASTLE_PROVIDER)
            if (currentProvider !is BouncyCastleProvider) {
                Security.removeProvider(BOUNCY_CASTLE_PROVIDER)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }

        private fun looksLikePemPrivateKey(privateKey: String): Boolean =
            PrivateKeyMaterialFormat.looksLikeRuntimePemPrivateKey(privateKey)
    }

    private fun authenticationTimeoutMillis(identity: Identity): Long = when (identity.kind) {
        IdentityKind.IMPORTED_KEY -> IMPORTED_KEY_AUTH_TIMEOUT_MILLIS
        else -> AUTH_TIMEOUT_MILLIS
    }
}
