package io.github.jtsang4.aterm.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import io.github.jtsang4.aterm.core.ssh.PendingTrustDecision
import io.github.jtsang4.aterm.core.ssh.SessionController
import io.github.jtsang4.aterm.core.ssh.SessionUiState
import io.github.jtsang4.aterm.core.ssh.SshSessionCoordinator

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

    AppScreenScaffold(
        title = "Sessions",
        supportingText = "Real SSH connects require explicit trust for unknown hosts, preserve saved identity credentials, and show truthful connection status.",
        modifier = Modifier.testTag("screen_session"),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
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
                onSendInput = coordinator::sendInput,
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
            sessionState.endpoint?.let { endpoint ->
                Text(
                    text = "Target: $endpoint",
                    modifier = Modifier.testTag("session_active_endpoint"),
                )
            }
            if (sessionState.isConnected) {
                TextButton(
                    onClick = onDisconnect,
                    modifier = Modifier.testTag("session_disconnect_button"),
                ) {
                    Text("Disconnect")
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
                                    } else {
                                        "Identity needs repair before connecting."
                                    },
                                    modifier = Modifier.testTag("session_host_identity_${host.id}"),
                                )
                                Button(
                                    onClick = { onConnect(host.id) },
                                    enabled = isReady && !sessionState.isConnecting,
                                    modifier = Modifier.testTag("session_connect_${host.id}"),
                                ) {
                                    Text(
                                        if (sessionState.activeHostId == host.id && sessionState.isConnected) {
                                            "Reconnect"
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

@Composable
private fun SessionTerminal(
    sessionState: SessionUiState,
    onSendInput: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

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
                text = "Terminal transcript",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = sessionState.transcript.ifBlank { "No terminal transcript yet." },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("session_transcript"),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Send input") },
                    enabled = sessionState.canSendInput,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("session_input_field"),
                )
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSendInput("$input\n")
                            input = ""
                        }
                    },
                    enabled = sessionState.canSendInput,
                    modifier = Modifier.testTag("session_send_button"),
                ) {
                    Text("Send")
                }
            }
        }
    }
}
