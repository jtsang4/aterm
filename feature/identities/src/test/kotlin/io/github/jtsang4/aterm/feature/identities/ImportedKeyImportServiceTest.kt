package io.github.jtsang4.aterm.feature.identities

import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.security.KeyPair
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
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
    fun unencrypted_private_key_ignores_stray_passphrase_text() {
        val keyPair = SecurityUtils.getKeyPairGenerator("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKey = writePrivateKey(keyPair, passphrase = null)

        val result = service.parse(privateKey, passphrase = "stray-passphrase")

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
    fun legacy_aes128cbc_rsa_pem_requires_passphrase_then_imports_with_correct_retry() {
        val keyPair = SecurityUtils.getKeyPairGenerator("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKey = writeLegacyEncryptedPemPrivateKey(keyPair, passphrase = "legacy-passphrase")

        val requiredResult = service.parse(privateKey, passphrase = null)
        val wrongResult = service.parse(privateKey, passphrase = "wrong-passphrase")
        val successResult = service.parse(privateKey, passphrase = "legacy-passphrase")

        assertTrue(privateKey.contains("BEGIN RSA PRIVATE KEY"))
        assertTrue(privateKey.contains("AES-128-CBC"))
        assertEquals(ImportedKeyParseResult.PassphraseRequired, requiredResult)
        assertEquals(ImportedKeyParseResult.IncorrectPassphrase, wrongResult)
        assertTrue(successResult is ImportedKeyParseResult.Success)
        successResult as ImportedKeyParseResult.Success
        assertTrue(successResult.publicKey.startsWith("ssh-rsa "))
        assertEquals(true, successResult.hasPassphrase)
    }

    @Test
    fun legacy_aes128cbc_ec_pem_requires_passphrase_then_imports_with_correct_retry() {
        val keyPair = SecurityUtils.getKeyPairGenerator("EC").apply { initialize(256) }.generateKeyPair()
        val privateKey = writeLegacyEncryptedPemPrivateKey(keyPair, passphrase = "legacy-passphrase")

        val requiredResult = service.parse(privateKey, passphrase = null)
        val wrongResult = service.parse(privateKey, passphrase = "wrong-passphrase")
        val successResult = service.parse(privateKey, passphrase = "legacy-passphrase")

        assertTrue(privateKey.contains("BEGIN EC PRIVATE KEY"))
        assertTrue(privateKey.contains("AES-128-CBC"))
        assertEquals(ImportedKeyParseResult.PassphraseRequired, requiredResult)
        assertEquals(ImportedKeyParseResult.IncorrectPassphrase, wrongResult)
        assertTrue(successResult is ImportedKeyParseResult.Success)
        successResult as ImportedKeyParseResult.Success
        assertTrue(successResult.publicKey.startsWith("ecdsa-sha2-"))
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

    private fun writeLegacyEncryptedPemPrivateKey(keyPair: KeyPair, passphrase: String): String {
        val output = StringWriter()
        JcaPEMWriter(output).use { writer ->
            writer.writeObject(
                keyPair.private,
                JcePEMEncryptorBuilder("AES-128-CBC")
                    .setProvider("BC")
                    .build(passphrase.toCharArray()),
            )
        }
        return output.toString()
    }
}
