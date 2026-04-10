package io.github.jtsang4.aterm.core.security.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreAesGcmCipherInstrumentedTest {
    @Test
    fun encrypt_and_decrypt_round_trip_secret_material() {
        val cipher = KeystoreAesGcmCipher("test.key.${UUID.randomUUID()}")
        try {
            val payload = cipher.encrypt(
                "super-secret".encodeToByteArray(),
                associatedData = "identity:1:primary".encodeToByteArray(),
            )

            val decrypted = cipher.decrypt(
                payload,
                associatedData = "identity:1:primary".encodeToByteArray(),
            )

            assertTrue(decrypted.contentEquals("super-secret".encodeToByteArray()))
        } finally {
            cipher.deleteKey()
        }
    }

    @Test
    fun tampering_ciphertext_or_associated_data_fails_decryption() {
        val cipher = KeystoreAesGcmCipher("test.key.${UUID.randomUUID()}")
        try {
            val payload = cipher.encrypt(
                "top-secret".encodeToByteArray(),
                associatedData = "snippet:7:body".encodeToByteArray(),
            )
            val tampered = payload.copy(
                cipherText = payload.cipherText.clone().also { bytes ->
                    bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
                },
            )

            var failed = false
            try {
                cipher.decrypt(
                    tampered,
                    associatedData = "snippet:7:body".encodeToByteArray(),
                )
            } catch (_: Exception) {
                failed = true
            }

            assertTrue(failed)
            assertFalse(payload.cipherText.contentEquals(tampered.cipherText))
        } finally {
            cipher.deleteKey()
        }
    }

    @Test
    fun deleting_keystore_alias_makes_existing_payload_unrecoverable() {
        val cipher = KeystoreAesGcmCipher("test.key.${UUID.randomUUID()}")
        try {
            val payload = cipher.encrypt(
                "restart-safe-secret".encodeToByteArray(),
                associatedData = "identity:9:primary".encodeToByteArray(),
            )

            cipher.deleteKey()

            var failed = false
            try {
                cipher.decrypt(
                    payload,
                    associatedData = "identity:9:primary".encodeToByteArray(),
                )
            } catch (_: Exception) {
                failed = true
            }

            assertTrue(failed)
        } finally {
            cipher.deleteKey()
        }
    }
}
