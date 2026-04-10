package io.github.jtsang4.aterm.core.security.crypto

data class EncryptedPayload(
    val cipherText: ByteArray,
    val iv: ByteArray,
)
