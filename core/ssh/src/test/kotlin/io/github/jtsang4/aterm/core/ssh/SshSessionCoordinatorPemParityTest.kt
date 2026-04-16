package io.github.jtsang4.aterm.core.ssh

import io.github.jtsang4.aterm.core.domain.PrivateKeyMaterialFormat
import java.io.StringWriter
import java.security.KeyPair
import org.apache.sshd.common.util.security.SecurityUtils
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class SshSessionCoordinatorPemParityTest {
    @Test
    fun runtime_pem_detection_accepts_legacy_encrypted_ec_pem() {
        val keyPair = SecurityUtils.getKeyPairGenerator("EC").apply { initialize(256) }.generateKeyPair()
        val privateKey = writeLegacyEncryptedPemPrivateKey(keyPair, passphrase = "legacy-passphrase")

        assertEquals(true, PrivateKeyMaterialFormat.looksLikeRuntimePemPrivateKey(privateKey))
        assertEquals(true, reflectCoordinatorPemDetection(privateKey))
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

    private fun reflectCoordinatorPemDetection(privateKey: String): Boolean {
        val companionField = SshSessionCoordinator::class.java.getDeclaredField("Companion")
        val companion = companionField.get(null)
        val method = companion.javaClass.getDeclaredMethod("looksLikePemPrivateKey", String::class.java)
        method.isAccessible = true
        return method.invoke(companion, privateKey) as Boolean
    }
}
