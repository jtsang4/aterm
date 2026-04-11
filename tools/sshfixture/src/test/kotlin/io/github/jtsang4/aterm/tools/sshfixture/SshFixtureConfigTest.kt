package io.github.jtsang4.aterm.tools.sshfixture

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshFixtureConfigTest {
    @Test
    fun runtimeFiles_are_written_with_password_authorized_key_and_stable_host_key() {
        val runtimeDir = Files.createTempDirectory("ssh-fixture-runtime")

        val hostKey = TestKeyMaterial.generate("host@test")
        val clientKey = TestKeyMaterial.generate("client@test")
        val config = SshFixtureConfig(runtimeDir = runtimeDir)
        val prepared = config.prepareRuntime(
            hostKeyMaterial = hostKey,
            clientKeyMaterial = clientKey,
            authorizedClientPublicKey = clientKey.publicKey,
        )

        assertTrue(Files.exists(prepared.runtimeDir.resolve("host_key")))
        assertTrue(Files.exists(prepared.runtimeDir.resolve("host_key.pub")))
        assertTrue(Files.exists(prepared.runtimeDir.resolve("client_key")))
        assertTrue(Files.exists(prepared.runtimeDir.resolve("client_key.pub")))
        assertTrue(Files.exists(prepared.runtimeDir.resolve("authorized_keys")))
        assertTrue(Files.exists(prepared.runtimeDir.resolve("fixture-metadata.env")))
        assertEquals(config.password, prepared.metadata.password)
        assertEquals(config.username, prepared.metadata.username)
        assertEquals(config.port, prepared.metadata.port)
        assertTrue(prepared.authorizedKeysPath.readText().contains(clientKey.publicKey))
        assertEquals(hostKey.privateKey.trim(), prepared.hostKeyPath.readText().trim())
        assertTrue(prepared.metadata.hostPublicKey.startsWith("ssh-"))
        assertTrue(prepared.metadata.hostFingerprint.startsWith("SHA256:"))
        assertNotEquals(hostKey.publicKey.trim(), clientKey.publicKey.trim())
    }

    @Test
    fun loadOrCreateKeyMaterial_reuses_existing_runtime_key_material_for_stable_fingerprint() {
        val runtimeDir = Files.createTempDirectory("ssh-fixture-runtime")
        val privateKeyPath = runtimeDir.resolve("host_key")
        val publicKeyPath = runtimeDir.resolve("host_key.pub")

        val original = loadOrCreateKeyMaterial(
            privateKeyPath = privateKeyPath,
            publicKeyPath = publicKeyPath,
            comment = "stable@test",
        )
        assertTrue(privateKeyPath.exists())
        assertTrue(publicKeyPath.exists())
        privateKeyPath.toFile().writeText(original.privateKey)
        publicKeyPath.toFile().writeText(original.publicKey)

        val reloaded = loadOrCreateKeyMaterial(
            privateKeyPath = privateKeyPath,
            publicKeyPath = publicKeyPath,
            comment = "stable@test",
        )

        assertEquals(original.publicKey.trim(), reloaded.publicKey.trim())
        assertEquals(original.fingerprint, reloaded.fingerprint)
    }

    @Test
    fun metadata_file_uses_shell_safe_values_for_service_exports() {
        val runtimeDir = Files.createTempDirectory("ssh-fixture-runtime")
        val hostKey = TestKeyMaterial.generate("host@test")
        val clientKey = TestKeyMaterial.generate("client@test")
        val prepared = SshFixtureConfig(runtimeDir = runtimeDir).prepareRuntime(
            hostKeyMaterial = hostKey,
            clientKeyMaterial = clientKey,
            authorizedClientPublicKey = clientKey.publicKey,
        )

        val envFile = prepared.runtimeDir.resolve("fixture-metadata.env").readText()

        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_RUNTIME_DIR="))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_HOST=127.0.0.1"))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_EMULATOR_HOST=10.0.2.2"))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_PORT=3122"))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_USERNAME=atermtester"))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_PASSWORD=aterm-password-fixture"))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_HOST_KEY_PATH="))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_HOST_FINGERPRINT="))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_CLIENT_PUBLIC_KEY="))
        assertTrue(envFile.contains("ATERM_SSH_FIXTURE_CLIENT_PRIVATE_KEY_PATH="))
    }
}
