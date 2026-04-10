package io.github.jtsang4.aterm.feature.identities

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedKeyImportServiceTest {
    private val service = ImportedKeyImportService()

    @Test
    fun unencrypted_private_key_import_succeeds() {
        val keyPair = SecurityUtils.getKeyPairGenerator("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKey = writePrivateKey(keyPair, passphrase = null)

        val result = service.parse(privateKey, passphrase = null)

        assertTrue(result is ImportedKeyParseResult.Success)
        result as ImportedKeyParseResult.Success
        assertTrue(result.publicKey.startsWith("ssh-rsa "))
        assertEquals(false, result.hasPassphrase)
    }

    @Test
    fun encrypted_private_key_requires_passphrase_then_imports_with_correct_retry() {
        val keyPair = SecurityUtils.getKeyPairGenerator("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKey = writePrivateKey(keyPair, passphrase = "correct-passphrase")

        val requiredResult = service.parse(privateKey, passphrase = null)
        val wrongResult = service.parse(privateKey, passphrase = "wrong-passphrase")
        val successResult = service.parse(privateKey, passphrase = "correct-passphrase")

        assertEquals(ImportedKeyParseResult.PassphraseRequired, requiredResult)
        assertEquals(ImportedKeyParseResult.IncorrectPassphrase, wrongResult)
        assertTrue(successResult is ImportedKeyParseResult.Success)
        successResult as ImportedKeyParseResult.Success
        assertTrue(successResult.publicKey.startsWith("ssh-rsa "))
        assertEquals(true, successResult.hasPassphrase)
    }

    @Test
    fun malformed_private_key_is_rejected_clearly() {
        val result = service.parse("this is not a private key", passphrase = null)

        assertEquals(ImportedKeyParseResult.InvalidKeyMaterial, result)
    }

    private fun writePrivateKey(keyPair: KeyPair, passphrase: String?): String {
        val output = ByteArrayOutputStream()
        val encryption = passphrase?.let {
            OpenSSHKeyEncryptionContext().apply {
                setCipherType("256")
                setPassword(it)
            }
        }
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "test-key", encryption, output)
        return output.toString(Charsets.UTF_8.name())
    }
}
