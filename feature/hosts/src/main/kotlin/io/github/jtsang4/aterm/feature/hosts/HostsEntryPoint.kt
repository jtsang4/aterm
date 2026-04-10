package io.github.jtsang4.aterm.feature.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.distinguishingDetail
import io.github.jtsang4.aterm.core.domain.model.kindLabel
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import java.time.Instant
import kotlinx.coroutines.launch

object HostsEntryPoint {
    const val route = "hosts"
}

private sealed interface HostsDestination {
    data object Library : HostsDestination
    data class Editor(val host: Host?) : HostsDestination
}

@Composable
fun HostsScreen(
    hostRepository: HostRepository,
    identityRepository: IdentityRepository,
) {
    val hosts by hostRepository.observeHosts().collectAsState(initial = emptyList())
    val identities by identityRepository.observeIdentities().collectAsState(initial = emptyList())
    var destination by remember { mutableStateOf<HostsDestination>(HostsDestination.Library) }

    when (val currentDestination = destination) {
        HostsDestination.Library -> HostsLibraryScreen(
            hosts = hosts,
            identities = identities,
            onCreateHost = { destination = HostsDestination.Editor(host = null) },
            onEditHost = { destination = HostsDestination.Editor(host = it) },
            onRepairHost = { destination = HostsDestination.Editor(host = it) },
        )

        is HostsDestination.Editor -> HostEditorScreen(
            host = currentDestination.host,
            availableIdentities = identities.filter { it.isAuthenticationReady },
            onCancel = { destination = HostsDestination.Library },
            onSaved = { destination = HostsDestination.Library },
            hostRepository = hostRepository,
        )
    }
}

@Composable
private fun HostsLibraryScreen(
    hosts: List<Host>,
    identities: List<Identity>,
    onCreateHost: () -> Unit,
    onEditHost: (Host) -> Unit,
    onRepairHost: (Host) -> Unit,
) {
    AppScreenScaffold(
        title = "Hosts",
        supportingText = "Saved hosts keep endpoint metadata separate from reusable password and key identities.",
        modifier = Modifier.testTag("screen_hosts"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = onCreateHost,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_create_action"),
            ) {
                Text("Create host")
            }

            if (hosts.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("host_identity_plumbing_empty_state"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "No hosts saved yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (identities.any { it.isAuthenticationReady }) {
                                "Create a host and link one of your reusable saved identities."
                            } else {
                                "Create an identity first, then come back here to link it from the host authentication section."
                            },
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.testTag("host_list"),
                ) {
                    items(hosts, key = Host::id) { host ->
                        val linkedIdentity = identities.firstOrNull { it.id == host.identityId }
                        HostRow(
                            host = host,
                            linkedIdentity = linkedIdentity,
                            onEditHost = onEditHost,
                            onRepairHost = onRepairHost,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostRow(
    host: Host,
    linkedIdentity: Identity?,
    onEditHost: (Host) -> Unit,
    onRepairHost: (Host) -> Unit,
) {
    val needsRepair = linkedIdentity == null || !linkedIdentity.isAuthenticationReady
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("host_row_${host.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = host.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = "${host.address}:${host.port}")
            Text(text = "Username: ${host.username}")
            Text(
                text = linkedIdentity?.takeIf { it.isAuthenticationReady }?.let {
                    "${it.kindLabel()}: ${it.name}"
                } ?: "Identity needs repair",
                modifier = Modifier.testTag("host_identity_label_${host.id}"),
            )
            linkedIdentity?.takeIf { it.isAuthenticationReady }?.let {
                Text(
                    text = "Detail: ${it.distinguishingDetail()}",
                    modifier = Modifier.testTag("host_identity_detail_${host.id}"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onEditHost(host) },
                    modifier = Modifier.testTag("host_edit_${host.id}"),
                ) {
                    Text("Edit")
                }
                if (needsRepair) {
                    TextButton(
                        onClick = { onRepairHost(host) },
                        modifier = Modifier.testTag("host_repair_${host.id}"),
                    ) {
                        Text("Repair link")
                    }
                }
            }
        }
    }
}

@Composable
private fun HostEditorScreen(
    host: Host?,
    availableIdentities: List<Identity>,
    hostRepository: HostRepository,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    val isEditing = host != null
    val coroutineScope = rememberCoroutineScope()
    var label by remember(host?.id) { mutableStateOf(host?.label.orEmpty()) }
    var address by remember(host?.id) { mutableStateOf(host?.address.orEmpty()) }
    var portText by remember(host?.id) { mutableStateOf(host?.port?.toString().orEmpty().ifBlank { "22" }) }
    var username by remember(host?.id) { mutableStateOf(host?.username.orEmpty()) }
    var selectedIdentityId by remember(host?.id, availableIdentities) {
        mutableStateOf(
            host?.identityId?.takeIf { existingId -> availableIdentities.any { it.id == existingId } }
                ?: availableIdentities.firstOrNull()?.id,
        )
    }
    var labelError by remember(host?.id) { mutableStateOf<String?>(null) }
    var addressError by remember(host?.id) { mutableStateOf<String?>(null) }
    var portError by remember(host?.id) { mutableStateOf<String?>(null) }
    var usernameError by remember(host?.id) { mutableStateOf<String?>(null) }
    var identityError by remember(host?.id) { mutableStateOf<String?>(null) }
    var saveError by remember(host?.id) { mutableStateOf<String?>(null) }

    AppScreenScaffold(
        title = if (isEditing) "Edit host" else "Create host",
        supportingText = "Choose one reusable identity for this host. The host record keeps only the link, not duplicated credential material.",
        modifier = Modifier.testTag("host_editor"),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = {
                    label = it
                    labelError = null
                    saveError = null
                },
                label = { Text("Host label") },
                isError = labelError != null,
                supportingText = { Text(labelError ?: "Shown throughout the local host library.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_label_field"),
            )
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    addressError = null
                    saveError = null
                },
                label = { Text("Address") },
                isError = addressError != null,
                supportingText = { Text(addressError ?: "Use a hostname or network address.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_address_field"),
            )
            OutlinedTextField(
                value = portText,
                onValueChange = {
                    portText = it
                    portError = null
                    saveError = null
                },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = portError != null,
                supportingText = { Text(portError ?: "TCP port used for SSH.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_port_field"),
            )
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    usernameError = null
                    saveError = null
                },
                label = { Text("Username") },
                isError = usernameError != null,
                supportingText = { Text(usernameError ?: "Usernames stay on the host record.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_username_field"),
            )
            PasswordIdentitySelectionSection(
                availableIdentities = availableIdentities,
                selectedIdentityId = selectedIdentityId,
                error = identityError,
                onSelected = {
                    selectedIdentityId = it
                    identityError = null
                    saveError = null
                },
            )
            saveError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("host_save_error"),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("host_editor_cancel"),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val trimmedLabel = label.trim()
                        val trimmedAddress = address.trim()
                        val trimmedUsername = username.trim()
                        val parsedPort = portText.toIntOrNull()

                        labelError = if (trimmedLabel.isBlank()) "Host label is required." else null
                        addressError = if (trimmedAddress.isBlank()) "Address is required." else null
                        portError = if (parsedPort == null || parsedPort !in 1..65_535) {
                            "Enter a valid port."
                        } else {
                            null
                        }
                        usernameError = if (trimmedUsername.isBlank()) "Username is required." else null
                        identityError = if (selectedIdentityId == null) {
                            "Select a reusable password identity."
                        } else {
                            null
                        }
                        if (
                            labelError != null ||
                            addressError != null ||
                            portError != null ||
                            usernameError != null ||
                            identityError != null
                        ) {
                            return@Button
                        }

                        coroutineScope.launch {
                            runCatching {
                                hostRepository.upsert(
                                    Host(
                                        id = host?.id ?: 0,
                                        label = trimmedLabel,
                                        address = trimmedAddress,
                                        port = requireNotNull(parsedPort),
                                        username = trimmedUsername,
                                        identityId = selectedIdentityId,
                                        isFavorite = host?.isFavorite ?: false,
                                        lastUsedAt = host?.lastUsedAt,
                                        createdAt = host?.createdAt ?: Instant.now(),
                                        updatedAt = Instant.now(),
                                    ),
                                )
                            }.onSuccess {
                                onSaved()
                            }.onFailure { throwable ->
                                saveError = throwable.message ?: "Unable to save the host."
                            }
                        }
                    },
                    modifier = Modifier.testTag("host_editor_save"),
                ) {
                    Text(if (isEditing) "Save changes" else "Save host")
                }
            }
        }
    }
}

@Composable
private fun PasswordIdentitySelectionSection(
    availableIdentities: List<Identity>,
    selectedIdentityId: Long?,
    error: String?,
    onSelected: (Long) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("host_password_identity_section"),
    ) {
        Text(
            text = "Authentication identity",
            style = MaterialTheme.typography.titleMedium,
        )
        if (availableIdentities.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_no_password_identities"),
            ) {
                Text(
                    text = "No reusable identities are available yet. Create one from the Identities tab, then return here to select it.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            availableIdentities.forEach { identity ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(identity.id) }
                        .testTag("host_identity_option_${identity.id}"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = identity.id == selectedIdentityId,
                            onClick = { onSelected(identity.id) },
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(text = identity.name)
                            Text(
                                text = "Reusable ${identity.kindLabel().lowercase()}",
                            )
                            Text(
                                text = identity.distinguishingDetail(),
                                modifier = Modifier.testTag("host_identity_option_detail_${identity.id}"),
                            )
                        }
                    }
                }
            }
        }
        error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("host_identity_error"),
            )
        }
    }
}
