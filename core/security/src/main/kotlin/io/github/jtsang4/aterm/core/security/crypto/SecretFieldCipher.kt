package io.github.jtsang4.aterm.core.security.crypto

interface SecretFieldCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray? = null): EncryptedPayload
    fun decrypt(payload: EncryptedPayload, associatedData: ByteArray? = null): ByteArray
}
