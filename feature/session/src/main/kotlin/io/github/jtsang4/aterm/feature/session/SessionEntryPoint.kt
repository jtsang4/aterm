package io.github.jtsang4.aterm.feature.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.passphraseStatusLabel
import io.github.jtsang4.aterm.core.domain.model.secretStatusLabel
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import io.github.jtsang4.aterm.core.ssh.PendingTrustDecision
import io.github.jtsang4.aterm.core.ssh.SessionController
import io.github.jtsang4.aterm.core.ssh.SessionUiState
import io.github.jtsang4.aterm.core.ssh.SshSessionCoordinator
import io.github.jtsang4.aterm.core.terminal.ComposeTerminalSurface
import io.github.jtsang4.aterm.core.terminal.calculateTerminalViewport
import io.github.jtsang4.aterm.core.terminal.TerminalSpecialKeyBar

object SessionEntryPoint {
    const val route = "session"
}

@Composable
fun SessionsScreen(
    hostRepository: HostRepository,
    identityRepository: IdentityRepository,
    knownHostTrustRepository: KnownHostTrustRepository,
    coordinator: SessionController = remember(
        hostRepository,
        identityRepository,
        knownHostTrustRepository,
    ) {
        SshSessionCoordinator(
            hostRepository = hostRepository,
            identityRepository = identityRepository,
            knownHostTrustRepository = knownHostTrustRepository,
        )
    },
) {
    val hosts by hostRepository.observeHosts().collectAsState(initial = emptyList())
    val identities by identityRepository.observeIdentities().collectAsState(initial = emptyList())
    val sessionState by coordinator.observeUiState().collectAsState()
    val scrollState = rememberScrollState()

    AppScreenScaffold(
        title = "Sessions",
        supportingText = "Real SSH connects require explicit trust for unknown hosts, preserve saved identity credentials, and show truthful connection status.",
        modifier = Modifier.testTag("screen_session"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SessionStatusCard(
                sessionState = sessionState,
                onDisconnect = coordinator::disconnect,
            )
            sessionState.pendingTrustDecision?.let { decision ->
                HostTrustPrompt(
                    decision = decision,
                    onAccept = { coordinator.submitHostTrustDecision(true) },
                    onReject = { coordinator.submitHostTrustDecision(false) },
                )
            }
            SessionHostList(
                hosts = hosts,
                identities = identities,
                sessionState = sessionState,
                onConnect = coordinator::connect,
            )
            SessionTerminal(
                sessionState = sessionState,
                coordinator = coordinator,
            )
        }
    }
}

@Composable
private fun SessionStatusCard(
    sessionState: SessionUiState,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_status_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "State: ${sessionState.connectionState.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("session_state_label"),
            )
            Text(
                text = sessionState.statusMessage ?: "Choose a saved host to start a real SSH session.",
                modifier = Modifier.testTag("session_status_message"),
            )
            if (sessionState.reconnectRequired || sessionState.connectionState == io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.RECONNECT_REQUIRED) {
                Text(
                    text = sessionState.disconnectReason ?: "Session is no longer live. Reconnect to resume terminal interaction.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("session_reconnect_required"),
                )
            }
            sessionState.endpoint?.let { endpoint ->
                Text(
                    text = "Target: $endpoint",
                    modifier = Modifier.testTag("session_active_endpoint"),
                )
            }
            if (sessionState.isConnected || sessionState.isConnecting) {
                val label = if (sessionState.isConnecting) "Cancel connection" else "Disconnect"
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.testTag("session_disconnect_button"),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun HostTrustPrompt(
    decision: PendingTrustDecision,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_trust_prompt"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Trust host key for ${decision.hostLabel}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Endpoint: ${decision.endpoint}",
                modifier = Modifier.testTag("session_trust_endpoint"),
            )
            Text(
                text = "Algorithm: ${decision.algorithm}",
                modifier = Modifier.testTag("session_trust_algorithm"),
            )
            Text(
                text = "Fingerprint: ${decision.fingerprint}",
                modifier = Modifier.testTag("session_trust_fingerprint"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onReject,
                    modifier = Modifier.testTag("session_trust_reject"),
                ) {
                    Text("Reject")
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.testTag("session_trust_accept"),
                ) {
                    Text("Trust and connect")
                }
            }
        }
    }
}

@Composable
private fun SessionHostList(
    hosts: List<Host>,
    identities: List<Identity>,
    sessionState: SessionUiState,
    onConnect: (Long) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_host_library"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Saved hosts",
                style = MaterialTheme.typography.titleMedium,
            )
            if (hosts.isEmpty()) {
                Text("Create a host from the Hosts tab, then return here to connect.")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .testTag("session_host_list"),
                ) {
                    items(hosts, key = Host::id) { host ->
                        val identity = identities.firstOrNull { it.id == host.identityId }
                        val isReady = identity?.isAuthenticationReady == true
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("session_host_row_${host.id}"),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(host.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${host.username}@${host.address}:${host.port}",
                                    modifier = Modifier.testTag("session_host_endpoint_${host.id}"),
                                )
                                Text(
                                    text = if (isReady) {
                                        "Identity ready: ${identity?.name}"
                                    } else if (identity != null) {
                                        "Identity needs repair before connecting: ${identity.name} (${identity.sessionRepairStatusLabel()})"
                                    } else {
                                        "Linked identity is missing. Repair this host before connecting."
                                    },
                                    modifier = Modifier.testTag("session_host_identity_${host.id}"),
                                )
                                Button(
                                    onClick = { onConnect(host.id) },
                                    enabled = isReady && !sessionState.isConnecting,
                                    modifier = Modifier.testTag("session_connect_${host.id}"),
                                ) {
                                    Text(
                                        if (sessionState.activeHostId == host.id &&
                                            (sessionState.isConnected ||
                                                sessionState.connectionState == io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.FAILED ||
                                                sessionState.connectionState == io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.DISCONNECTED ||
                                                sessionState.connectionState == io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.RECONNECT_REQUIRED)
                                        ) {
                                            "Reconnect"
                                        } else if (sessionState.activeHostId == host.id && sessionState.isConnecting) {
                                            "Connecting…"
                                        } else {
                                            "Connect"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Identity.sessionRepairStatusLabel(): String = when (kind) {
    IdentityKind.PASSWORD -> secretStatusLabel()
    IdentityKind.IMPORTED_KEY,
    IdentityKind.GENERATED_KEY,
    -> if (hasPassphrase) passphraseStatusLabel() else secretStatusLabel()
}

@Composable
private fun SessionTerminal(
    sessionState: SessionUiState,
    coordinator: SessionController,
) {
    var input by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var clipboardStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionState.connectionState) {
        if (!sessionState.canSendInput) {
            clipboardStatus = null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_terminal_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (sessionState.isTerminalLive) "Terminal surface" else "Terminal history",
                style = MaterialTheme.typography.titleMedium,
            )
            ComposeTerminalSurface(
                controller = coordinator,
                onTerminalSurfaceSizeChanged = { widthPx, heightPx, cellWidthPx, cellHeightPx ->
                    val viewport = calculateTerminalViewport(
                        contentWidthPx = widthPx,
                        contentHeightPx = heightPx,
                        cellWidthPx = cellWidthPx,
                        cellHeightPx = cellHeightPx,
                    ) ?: return@ComposeTerminalSurface
                    coordinator.resize(viewport)
                },
                modifier = Modifier
                    .testTag("session_terminal_region"),
            )
            if (!sessionState.isTerminalLive) {
                Text(
                    text = sessionState.disconnectReason
                        ?: if (sessionState.connectionState == io.github.jtsang4.aterm.core.domain.model.SessionConnectionState.DISCONNECTED) {
                            "Terminal is disconnected. Transcript below is historical only."
                        } else {
                            "Terminal is not currently live. Reconnect to send input."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("session_terminal_truth_banner"),
                )
            }
            TerminalSpecialKeyBar(
                enabled = sessionState.isTerminalLive,
                onSpecialKey = coordinator::sendSpecialKey,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Send input") },
                    enabled = sessionState.isTerminalLive,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("session_input_field"),
                )
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            coordinator.sendText("$input\n")
                            input = ""
                        }
                    },
                    enabled = sessionState.isTerminalLive,
                    modifier = Modifier.testTag("session_send_button"),
                ) {
                    Text("Send")
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        val text = sessionState.liveTerminalState.snapshot.completeText
                            .ifBlank { sessionState.transcript }
                            .ifBlank { return@OutlinedButton }
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("aterm-terminal", text))
                        clipboardStatus = "Copied terminal output"
                    },
                    modifier = Modifier.testTag("session_copy_button"),
                ) {
                    Text("Copy")
                }
                OutlinedButton(
                    onClick = {
                        val text = clipboardManager.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?: return@OutlinedButton
                        coordinator.pasteText(text)
                        clipboardStatus = "Pasted from clipboard"
                    },
                    enabled = sessionState.isTerminalLive,
                    modifier = Modifier.testTag("session_paste_button"),
                ) {
                    Text("Paste")
                }
            }
            clipboardStatus?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("session_clipboard_status"),
                )
            }
            Text(
                text = sessionState.liveTerminalState.snapshot.completeText
                    .ifBlank { sessionState.transcript }
                    .ifBlank { "No terminal transcript yet." },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("session_transcript"),
            )
            Text(
                text = "Terminal cell: ${sessionState.liveTerminalState.cellWidthPx}×${sessionState.liveTerminalState.cellHeightPx}px",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("session_terminal_metrics"),
            )
        }
    }
}
