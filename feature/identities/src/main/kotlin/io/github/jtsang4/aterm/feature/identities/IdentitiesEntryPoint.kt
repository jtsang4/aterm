package io.github.jtsang4.aterm.feature.identities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import java.time.Instant
import kotlinx.coroutines.launch

object IdentitiesEntryPoint {
    const val route = "identities"
}

private sealed interface IdentityDestination {
    data object Library : IdentityDestination
    data class PasswordEditor(val identity: Identity?) : IdentityDestination
    data object ImportKeyStub : IdentityDestination
    data object GenerateKeyStub : IdentityDestination
}

@Composable
fun IdentitiesScreen(
    identityRepository: IdentityRepository,
) {
    val identities by identityRepository.observeIdentities().collectAsState(initial = emptyList())
    var destination by remember { mutableStateOf<IdentityDestination>(IdentityDestination.Library) }

    when (val currentDestination = destination) {
        IdentityDestination.GenerateKeyStub -> IdentityStubScreen(
            title = "Generate key identity",
            supportingText = "Generated-key identities will attach to this library flow in the next identity slice.",
            modifier = Modifier.testTag("identity_generate_stub"),
            onBack = { destination = IdentityDestination.Library },
        )

        IdentityDestination.ImportKeyStub -> IdentityStubScreen(
            title = "Import key identity",
            supportingText = "Private-key import will attach to this library flow in the next identity slice.",
            modifier = Modifier.testTag("identity_import_stub"),
            onBack = { destination = IdentityDestination.Library },
        )

        IdentityDestination.Library -> IdentityLibraryScreen(
            identities = identities,
            onCreatePassword = { destination = IdentityDestination.PasswordEditor(identity = null) },
            onImportKey = { destination = IdentityDestination.ImportKeyStub },
            onGenerateKey = { destination = IdentityDestination.GenerateKeyStub },
            onEditPasswordIdentity = { destination = IdentityDestination.PasswordEditor(identity = it) },
        )

        is IdentityDestination.PasswordEditor -> PasswordIdentityEditorScreen(
            identity = currentDestination.identity,
            identityRepository = identityRepository,
            onCancel = { destination = IdentityDestination.Library },
            onSaved = { destination = IdentityDestination.Library },
        )
    }
}

@Composable
private fun IdentityLibraryScreen(
    identities: List<Identity>,
    onCreatePassword: () -> Unit,
    onImportKey: () -> Unit,
    onGenerateKey: () -> Unit,
    onEditPasswordIdentity: (Identity) -> Unit,
) {
    AppScreenScaffold(
        title = "Identities",
        supportingText = "Reusable password identities are stored locally and can be linked from host authentication flows.",
        modifier = Modifier.testTag("screen_identities"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IdentityActionButtons(
                onCreatePassword = onCreatePassword,
                onImportKey = onImportKey,
                onGenerateKey = onGenerateKey,
            )

            if (identities.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_empty_state"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "No identities yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Create a password identity now, or open the upcoming key import and key generation entry points from this first-run state.",
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.testTag("identity_list"),
                ) {
                    items(identities, key = Identity::id) { identity ->
                        IdentityRow(
                            identity = identity,
                            onEditPasswordIdentity = onEditPasswordIdentity,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentityActionButtons(
    onCreatePassword: () -> Unit,
    onImportKey: () -> Unit,
    onGenerateKey: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("identity_actions"),
    ) {
        Button(
            onClick = onCreatePassword,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("identity_create_password_action"),
        ) {
            Text("Create password identity")
        }
        Button(
            onClick = onImportKey,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("identity_import_key_action"),
        ) {
            Text("Import key")
        }
        Button(
            onClick = onGenerateKey,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("identity_generate_key_action"),
        ) {
            Text("Generate key")
        }
    }
}

@Composable
private fun IdentityRow(
    identity: Identity,
    onEditPasswordIdentity: (Identity) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("identity_row_${identity.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = identity.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("identity_name_${identity.id}"),
            )
            Text(
                text = when (identity.kind) {
                    IdentityKind.PASSWORD -> "Password identity"
                    IdentityKind.IMPORTED_KEY -> "Imported key identity"
                    IdentityKind.GENERATED_KEY -> "Generated key identity"
                },
            )
            Text(
                text = if (identity.hasSecret) {
                    "Secret stored securely"
                } else {
                    "Secret missing"
                },
            )
            if (identity.kind == IdentityKind.PASSWORD) {
                TextButton(
                    onClick = { onEditPasswordIdentity(identity) },
                    modifier = Modifier.testTag("identity_edit_${identity.id}"),
                ) {
                    Text("Edit")
                }
            }
        }
    }
}

@Composable
private fun PasswordIdentityEditorScreen(
    identity: Identity?,
    identityRepository: IdentityRepository,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    val isEditing = identity != null
    val coroutineScope = rememberCoroutineScope()
    var name by remember(identity?.id) { mutableStateOf(identity?.name.orEmpty()) }
    var password by remember(identity?.id) { mutableStateOf("") }
    var showPassword by remember(identity?.id) { mutableStateOf(false) }
    var nameError by remember(identity?.id) { mutableStateOf<String?>(null) }
    var passwordError by remember(identity?.id) { mutableStateOf<String?>(null) }
    var saveError by remember(identity?.id) { mutableStateOf<String?>(null) }

    AppScreenScaffold(
        title = if (isEditing) "Edit password identity" else "Create password identity",
        supportingText = if (isEditing) {
            "Update the identity name and optionally replace the stored password. Leaving the password blank keeps the current secret."
        } else {
            "Password identities keep the secret masked by default and reusable from host authentication flows."
        },
        modifier = Modifier.testTag("identity_password_editor"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                    saveError = null
                },
                label = { Text("Identity name") },
                isError = nameError != null,
                supportingText = {
                    Text(nameError ?: "A clear label helps distinguish reusable credentials.")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("identity_name_field"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    passwordError = null
                    saveError = null
                },
                label = { Text(if (isEditing) "New password" else "Password") },
                isError = passwordError != null,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                supportingText = {
                    Text(
                        passwordError ?: if (isEditing) {
                            "Leave blank to keep the current password secret."
                        } else {
                            "Stored securely and masked by default."
                        },
                    )
                },
                trailingIcon = {
                    TextButton(
                        onClick = { showPassword = !showPassword },
                        modifier = Modifier.testTag("identity_password_toggle"),
                    ) {
                        Text(if (showPassword) "Hide" else "Show")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("identity_password_field"),
            )
            saveError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("identity_save_error"),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("identity_editor_cancel"),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val trimmedName = name.trim()
                        val requiresPassword = identity?.hasSecret != true
                        nameError = if (trimmedName.isBlank()) "Identity name is required." else null
                        passwordError = if (password.isBlank() && requiresPassword) {
                            "Password is required."
                        } else {
                            null
                        }
                        if (nameError != null || passwordError != null) {
                            return@Button
                        }

                        coroutineScope.launch {
                            runCatching {
                                identityRepository.upsert(
                                    identity = Identity(
                                        id = identity?.id ?: 0,
                                        name = trimmedName,
                                        kind = IdentityKind.PASSWORD,
                                        username = identity?.username,
                                        publicKey = null,
                                        hasSecret = identity?.hasSecret == true || password.isNotBlank(),
                                        hasPassphrase = false,
                                        createdAt = identity?.createdAt ?: Instant.now(),
                                        updatedAt = Instant.now(),
                                    ),
                                    secrets = password.takeIf(String::isNotBlank)?.let { enteredPassword ->
                                        IdentitySecretMaterial(primarySecret = enteredPassword)
                                    },
                                )
                            }.onSuccess {
                                onSaved()
                            }.onFailure { throwable ->
                                saveError = throwable.message ?: "Unable to save the identity."
                            }
                        }
                    },
                    modifier = Modifier.testTag("identity_editor_save"),
                ) {
                    Text(if (isEditing) "Save changes" else "Save identity")
                }
            }
        }
    }
}

@Composable
private fun IdentityStubScreen(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    AppScreenScaffold(
        title = title,
        supportingText = supportingText,
        modifier = modifier,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag("identity_stub_back"),
        ) {
            Text("Back to identities")
        }
    }
}
