package io.github.jtsang4.aterm.feature.identities

import java.io.ByteArrayOutputStream
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils

open class GeneratedKeyIdentityService {
    open fun generate(): GeneratedKeyMaterial {
        val keyPair = SecurityUtils.getKeyPairGenerator("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKeyMaterial = ByteArrayOutputStream().use { output ->
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "generated-key", null, output)
            output.toString(Charsets.UTF_8.name())
        }
        return GeneratedKeyMaterial(
            privateKeyMaterial = privateKeyMaterial,
            publicKey = PublicKeyEntry.toString(keyPair.public, null),
        )
    }
}

data class GeneratedKeyMaterial(
    val privateKeyMaterial: String,
    val publicKey: String,
)
