package io.github.jtsang4.aterm.core.domain.model

fun Identity.kindLabel(): String = when (kind) {
    IdentityKind.PASSWORD -> "Password identity"
    IdentityKind.IMPORTED_KEY -> "Imported key identity"
    IdentityKind.GENERATED_KEY -> "Generated key identity"
}

fun Identity.secretStatusLabel(): String = if (hasSecret) {
    if (kind == IdentityKind.PASSWORD) {
        "Password stored securely"
    } else {
        "Private key stored securely"
    }
} else {
    "Secret missing"
}

fun Identity.passphraseStatusLabel(): String = if (hasPassphrase) {
    "Passphrase required for this key"
} else {
    "No key passphrase required"
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
