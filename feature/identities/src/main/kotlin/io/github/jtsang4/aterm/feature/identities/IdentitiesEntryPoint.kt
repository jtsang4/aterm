package io.github.jtsang4.aterm.feature.identities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.SecretMaterialUnavailableException
import io.github.jtsang4.aterm.core.domain.model.SecretStorageState
import io.github.jtsang4.aterm.core.domain.model.distinguishingDetail
import io.github.jtsang4.aterm.core.domain.model.kindLabel
import io.github.jtsang4.aterm.core.domain.model.passphraseStatusLabel
import io.github.jtsang4.aterm.core.domain.model.publicKeyPreview
import io.github.jtsang4.aterm.core.domain.model.secretStatusLabel
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object IdentitiesEntryPoint {
    const val route = "identities"
}

sealed interface IdentityLaunchDestination {
    data object Library : IdentityLaunchDestination
    data object CreatePassword : IdentityLaunchDestination
    data object ImportKey : IdentityLaunchDestination
    data object GenerateKey : IdentityLaunchDestination
}

private sealed interface IdentityDestination {
    data object Library : IdentityDestination
    data class PasswordEditor(val identity: Identity?) : IdentityDestination
    data class KeyEditor(
        val identity: Identity?,
        val initialMode: KeyEditorMode = when (identity?.kind) {
            IdentityKind.GENERATED_KEY -> KeyEditorMode.Generate
            else -> KeyEditorMode.Import
        },
    ) : IdentityDestination
    data class DeleteIdentity(val identity: Identity) : IdentityDestination
}

@Composable
fun IdentitiesScreen(
    identityRepository: IdentityRepository,
    importedKeyImportService: ImportedKeyImportService = ImportedKeyImportService(),
    generatedKeyIdentityService: GeneratedKeyIdentityService = GeneratedKeyIdentityService(),
    initialDestination: IdentityLaunchDestination = IdentityLaunchDestination.Library,
    onCloseRequest: (() -> Unit)? = null,
    onIdentitySaved: ((Identity) -> Unit)? = null,
) {
    val identities by identityRepository.observeIdentities().collectAsState(initial = emptyList())
    var destination by remember(initialDestination) {
        mutableStateOf(initialDestination.toInternalDestination())
    }

    when (val currentDestination = destination) {
        is IdentityDestination.KeyEditor -> KeyIdentityEditorScreen(
            identity = currentDestination.identity,
            initialMode = currentDestination.initialMode,
            identityRepository = identityRepository,
            importService = importedKeyImportService,
            generatedKeyIdentityService = generatedKeyIdentityService,
            onCancel = {
                if (onCloseRequest != null) {
                    onCloseRequest()
                } else {
                    destination = IdentityDestination.Library
                }
            },
            onSaved = { savedIdentity ->
                if (onIdentitySaved != null) {
                    onIdentitySaved(savedIdentity)
                } else {
                    destination = IdentityDestination.Library
                }
            },
        )

        IdentityDestination.Library -> IdentityLibraryScreen(
            identities = identities,
            onCreatePassword = { destination = IdentityDestination.PasswordEditor(identity = null) },
            onImportKey = { destination = IdentityDestination.KeyEditor(identity = null, initialMode = KeyEditorMode.Import) },
            onGenerateKey = { destination = IdentityDestination.KeyEditor(identity = null, initialMode = KeyEditorMode.Generate) },
            onEditPasswordIdentity = { destination = IdentityDestination.PasswordEditor(identity = it) },
            onEditKeyIdentity = { destination = IdentityDestination.KeyEditor(identity = it) },
            onDeleteIdentity = { destination = IdentityDestination.DeleteIdentity(identity = it) },
        )

        is IdentityDestination.PasswordEditor -> PasswordIdentityEditorScreen(
            identity = currentDestination.identity,
            identityRepository = identityRepository,
            onCancel = {
                if (onCloseRequest != null) {
                    onCloseRequest()
                } else {
                    destination = IdentityDestination.Library
                }
            },
            onSaved = { savedIdentity ->
                if (onIdentitySaved != null) {
                    onIdentitySaved(savedIdentity)
                } else {
                    destination = IdentityDestination.Library
                }
            },
        )

        is IdentityDestination.DeleteIdentity -> DeleteIdentityScreen(
            identity = currentDestination.identity,
            identityRepository = identityRepository,
            onCancel = { destination = IdentityDestination.Library },
            onDeleted = { destination = IdentityDestination.Library },
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
    onEditKeyIdentity: (Identity) -> Unit,
    onDeleteIdentity: (Identity) -> Unit,
) {
    AppScreenScaffold(
        title = "Identities",
        supportingText = "Reusable password and key identities are stored locally and can be linked from host authentication flows.",
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
                            text = "Create a password identity, import a private key, or generate a new key pair from this first-run state.",
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
                            onEditKeyIdentity = onEditKeyIdentity,
                            onDeleteIdentity = onDeleteIdentity,
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
    onEditKeyIdentity: (Identity) -> Unit,
    onDeleteIdentity: (Identity) -> Unit,
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
                text = identity.kindLabel(),
            )
            Text(
                text = identity.secretStatusLabel(),
            )
            if (identity.requiresSecretRepair) {
                Text(
                    text = "Repair needed: Keystore access changed or the saved secret was invalidated. Re-enter the secret to continue.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("identity_repair_hint_${identity.id}"),
                )
            }
            if (identity.kind != IdentityKind.PASSWORD) {
                Text(
                    text = identity.passphraseStatusLabel(),
                )
                Text(
                    text = "Distinguishing detail: ${identity.distinguishingDetail()}",
                    modifier = Modifier.testTag("identity_detail_${identity.id}"),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        if (identity.kind == IdentityKind.PASSWORD) {
                            onEditPasswordIdentity(identity)
                        } else {
                            onEditKeyIdentity(identity)
                        }
                    },
                    modifier = Modifier.testTag("identity_edit_${identity.id}"),
                ) {
                    Text("Edit")
                }
                TextButton(
                    onClick = { onDeleteIdentity(identity) },
                    modifier = Modifier.testTag("identity_delete_${identity.id}"),
                ) {
                    Text("Delete")
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
    onSaved: (Identity) -> Unit,
) {
    val isEditing = identity != null
    val requiresRepair = identity?.requiresSecretRepair == true
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
            if (requiresRepair) {
                "The stored password is unavailable. Re-enter it to repair this identity."
            } else {
                "Update the identity name and optionally replace the stored password. Leaving the password blank keeps the current secret."
            }
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
                        val requiresPassword = identity?.hasAccessibleSecret != true
                        nameError = if (trimmedName.isBlank()) "Identity name is required." else null
                        passwordError = if (password.isBlank() && requiresPassword) {
                            if (requiresRepair) {
                                "Re-enter the password to repair this identity."
                            } else {
                                "Password is required."
                            }
                        } else {
                            null
                        }
                        if (nameError != null || passwordError != null) {
                            return@Button
                        }

                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            runCatching {
                                identityRepository.upsert(
                                    identity = Identity(
                                        id = identity?.id ?: 0,
                                        name = trimmedName,
                                        kind = IdentityKind.PASSWORD,
                                        publicKey = null,
                                        hasSecret = identity?.hasSecret == true || password.isNotBlank(),
                                        hasPassphrase = false,
                                        secretStorageState = when {
                                            password.isNotBlank() -> SecretStorageState.AVAILABLE
                                            identity != null -> identity.secretStorageState
                                            else -> SecretStorageState.MISSING
                                        },
                                        passphraseStorageState = SecretStorageState.MISSING,
                                        createdAt = identity?.createdAt ?: Instant.now(),
                                        updatedAt = Instant.now(),
                                    ),
                                    secrets = password.takeIf(String::isNotBlank)?.let { enteredPassword ->
                                        IdentitySecretMaterial(primarySecret = enteredPassword)
                                    },
                                )
                            }.onSuccess { savedIdentity ->
                                withContext(Dispatchers.Main.immediate) {
                                    onSaved(savedIdentity)
                                }
                            }.onFailure { throwable ->
                                withContext(Dispatchers.Main.immediate) {
                                    saveError = when (throwable) {
                                        is SecretMaterialUnavailableException ->
                                            "Stored password is unavailable. Re-enter it to repair this identity."
                                        else -> throwable.message ?: "Unable to save the identity."
                                    }
                                }
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
private fun KeyIdentityEditorScreen(
    identity: Identity?,
    initialMode: KeyEditorMode,
    identityRepository: IdentityRepository,
    importService: ImportedKeyImportService,
    generatedKeyIdentityService: GeneratedKeyIdentityService,
    onCancel: () -> Unit,
    onSaved: (Identity) -> Unit,
) {
    val isEditing = identity != null
    val requiresRepair = identity?.requiresSecretRepair == true
    val canMaintainExistingSecret = identity?.secretStorageState == SecretStorageState.AVAILABLE
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var mode by remember(identity?.id) {
        mutableStateOf(initialMode)
    }
    var name by remember(identity?.id) { mutableStateOf(identity?.name.orEmpty()) }
    var privateKeyMaterial by remember(identity?.id) { mutableStateOf("") }
    var publicKey by remember(identity?.id) { mutableStateOf(identity?.publicKey.orEmpty()) }
    var passphrase by remember(identity?.id) { mutableStateOf("") }
    var savePassphrase by remember(identity?.id) {
        mutableStateOf(identity?.passphraseStorageState == SecretStorageState.AVAILABLE)
    }
    var replacePrivateKey by remember(identity?.id) { mutableStateOf(identity == null || !canMaintainExistingSecret) }
    var passphraseRequested by remember(identity?.id) { mutableStateOf(false) }
    var showPassphrase by remember(identity?.id) { mutableStateOf(false) }
    var nameError by remember(identity?.id) { mutableStateOf<String?>(null) }
    var keyMaterialError by remember(identity?.id) { mutableStateOf<String?>(null) }
    var passphraseError by remember(identity?.id) { mutableStateOf<String?>(null) }
    var saveError by remember(identity?.id) { mutableStateOf<String?>(null) }
    var clipboardStatus by remember(identity?.id) { mutableStateOf<String?>(null) }

    AppScreenScaffold(
        title = when {
            isEditing && identity?.kind == IdentityKind.GENERATED_KEY -> "Edit generated key identity"
            isEditing -> "Edit key identity"
            mode == KeyEditorMode.Generate -> "Generate key identity"
            else -> "Import key identity"
        },
        supportingText = when {
            isEditing && requiresRepair && canMaintainExistingSecret ->
                "The private key is still available, but the passphrase is not ready. Re-enter, update, or clear the saved passphrase without replacing the key material."
            isEditing && requiresRepair ->
                "The saved private key is unavailable. Replace the blocked secret material to repair this identity."
            isEditing ->
                "Update the identity name, keep the existing private key, add or update its saved passphrase, or replace it with new imported or generated key material."
            mode == KeyEditorMode.Generate -> "Generate a reusable key pair, then copy or review the public key so you can install it on a server."
            else -> "Import supported OpenSSH or PEM private keys. Encrypted keys prompt for a passphrase and keep your draft intact if the first attempt fails."
        },
        modifier = Modifier.testTag("identity_key_editor"),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isEditing || replacePrivateKey) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("identity_key_mode_row"),
                ) {
                    Button(
                        onClick = {
                            mode = KeyEditorMode.Import
                            privateKeyMaterial = ""
                            publicKey = if (isEditing && !replacePrivateKey) identity?.publicKey.orEmpty() else ""
                            passphrase = ""
                            savePassphrase = false
                            passphraseRequested = false
                            saveError = null
                            clipboardStatus = null
                        },
                        modifier = Modifier.testTag("identity_import_key_action_inline"),
                    ) {
                        Text("Import")
                    }
                    Button(
                        onClick = {
                            mode = KeyEditorMode.Generate
                            privateKeyMaterial = ""
                            publicKey = ""
                            passphrase = ""
                            savePassphrase = false
                            passphraseRequested = false
                            saveError = null
                            clipboardStatus = null
                        },
                        modifier = Modifier.testTag("identity_generate_key_action_inline"),
                    ) {
                        Text("Generate")
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                    saveError = null
                },
                label = { Text("Identity name") },
                isError = nameError != null,
                supportingText = { Text(nameError ?: "Use a label that helps distinguish this imported key later.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("identity_import_name_field"),
            )
            if (isEditing) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("identity_replace_key_row"),
                ) {
                    TextButton(
                        onClick = {
                            replacePrivateKey = false
                            privateKeyMaterial = ""
                            publicKey = identity?.publicKey.orEmpty()
                            passphrase = ""
                            savePassphrase = identity?.passphraseStorageState == SecretStorageState.AVAILABLE
                            passphraseRequested = false
                            keyMaterialError = null
                            passphraseError = null
                            saveError = null
                        },
                        modifier = Modifier.testTag("identity_keep_existing_secret"),
                        enabled = canMaintainExistingSecret,
                    ) {
                        Text("Keep existing secret")
                    }
                    TextButton(
                        onClick = {
                            replacePrivateKey = true
                            privateKeyMaterial = ""
                            publicKey = ""
                            passphrase = ""
                            savePassphrase = identity?.passphraseStorageState == SecretStorageState.AVAILABLE
                            passphraseRequested = false
                            keyMaterialError = null
                            passphraseError = null
                            saveError = null
                        },
                        modifier = Modifier.testTag("identity_replace_secret"),
                    ) {
                        Text("Replace secret")
                    }
                }
            }
            val requiresKeyEditor = !isEditing || replacePrivateKey
            val isPassphraseMaintenance = isEditing && !replacePrivateKey && identity?.hasPassphrase == true
            val maintainedIdentity = identity?.takeIf { isPassphraseMaintenance }
            if (requiresKeyEditor) {
                if (mode == KeyEditorMode.Import) {
                    OutlinedTextField(
                        value = privateKeyMaterial,
                        onValueChange = {
                            privateKeyMaterial = it
                            keyMaterialError = null
                            passphraseError = null
                            saveError = null
                            clipboardStatus = null
                        },
                        label = { Text("Private key material") },
                        isError = keyMaterialError != null,
                        supportingText = {
                            Text(
                                keyMaterialError ?: "Paste the full private key block. Unsupported or malformed keys are rejected without saving a partial identity.",
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                            .testTag("identity_import_key_field"),
                    )
                } else {
                    Button(
                        onClick = {
                            val generated = generatedKeyIdentityService.generate()
                            privateKeyMaterial = generated.privateKeyMaterial
                            publicKey = generated.publicKey
                            passphrase = ""
                            savePassphrase = false
                            passphraseRequested = false
                            keyMaterialError = null
                            passphraseError = null
                            saveError = null
                            clipboardStatus = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("identity_generate_execute"),
                    ) {
                        Text(if (privateKeyMaterial.isBlank()) "Generate key pair" else "Regenerate key pair")
                    }
                }
            }
            val showPassphraseField = mode == KeyEditorMode.Import && (passphraseRequested || (isEditing && replacePrivateKey))
            if (showPassphraseField) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        passphraseError = null
                        saveError = null
                    },
                    label = { Text("Passphrase") },
                    isError = passphraseError != null,
                    visualTransformation = if (showPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    supportingText = {
                        Text(passphraseError ?: "Enter the passphrase needed to unlock this private key.")
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { showPassphrase = !showPassphrase },
                            modifier = Modifier.testTag("identity_import_passphrase_toggle"),
                        ) {
                            Text(if (showPassphrase) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_import_passphrase_field"),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_import_passphrase_persistence_row"),
                ) {
                    Checkbox(
                        checked = savePassphrase,
                        onCheckedChange = { checked ->
                            savePassphrase = checked
                            saveError = null
                        },
                        modifier = Modifier.testTag("identity_import_save_passphrase_toggle"),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Save passphrase for future connections")
                        Text(
                            text = if (savePassphrase) {
                                "This imported key will stay ready to connect after import."
                            } else {
                                "Leave unchecked to import without saving the passphrase. The identity will stay blocked until you repair it later."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("identity_import_passphrase_persistence_hint"),
                        )
                    }
                }
            }
            if (maintainedIdentity != null) {
                Text(
                    text = "Current passphrase state: ${maintainedIdentity.passphraseStatusLabel()}",
                    modifier = Modifier.testTag("identity_existing_passphrase_status"),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        passphraseError = null
                        saveError = null
                    },
                    label = { Text("Passphrase") },
                    isError = passphraseError != null,
                    visualTransformation = if (showPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    supportingText = {
                        Text(
                            passphraseError ?: if (maintainedIdentity.passphraseStorageState == SecretStorageState.AVAILABLE) {
                                "Leave blank to keep the current saved passphrase, or enter a replacement to validate and update it."
                            } else {
                                "Enter the passphrase to make this encrypted key ready again."
                            },
                        )
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { showPassphrase = !showPassphrase },
                            modifier = Modifier.testTag("identity_import_passphrase_toggle"),
                        ) {
                            Text(if (showPassphrase) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_import_passphrase_field"),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_import_passphrase_persistence_row"),
                ) {
                    Checkbox(
                        checked = savePassphrase,
                        onCheckedChange = { checked ->
                            savePassphrase = checked
                            passphraseError = null
                            saveError = null
                        },
                        modifier = Modifier.testTag("identity_import_save_passphrase_toggle"),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Save passphrase for future connections")
                        Text(
                            text = if (savePassphrase) {
                                "Saving changes validates this passphrase against the existing encrypted key and keeps the same identity record ready to connect."
                            } else if (maintainedIdentity.passphraseStorageState == SecretStorageState.AVAILABLE) {
                                "Saving changes clears the stored passphrase and returns this identity to a non-ready state."
                            } else {
                                "Leave unchecked to keep this encrypted key in a truthful non-ready state until you repair it later."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("identity_import_passphrase_persistence_hint"),
                        )
                    }
                }
            }
            if (publicKey.isNotBlank()) {
                OutlinedTextField(
                    value = publicKey,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Public key") },
                    supportingText = { Text("Copy or review this public key before installing it on a server.") },
                    colors = TextFieldDefaults.colors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .testTag("identity_public_key_field"),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(publicKey))
                            clipboardStatus = "Public key copied"
                        },
                        modifier = Modifier.testTag("identity_public_key_copy"),
                    ) {
                        Text("Copy public key")
                    }
                    Text(
                        text = "Preview: ${publicKeyPreview(publicKey)}",
                        modifier = Modifier.testTag("identity_public_key_preview"),
                    )
                }
            }
            clipboardStatus?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.testTag("identity_public_key_copy_status"),
                )
            }
            saveError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("identity_import_save_error"),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("identity_import_cancel"),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val trimmedName = name.trim()
                        nameError = if (trimmedName.isBlank()) "Identity name is required." else null
                        val normalizedKeyMaterial = privateKeyMaterial.trim()
                        if (requiresKeyEditor) {
                            keyMaterialError = if (normalizedKeyMaterial.isBlank()) {
                                if (mode == KeyEditorMode.Generate) {
                                    "Generate a key pair before saving."
                                } else {
                                    "Private key material is required."
                                }
                            } else {
                                null
                            }
                        }
                        if (nameError != null || keyMaterialError != null) {
                            return@Button
                        }

                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            val existing = identity
                            val parsed = if (requiresKeyEditor) {
                                when (mode) {
                                    KeyEditorMode.Import -> importService.parse(
                                        normalizedKeyMaterial,
                                        passphrase.takeIf { passphraseRequested || passphrase.isNotBlank() },
                                    )
                                    KeyEditorMode.Generate -> ImportedKeyParseResult.Success(
                                        publicKey = publicKey,
                                        hasPassphrase = false,
                                    )
                                }
                            } else {
                                ImportedKeyParseResult.Success(
                                    publicKey = existing?.publicKey.orEmpty(),
                                    hasPassphrase = existing?.hasPassphrase == true,
                                )
                            }

                            when (parsed) {
                                ImportedKeyParseResult.PassphraseRequired -> {
                                    passphraseRequested = true
                                    passphraseError = "Passphrase is required to unlock this private key."
                                }

                                ImportedKeyParseResult.IncorrectPassphrase -> {
                                    passphraseRequested = true
                                    passphraseError = "Passphrase was incorrect. Try again."
                                }

                                ImportedKeyParseResult.InvalidKeyMaterial -> {
                                    saveError = "Unsupported or malformed private key. Import a supported OpenSSH or PEM private key."
                                }

                                is ImportedKeyParseResult.Success -> {
                                    val maintenanceUpdate = if (isPassphraseMaintenance) {
                                        when {
                                            !savePassphrase -> PassphraseMaintenancePlan(
                                                secrets = null,
                                                passphraseStorageState = when (existing?.passphraseStorageState) {
                                                    SecretStorageState.AVAILABLE -> SecretStorageState.MISSING
                                                    else -> existing?.passphraseStorageState ?: SecretStorageState.MISSING
                                                },
                                            )

                                            passphrase.isBlank() && existing?.passphraseStorageState == SecretStorageState.AVAILABLE ->
                                                PassphraseMaintenancePlan(
                                                    secrets = null,
                                                    passphraseStorageState = SecretStorageState.AVAILABLE,
                                                )

                                            passphrase.isBlank() -> {
                                                passphraseError = "Enter the passphrase to save or repair this encrypted key."
                                                null
                                            }

                                            else -> {
                                                val existingPrivateKey = runCatching {
                                                    identityRepository.getSecretMaterial(requireNotNull(existing).id)?.primarySecret
                                                }.getOrElse { throwable ->
                                                    saveError = when (throwable) {
                                                        is SecretMaterialUnavailableException ->
                                                            "Stored secret material is unavailable. Replace it to repair this identity."
                                                        else -> throwable.message ?: "Unable to read the existing key material."
                                                    }
                                                    null
                                                }
                                                if (existingPrivateKey.isNullOrBlank()) {
                                                    saveError = "Stored secret material is unavailable. Replace it to repair this identity."
                                                    null
                                                } else {
                                                    when (importService.parse(existingPrivateKey, passphrase)) {
                                                        ImportedKeyParseResult.PassphraseRequired -> {
                                                            passphraseError = "Enter the passphrase to save or repair this encrypted key."
                                                            null
                                                        }

                                                        ImportedKeyParseResult.IncorrectPassphrase -> {
                                                            passphraseError = "Passphrase was incorrect. Try again."
                                                            null
                                                        }

                                                        ImportedKeyParseResult.InvalidKeyMaterial -> {
                                                            saveError = "Unable to validate the existing encrypted key. Replace it to repair this identity."
                                                            null
                                                        }

                                                        is ImportedKeyParseResult.Success -> PassphraseMaintenancePlan(
                                                            secrets = IdentitySecretMaterial(passphrase = passphrase),
                                                            passphraseStorageState = SecretStorageState.AVAILABLE,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                    if (isPassphraseMaintenance && maintenanceUpdate == null) {
                                        return@launch
                                    }
                                    val targetKind = when {
                                        !requiresKeyEditor -> existing?.kind ?: IdentityKind.IMPORTED_KEY
                                        mode == KeyEditorMode.Generate -> IdentityKind.GENERATED_KEY
                                        else -> IdentityKind.IMPORTED_KEY
                                    }
                                    runCatching {
                                        identityRepository.upsert(
                                            identity = Identity(
                                                id = existing?.id ?: 0,
                                                name = trimmedName,
                                                kind = targetKind,
                                                publicKey = parsed.publicKey,
                                                hasSecret = existing?.hasSecret == true || requiresKeyEditor,
                                                hasPassphrase = if (requiresKeyEditor) parsed.hasPassphrase else existing?.hasPassphrase == true,
                                                secretStorageState = when {
                                                    requiresKeyEditor -> SecretStorageState.AVAILABLE
                                                    existing != null -> existing.secretStorageState
                                                    else -> SecretStorageState.MISSING
                                                },
                                                passphraseStorageState = when {
                                                    maintenanceUpdate != null -> maintenanceUpdate.passphraseStorageState
                                                    !parsed.hasPassphrase && !requiresKeyEditor ->
                                                        existing?.passphraseStorageState ?: SecretStorageState.MISSING
                                                    parsed.hasPassphrase && savePassphrase && passphrase.isNotBlank() ->
                                                        SecretStorageState.AVAILABLE
                                                    parsed.hasPassphrase -> SecretStorageState.BLOCKED
                                                    else -> SecretStorageState.MISSING
                                                },
                                                createdAt = existing?.createdAt ?: Instant.now(),
                                                updatedAt = Instant.now(),
                                            ),
                                            secrets = when {
                                                maintenanceUpdate != null -> maintenanceUpdate.secrets
                                                requiresKeyEditor -> {
                                                IdentitySecretMaterial(
                                                    primarySecret = normalizedKeyMaterial,
                                                    passphrase = passphrase.takeIf {
                                                        parsed.hasPassphrase && savePassphrase && it.isNotBlank()
                                                    },
                                                )
                                                }

                                                else -> null
                                            },
                                        )
                                    }.onSuccess { savedIdentity ->
                                        withContext(Dispatchers.Main.immediate) {
                                            onSaved(savedIdentity)
                                        }
                                    }.onFailure { throwable ->
                                        withContext(Dispatchers.Main.immediate) {
                                            saveError = when (throwable) {
                                                is SecretMaterialUnavailableException ->
                                                    "Stored secret material is unavailable. Replace it to repair this identity."
                                                else -> throwable.message ?: "Unable to save the key identity."
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("identity_import_save"),
                ) {
                    Text(
                        when {
                            isEditing -> "Save changes"
                            mode == KeyEditorMode.Generate -> "Save generated key"
                            else -> "Import key"
                        },
                    )
                }
            }
        }
    }
}

private data class PassphraseMaintenancePlan(
    val secrets: IdentitySecretMaterial?,
    val passphraseStorageState: SecretStorageState,
)

@Composable
private fun DeleteIdentityScreen(
    identity: Identity,
    identityRepository: IdentityRepository,
    onCancel: () -> Unit,
    onDeleted: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    AppScreenScaffold(
        title = "Delete identity",
        supportingText = "Deleting an identity can leave linked hosts needing repair. Review the identity detail before confirming.",
        modifier = Modifier.testTag("identity_delete_confirmation"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Delete ${identity.name}?",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Affected hosts will stay saved, show a repair state, and can be relinked from the host editor.",
                modifier = Modifier.testTag("identity_delete_warning"),
            )
            Text(
                text = "Distinguishing detail: ${identity.distinguishingDetail()}",
                modifier = Modifier.testTag("identity_delete_detail"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("identity_delete_cancel"),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            identityRepository.deleteIdentity(identity.id)
                            withContext(Dispatchers.Main.immediate) {
                                onDeleted()
                            }
                        }
                    },
                    modifier = Modifier.testTag("identity_delete_confirm"),
                ) {
                    Text("Delete identity")
                }
            }
        }
    }
}

private enum class KeyEditorMode {
    Import,
    Generate,
}

private fun publicKeyPreview(publicKey: String): String =
    publicKey
        .split(' ')
        .getOrNull(1)
        ?.takeIf(String::isNotBlank)
        ?.let { keyBody ->
            if (keyBody.length <= 18) {
                keyBody
            } else {
                "${keyBody.take(12)}…${keyBody.takeLast(6)}"
            }
        }
        .orEmpty()

private fun IdentityLaunchDestination.toInternalDestination(): IdentityDestination = when (this) {
    IdentityLaunchDestination.Library -> IdentityDestination.Library
    IdentityLaunchDestination.CreatePassword -> IdentityDestination.PasswordEditor(identity = null)
    IdentityLaunchDestination.ImportKey -> IdentityDestination.KeyEditor(identity = null, initialMode = KeyEditorMode.Import)
    IdentityLaunchDestination.GenerateKey -> IdentityDestination.KeyEditor(identity = null, initialMode = KeyEditorMode.Generate)
}
