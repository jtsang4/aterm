package io.github.jtsang4.aterm.core.domain.model

import java.time.Instant

enum class IdentityKind {
    PASSWORD,
    IMPORTED_KEY,
    GENERATED_KEY,
}

enum class SecretStorageState {
    MISSING,
    AVAILABLE,
    BLOCKED,
}

data class Identity(
    val id: Long = 0,
    val name: String,
    val kind: IdentityKind,
    val publicKey: String? = null,
    val hasSecret: Boolean = false,
    val hasPassphrase: Boolean = false,
    val secretStorageState: SecretStorageState = if (hasSecret) {
        SecretStorageState.AVAILABLE
    } else {
        SecretStorageState.MISSING
    },
    val passphraseStorageState: SecretStorageState = if (hasPassphrase) {
        SecretStorageState.AVAILABLE
    } else {
        SecretStorageState.MISSING
    },
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
) {
    init {
        require(name.isNotBlank()) { "Identity name must not be blank." }
        require(!(kind == IdentityKind.PASSWORD && hasPassphrase)) {
            "Password identities do not carry a separate passphrase."
        }
    }

    val usesKeyMaterial: Boolean = kind != IdentityKind.PASSWORD
    val hasAccessibleSecret: Boolean = secretStorageState == SecretStorageState.AVAILABLE
    val requiresSecretRepair: Boolean =
        secretStorageState == SecretStorageState.BLOCKED ||
            passphraseStorageState == SecretStorageState.BLOCKED
    val isAuthenticationReady: Boolean =
        hasAccessibleSecret && passphraseStorageState != SecretStorageState.BLOCKED
}

data class IdentitySecretMaterial(
    val primarySecret: String? = null,
    val passphrase: String? = null,
) {
    val isEmpty: Boolean = primarySecret.isNullOrEmpty() && passphrase.isNullOrEmpty()
}

class SecretMaterialUnavailableException(
    message: String = "Stored secret material is unavailable. Repair the identity to continue.",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
