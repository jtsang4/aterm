package io.github.jtsang4.aterm.core.domain.model

import java.time.Instant

enum class IdentityKind {
    PASSWORD,
    IMPORTED_KEY,
    GENERATED_KEY,
}

data class Identity(
    val id: Long = 0,
    val name: String,
    val kind: IdentityKind,
    val username: String? = null,
    val publicKey: String? = null,
    val hasSecret: Boolean = false,
    val hasPassphrase: Boolean = false,
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
}

data class IdentitySecretMaterial(
    val primarySecret: String? = null,
    val passphrase: String? = null,
) {
    val isEmpty: Boolean = primarySecret.isNullOrEmpty() && passphrase.isNullOrEmpty()
}
