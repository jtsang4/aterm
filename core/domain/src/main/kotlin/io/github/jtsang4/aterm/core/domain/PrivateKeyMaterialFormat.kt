package io.github.jtsang4.aterm.core.domain

object PrivateKeyMaterialFormat {
    private val privateKeyHeaderRegex = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
    private val importedKeyPemHeaders = listOf(
        "BEGIN RSA PRIVATE KEY",
        "BEGIN EC PRIVATE KEY",
        "BEGIN DSA PRIVATE KEY",
        "BEGIN ENCRYPTED PRIVATE KEY",
    )
    private val encryptedPrivateKeyMarkers = listOf(
        "Proc-Type: 4,ENCRYPTED",
        "DEK-Info:",
        "BEGIN OPENSSH PRIVATE KEY",
    )

    fun looksLikePrivateKey(material: String): Boolean =
        privateKeyHeaderRegex.containsMatchIn(material)

    fun looksLikeImportedKeyPemPrivateKey(material: String): Boolean =
        importedKeyPemHeaders.any(material::contains)

    fun looksLikeRuntimePemPrivateKey(material: String): Boolean =
        material.contains("BEGIN PRIVATE KEY") || looksLikeImportedKeyPemPrivateKey(material)

    fun looksLikeEncryptedPrivateKey(material: String): Boolean =
        encryptedPrivateKeyMarkers.any(material::contains)
}
