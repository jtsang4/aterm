package io.github.jtsang4.aterm.feature.identities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedKeyIdentityServiceTest {
    private val service = GeneratedKeyIdentityService()

    @Test
    fun generate_creates_private_and_public_key_material() {
        val generated = service.generate()

        assertTrue(generated.privateKeyMaterial.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertTrue(generated.privateKeyMaterial.contains("END OPENSSH PRIVATE KEY"))
        assertTrue(generated.publicKey.startsWith("ssh-rsa "))
        assertFalse(generated.publicKey.contains('\n'))
    }
}
