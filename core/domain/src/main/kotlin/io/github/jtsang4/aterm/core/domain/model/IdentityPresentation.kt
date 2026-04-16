package io.github.jtsang4.aterm.core.domain.model

fun Identity.kindLabel(): String = when (kind) {
    IdentityKind.PASSWORD -> "Password identity"
    IdentityKind.IMPORTED_KEY -> "Imported key identity"
    IdentityKind.GENERATED_KEY -> "Generated key identity"
}

fun Identity.secretStatusLabel(): String = when (secretStorageState) {
    SecretStorageState.AVAILABLE -> {
        if (kind == IdentityKind.PASSWORD) {
            "Password stored securely"
        } else {
            "Private key stored securely"
        }
    }

    SecretStorageState.BLOCKED -> {
        if (kind == IdentityKind.PASSWORD) {
            "Password unavailable until repaired"
        } else {
            "Private key unavailable until repaired"
        }
    }

    SecretStorageState.MISSING -> "Secret missing"
}

fun Identity.passphraseStatusLabel(): String = when {
    !hasPassphrase -> "No key passphrase required"
    isAuthenticationReady -> "Saved key passphrase available"
    passphraseStorageState == SecretStorageState.BLOCKED -> "Passphrase unavailable until repaired"
    else -> "Passphrase required before this key can connect"
}

fun Identity.requiresPassphraseBeforeConnecting(): Boolean =
    usesKeyMaterial &&
        hasPassphrase &&
        secretStorageState == SecretStorageState.AVAILABLE &&
        passphraseStorageState == SecretStorageState.MISSING

fun Identity.hostConnectionRequirementLabel(): String =
    if (requiresPassphraseBeforeConnecting()) {
        "Passphrase required before connecting"
    } else {
        "Identity needs repair"
    }

fun Identity.sessionConnectionRequirementLabel(): String =
    if (requiresPassphraseBeforeConnecting()) {
        "Passphrase required before connecting"
    } else {
        "Identity needs repair before connecting"
    }

fun Identity.connectionBlockedMessage(): String =
    if (requiresPassphraseBeforeConnecting()) {
        "The linked identity needs its passphrase before connecting."
    } else {
        "The linked identity needs repair before connecting."
    }

fun Identity.distinguishingDetail(): String = buildString {
    append(kindLabel())
    append(" · ")
    append(publicKeyPreview() ?: "Identity #$id")
}

fun Identity.publicKeyPreview(): String? = publicKey
    ?.split(' ')
    ?.getOrNull(1)
    ?.takeIf(String::isNotBlank)
    ?.let { keyBody ->
        if (keyBody.length <= 18) {
            keyBody
        } else {
            "${keyBody.take(12)}…${keyBody.takeLast(6)}"
        }
    }
