package io.github.jtsang4.aterm.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateKeyMaterialFormatTest {
    @Test
    fun imported_key_pem_detection_accepts_non_rsa_encrypted_pem_families() {
        assertTrue(
            PrivateKeyMaterialFormat.looksLikeImportedKeyPemPrivateKey(
                "-----BEGIN EC PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,1234\n",
            ),
        )
        assertTrue(
            PrivateKeyMaterialFormat.looksLikeImportedKeyPemPrivateKey(
                "-----BEGIN DSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,1234\n",
            ),
        )
        assertFalse(
            PrivateKeyMaterialFormat.looksLikeImportedKeyPemPrivateKey(
                "-----BEGIN PRIVATE KEY-----\nplain-pkcs8\n",
            ),
        )
    }

    @Test
    fun runtime_pem_detection_covers_imported_families_and_pkcs8() {
        assertTrue(
            PrivateKeyMaterialFormat.looksLikeRuntimePemPrivateKey(
                "-----BEGIN EC PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n",
            ),
        )
        assertTrue(
            PrivateKeyMaterialFormat.looksLikeRuntimePemPrivateKey(
                "-----BEGIN DSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n",
            ),
        )
        assertTrue(
            PrivateKeyMaterialFormat.looksLikeRuntimePemPrivateKey(
                "-----BEGIN PRIVATE KEY-----\npkcs8\n",
            ),
        )
    }

    @Test
    fun encrypted_private_key_detection_recognizes_proc_type_and_openssh_markers() {
        assertTrue(
            PrivateKeyMaterialFormat.looksLikeEncryptedPrivateKey(
                "-----BEGIN EC PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,1234\n",
            ),
        )
        assertTrue(
            PrivateKeyMaterialFormat.looksLikeEncryptedPrivateKey(
                "-----BEGIN OPENSSH PRIVATE KEY-----\nopenssh\n",
            ),
        )
    }
}
