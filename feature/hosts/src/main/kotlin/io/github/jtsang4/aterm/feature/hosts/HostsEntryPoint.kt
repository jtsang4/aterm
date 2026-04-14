package io.github.jtsang4.aterm.feature.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.distinguishingDetail
import io.github.jtsang4.aterm.core.domain.model.kindLabel
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.feature.identities.GeneratedKeyIdentityService
import io.github.jtsang4.aterm.feature.identities.IdentitiesScreen
import io.github.jtsang4.aterm.feature.identities.ImportedKeyImportService
import io.github.jtsang4.aterm.feature.identities.IdentityLaunchDestination
import java.time.Instant
import kotlinx.coroutines.launch

object HostsEntryPoint {
    const val route = "hosts"
}

private sealed interface HostsDestination {
    data object Library : HostsDestination
    data class Editor(val draft: HostEditorDraft) : HostsDestination
    data class DeleteConfirmation(
        val host: Host,
        val returnDraft: HostEditorDraft,
    ) : HostsDestination
    data class IdentityRecovery(
        val draft: HostEditorDraft,
        val launchDestination: IdentityLaunchDestination,
    ) : HostsDestination
}

@Composable
fun HostsScreen(
    hostRepository: HostRepository,
    identityRepository: IdentityRepository,
    onOpenRecentHost: (Long) -> Unit = {},
    importedKeyImportService: ImportedKeyImportService = ImportedKeyImportService(),
    generatedKeyIdentityService: GeneratedKeyIdentityService = GeneratedKeyIdentityService(),
) {
    val hosts by hostRepository.observeHosts().collectAsState(initial = emptyList())
    val identities by identityRepository.observeIdentities().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()
    var destination by remember { mutableStateOf<HostsDestination>(HostsDestination.Library) }

    when (val currentDestination = destination) {
        HostsDestination.Library -> HostsLibraryScreen(
            hosts = hosts,
            identities = identities,
            onCreateHost = {
                destination = HostsDestination.Editor(
                    HostEditorDraft.from(host = null, identities = identities),
                )
            },
            onEditHost = { host ->
                destination = HostsDestination.Editor(
                    HostEditorDraft.from(host = host, identities = identities),
                )
            },
            onRepairHost = { host ->
                destination = HostsDestination.Editor(
                    HostEditorDraft.from(host = host, identities = identities),
                )
            },
            onToggleFavorite = { host, isFavorite ->
                coroutineScope.launch {
                    hostRepository.setFavorite(host.id, isFavorite)
                }
            },
            onOpenRecentHost = onOpenRecentHost,
        )

        is HostsDestination.Editor -> HostEditorScreen(
            initialDraft = currentDestination.draft,
            existingHost = hosts.firstOrNull { it.id == currentDestination.draft.hostId },
            identities = identities,
            hostRepository = hostRepository,
            onCancel = { destination = HostsDestination.Library },
            onSaved = { destination = HostsDestination.Library },
            onDeleteRequested = { host, draft ->
                destination = HostsDestination.DeleteConfirmation(host, draft)
            },
            onLaunchIdentityRecovery = { draft, launchDestination ->
                destination = HostsDestination.IdentityRecovery(draft, launchDestination)
            },
        )

        is HostsDestination.DeleteConfirmation -> DeleteHostScreen(
            host = currentDestination.host,
            hostRepository = hostRepository,
            onCancel = {
                destination = HostsDestination.Editor(currentDestination.returnDraft)
            },
            onDeleted = { destination = HostsDestination.Library },
        )

        is HostsDestination.IdentityRecovery -> IdentitiesScreen(
            identityRepository = identityRepository,
            importedKeyImportService = importedKeyImportService,
            generatedKeyIdentityService = generatedKeyIdentityService,
            initialDestination = currentDestination.launchDestination,
            onCloseRequest = {
                destination = HostsDestination.Editor(currentDestination.draft)
            },
            onIdentitySaved = { savedIdentity ->
                destination = HostsDestination.Editor(
                    currentDestination.draft.copy(
                        authMode = if (savedIdentity.isCompatibleWith(HostAuthMode.PASSWORD)) {
                            HostAuthMode.PASSWORD
                        } else {
                            HostAuthMode.KEY
                        },
                        selectedIdentityId = savedIdentity.id,
                    ),
                )
            },
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
    onToggleFavorite: (Host, Boolean) -> Unit,
    onOpenRecentHost: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredHosts = remember(hosts, query) { hosts.filteredBy(query) }
    val favoriteHosts = remember(hosts) { hosts.filter(Host::isFavorite).sortedBy(Host::label) }
    val recentHosts = remember(hosts) {
        hosts.filter { it.lastUsedAt != null }
            .sortedWith(compareByDescending<Host> { it.lastUsedAt }.thenBy(Host::label))
    }

    AppScreenScaffold(
        title = "Hosts",
        supportingText = "Saved hosts keep endpoint metadata and usernames separate from reusable password and key identities.",
        modifier = Modifier.testTag("screen_hosts"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
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

            if (hosts.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search hosts") },
                    supportingText = { Text("Search by label, address, or username.") },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            TextButton(
                                onClick = { query = "" },
                                modifier = Modifier.testTag("host_search_clear"),
                            ) {
                                Text("Clear")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("host_search_field"),
                )
            }


            if (favoriteHosts.isNotEmpty()) {
                DiscoverySurfaceSection(
                    title = "Favorites",
                    supportingText = "Quickly reopen the same saved hosts you starred.",
                    modifier = Modifier.testTag("favorite_hosts_section"),
                ) {
                    favoriteHosts.forEachIndexed { index, host ->
                        DiscoveryChip(
                            host = host,
                            index = index,
                            containerTagPrefix = "favorite_host_item",
                            markerTagPrefix = "favorite_host_marker",
                            onClick = { onEditHost(host) },
                        )
                    }
                }
            }

            if (recentHosts.isNotEmpty()) {
                DiscoverySurfaceSection(
                    title = "Recents",
                    supportingText = "Newest successful connections stay available for quick reconnect.",
                    modifier = Modifier.testTag("recent_hosts_section"),
                ) {
                    recentHosts.forEachIndexed { index, host ->
                        DiscoveryChip(
                            host = host,
                            index = index,
                            containerTagPrefix = "recent_host_item",
                            markerTagPrefix = "recent_host_marker",
                            rowTagPrefix = "recent_host_row",
                            onClick = { onOpenRecentHost(host.id) },
                        )
                    }
                }
            }
            if (hosts.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("host_empty_state"),
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
                                "Create your first host and link one of your reusable saved identities."
                            } else {
                                "Create your first host here. If you still need credentials, the host form can open password, import-key, and generate-key identity flows."
                            },
                        )
                    }
                }
            } else if (filteredHosts.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("host_search_empty_state"),
                ) {
                    Text(
                        text = "No hosts match \"$query\". Clear search to see the full library again.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("host_list"),
                ) {
                    filteredHosts.forEach { host ->
                        val linkedIdentity = identities.firstOrNull { it.id == host.identityId }
                        HostRow(
                            host = host,
                            linkedIdentity = linkedIdentity,
                            onEditHost = onEditHost,
                            onRepairHost = onRepairHost,
                            onToggleFavorite = onToggleFavorite,
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
    onToggleFavorite: (Host, Boolean) -> Unit,
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
                modifier = Modifier.testTag("host_label_${host.id}"),
            )
            Text(
                text = "Selection detail: ${host.username}@${host.address}:${host.port}",
                modifier = Modifier.testTag("host_selection_detail_${host.id}"),
            )
            Text(text = "Address: ${host.address}:${host.port}")
            Text(text = "Username: ${host.username}")
            Text(
                text = linkedIdentity?.takeIf { it.isAuthenticationReady }?.let {
                    "${it.kindLabel()}: ${it.name}"
                } ?: "Identity needs repair",
                modifier = Modifier.testTag("host_identity_label_${host.id}"),
            )
            linkedIdentity?.takeIf { it.isAuthenticationReady }?.let {
                Text(
                    text = "Identity detail: ${it.distinguishingDetail()}",
                    modifier = Modifier.testTag("host_identity_detail_${host.id}"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onToggleFavorite(host, !host.isFavorite) },
                    modifier = Modifier.testTag("host_favorite_toggle_${host.id}"),
                ) {
                    Text(if (host.isFavorite) "Unfavorite" else "Favorite")
                }
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
    initialDraft: HostEditorDraft,
    existingHost: Host?,
    identities: List<Identity>,
    hostRepository: HostRepository,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    onDeleteRequested: (Host, HostEditorDraft) -> Unit,
    onLaunchIdentityRecovery: (HostEditorDraft, IdentityLaunchDestination) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var label by remember(initialDraft) { mutableStateOf(initialDraft.label) }
    var address by remember(initialDraft) { mutableStateOf(initialDraft.address) }
    var portText by remember(initialDraft) { mutableStateOf(initialDraft.portText) }
    var username by remember(initialDraft) { mutableStateOf(initialDraft.username) }
    var authMode by remember(initialDraft) { mutableStateOf(initialDraft.authMode) }
    var selectedIdentityId by remember(initialDraft) { mutableStateOf(initialDraft.selectedIdentityId) }
    var authModeError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var labelError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var addressError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var portError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var usernameError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var identityError by remember(initialDraft) { mutableStateOf<String?>(null) }
    var saveError by remember(initialDraft) { mutableStateOf<String?>(null) }

    val authenticationReadyIdentities = identities.filter(Identity::isAuthenticationReady)
    val compatibleIdentities = authMode?.let { authenticationReadyIdentities.compatibleWith(it) }.orEmpty()
    val hasSelectedCompatibleIdentity = compatibleIdentities.any { it.id == selectedIdentityId }

    AppScreenScaffold(
        title = if (initialDraft.isEditing) "Edit host" else "Create host",
        supportingText = "Hosts save the endpoint and username while reusable identities keep the secret or key material.",
        modifier = Modifier.testTag("host_editor"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
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
                supportingText = { Text(labelError ?: "Shown throughout the host library.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_label_field"),
            )
            labelError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("host_label_error"),
                )
            }
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
            addressError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("host_address_error"),
                )
            }
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
            portError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("host_port_error"),
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    usernameError = null
                    saveError = null
                },
                label = { Text("Username") },
                isError = usernameError != null,
                supportingText = { Text(usernameError ?: "Usernames belong to hosts, not shared identities.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_username_field"),
            )
            usernameError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("host_username_error"),
                )
            }
            HostAuthModeSection(
                authMode = authMode,
                error = authModeError,
                onAuthModeSelected = { newMode ->
                    authMode = newMode
                    selectedIdentityId = selectedIdentityId?.takeIf { existingId ->
                        authenticationReadyIdentities
                            .filter { it.isCompatibleWith(newMode) }
                            .any { it.id == existingId }
                    }
                    authModeError = null
                    identityError = null
                    saveError = null
                },
            )
            HostIdentitySelectionSection(
                authMode = authMode,
                compatibleIdentities = compatibleIdentities,
                selectedIdentityId = selectedIdentityId.takeIf { hasSelectedCompatibleIdentity },
                error = identityError,
                onSelected = {
                    selectedIdentityId = it
                    identityError = null
                    saveError = null
                },
                onLaunchRecovery = { launchDestination ->
                    onLaunchIdentityRecovery(
                        HostEditorDraft(
                            hostId = initialDraft.hostId,
                            label = label,
                            address = address,
                            portText = portText,
                            username = username,
                            authMode = authMode,
                            selectedIdentityId = selectedIdentityId.takeIf { hasSelectedCompatibleIdentity },
                        ),
                        launchDestination,
                    )
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
                if (existingHost != null) {
                    TextButton(
                        onClick = {
                            onDeleteRequested(
                                existingHost,
                                HostEditorDraft(
                                    hostId = initialDraft.hostId,
                                    label = label,
                                    address = address,
                                    portText = portText,
                                    username = username,
                                    authMode = authMode,
                                    selectedIdentityId = selectedIdentityId,
                                ),
                            )
                        },
                        modifier = Modifier.testTag("host_editor_delete"),
                    ) {
                        Text("Delete")
                    }
                }
                Button(
                    onClick = {
                        val trimmedLabel = label.trim()
                        val trimmedAddress = address.trim()
                        val trimmedUsername = username.trim()
                        val parsedPort = portText.toIntOrNull()
                        val currentAuthMode = authMode
                        val persistedIdentityId = selectedIdentityId.takeIf {
                            compatibleIdentities.any { identity -> identity.id == it }
                        }

                        labelError = if (trimmedLabel.isBlank()) "Host label is required." else null
                        addressError = if (trimmedAddress.isBlank()) "Address is required." else null
                        portError = if (parsedPort == null || parsedPort !in 1..65_535) {
                            "Enter a valid port."
                        } else {
                            null
                        }
                        usernameError = if (trimmedUsername.isBlank()) "Username is required." else null
                        authModeError = if (currentAuthMode == null) {
                            "Choose whether this host repairs with a password identity or SSH key before saving."
                        } else {
                            null
                        }
                        identityError = if (currentAuthMode != null && persistedIdentityId == null) {
                            "Select a reusable ${currentAuthMode.identityRequirementLabel()} before saving."
                        } else {
                            null
                        }
                        if (
                            authModeError != null ||
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
                                        id = initialDraft.hostId,
                                        label = trimmedLabel,
                                        address = trimmedAddress,
                                        port = requireNotNull(parsedPort),
                                        username = trimmedUsername,
                                        identityId = persistedIdentityId,
                                        authKind = requireNotNull(currentAuthMode).toDomain(),
                                        isFavorite = existingHost?.isFavorite ?: false,
                                        lastUsedAt = existingHost?.lastUsedAt,
                                        createdAt = existingHost?.createdAt ?: Instant.now(),
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
                    Text(if (initialDraft.isEditing) "Save changes" else "Save host")
                }
            }
        }
    }
}

@Composable
private fun HostAuthModeSection(
    authMode: HostAuthMode?,
    error: String?,
    onAuthModeSelected: (HostAuthMode) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("host_auth_mode_section"),
    ) {
        Text(
            text = "Authentication mode",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            HostAuthMode.entries.forEach { mode ->
                HostAuthModeChip(
                    authMode = mode,
                    selected = authMode == mode,
                    onSelected = { onAuthModeSelected(mode) },
                )
            }
        }
        if (authMode == null) {
            Text(
                text = "This repaired host no longer has enough saved context to know whether it used a password identity or SSH key. Choose the credential family before relinking it.",
                modifier = Modifier.testTag("host_auth_mode_required"),
            )
        }
        error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("host_auth_mode_error"),
            )
        }
    }
}

@Composable
private fun HostIdentitySelectionSection(
    authMode: HostAuthMode?,
    compatibleIdentities: List<Identity>,
    selectedIdentityId: Long?,
    error: String?,
    onSelected: (Long) -> Unit,
    onLaunchRecovery: (IdentityLaunchDestination) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("host_identity_selection_section"),
    ) {
        Text(
            text = "Authentication identity",
            style = MaterialTheme.typography.titleMedium,
        )
        if (authMode == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_identity_waiting_for_auth_mode"),
            ) {
                Text(
                    text = "Choose whether this host should use a password identity or SSH key first, then pick or recover a compatible replacement identity.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else if (compatibleIdentities.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(
                        if (authMode == HostAuthMode.PASSWORD) {
                            "host_no_password_identities"
                        } else {
                            "host_no_key_identities"
                        },
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = when (authMode) {
                            HostAuthMode.PASSWORD ->
                                "No reusable password identities are available. Recover from this flow by creating one and returning here."
                            HostAuthMode.KEY ->
                                "No compatible key identities are available. Recover from this flow by importing or generating a key, then return here to relink it."
                        },
                    )
                    IdentityRecoveryActions(
                        authMode = authMode,
                        onLaunchRecovery = onLaunchRecovery,
                    )
                }
            }
        } else {
            Text(
                text = "Choose the reusable ${authMode.identityRequirementLabel()} for this host. Duplicate labels stay safe because each option also shows a distinguishing detail.",
            )
            compatibleIdentities.forEach { identity ->
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
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = identity.name)
                            Text(text = "Reusable ${identity.kindLabel().lowercase()}")
                            Text(
                                text = identity.distinguishingDetail(),
                                modifier = Modifier.testTag("host_identity_option_detail_${identity.id}"),
                            )
                        }
                    }
                }
            }
            IdentityRecoveryActions(
                authMode = authMode,
                onLaunchRecovery = onLaunchRecovery,
            )
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

@Composable
private fun IdentityRecoveryActions(
    authMode: HostAuthMode,
    onLaunchRecovery: (IdentityLaunchDestination) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("host_identity_recovery_actions"),
    ) {
        when (authMode) {
            HostAuthMode.PASSWORD -> {
                TextButton(
                    onClick = { onLaunchRecovery(IdentityLaunchDestination.CreatePassword) },
                    modifier = Modifier.testTag("host_recover_create_password_identity"),
                ) {
                    Text("Create password identity")
                }
            }

            HostAuthMode.KEY -> {
                TextButton(
                    onClick = { onLaunchRecovery(IdentityLaunchDestination.ImportKey) },
                    modifier = Modifier.testTag("host_recover_import_key_identity"),
                ) {
                    Text("Import key")
                }
                TextButton(
                    onClick = { onLaunchRecovery(IdentityLaunchDestination.GenerateKey) },
                    modifier = Modifier.testTag("host_recover_generate_key_identity"),
                ) {
                    Text("Generate key")
                }
            }
        }
    }
}

@Composable
private fun DeleteHostScreen(
    host: Host,
    hostRepository: HostRepository,
    onCancel: () -> Unit,
    onDeleted: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    AppScreenScaffold(
        title = "Delete host",
        supportingText = "Deleting a host removes only that saved host record. Linked reusable identities remain intact.",
        modifier = Modifier.testTag("host_delete_confirmation"),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Delete ${host.label}?",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Selection detail: ${host.username}@${host.address}:${host.port}",
                modifier = Modifier.testTag("host_delete_detail"),
            )
            Text(
                text = "Cancel keeps this host unchanged. Confirm removes only this host entry and leaves any linked identity reusable elsewhere.",
                modifier = Modifier.testTag("host_delete_warning"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("host_delete_cancel"),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        coroutineScope.launch {
                            hostRepository.deleteHost(host.id)
                            onDeleted()
                        }
                    },
                    modifier = Modifier.testTag("host_delete_confirm"),
                ) {
                    Text("Delete host")
                }
            }
        }
    }
}

private fun List<Host>.filteredBy(query: String): List<Host> {
    val normalized = query.trim()
    if (normalized.isBlank()) {
        return this
    }
    return filter { host ->
        host.label.contains(normalized, ignoreCase = true) ||
            host.address.contains(normalized, ignoreCase = true) ||
            host.username.contains(normalized, ignoreCase = true)
    }
}

private fun HostAuthMode.toDomain(): HostAuthKind = when (this) {
    HostAuthMode.PASSWORD -> HostAuthKind.PASSWORD
    HostAuthMode.KEY -> HostAuthKind.KEY
}

@Composable
private fun DiscoverySurfaceSection(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = supportingText)
                content()
            },
        )
    }
}

@Composable
private fun DiscoveryChip(
    host: Host,
    index: Int,
    containerTagPrefix: String,
    markerTagPrefix: String,
    rowTagPrefix: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (rowTagPrefix != null) {
                    Modifier.testTag("${rowTagPrefix}_${host.id}")
                } else {
                    Modifier
                },
            ),
    ) {
        FilterChip(
            selected = false,
            onClick = onClick,
            label = {
                Text(
                    text = "${host.label} · ${host.username}@${host.address}:${host.port}",
                    modifier = Modifier.testTag("${markerTagPrefix}_${host.id}"),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${containerTagPrefix}_${index}_${host.id}"),
        )
    }
}

@Composable
private fun HostAuthModeChip(
    authMode: HostAuthMode,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    Card(
        modifier = Modifier
            .clickable { onSelected() }
            .testTag("host_auth_mode_${authMode.name.lowercase()}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelected,
            )
            Text(text = authMode.label())
        }
    }
}
