package io.github.jtsang4.aterm.tools.sshfixture

import java.security.KeyPair
import org.apache.sshd.common.util.security.SecurityUtils

object KeyMaterialFactory {
    fun generateRsa(): KeyPair =
        SecurityUtils.getKeyPairGenerator("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
}

object Fingerprints {
    fun sha256(encodedKey: ByteArray): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(encodedKey)
        val base64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
        return "SHA256:$base64"
    }
}
