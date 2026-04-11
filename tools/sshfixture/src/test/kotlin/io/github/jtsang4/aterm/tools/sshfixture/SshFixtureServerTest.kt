package io.github.jtsang4.aterm.tools.sshfixture

import java.nio.file.Files
import java.time.Duration
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.junit.Assert.assertTrue
import org.junit.Test

class SshFixtureServerTest {
    @Test
    fun fixture_accepts_password_and_public_key_authentication() {
        val runtimeDir = Files.createTempDirectory("ssh-fixture-server")
        val hostKey = TestKeyMaterial.generate("host@test")
        val clientKey = TestKeyMaterial.generate("client@test")
        val prepared = SshFixtureConfig(runtimeDir = runtimeDir).prepareRuntime(
            hostKeyMaterial = hostKey,
            clientKeyMaterial = clientKey,
            authorizedClientPublicKey = clientKey.publicKey,
        )
        val restartedPrepared = SshFixtureConfig(runtimeDir = runtimeDir).prepareRuntime(
            hostKeyMaterial = TestKeyMaterial.generate("host@test"),
            clientKeyMaterial = clientKey,
            authorizedClientPublicKey = clientKey.publicKey,
        )

        assertTrue(prepared.hostKeyPath.toFile().readText().contains("BEGIN OPENSSH PRIVATE KEY"))
        assertTrue(restartedPrepared.metadata.clientPublicKey.contains("ssh-rsa"))

        SshFixtureServer(prepared).start().use {
            val passwordOutput = connectAndRun(
                host = prepared.metadata.host,
                port = prepared.metadata.port,
                username = prepared.metadata.username,
            ) { session ->
                session.addPasswordIdentity(prepared.metadata.password)
            }
            assertTrue(passwordOutput.contains("atermtester"))

            val keyOutput = connectAndRun(
                host = prepared.metadata.host,
                port = prepared.metadata.port,
                username = prepared.metadata.username,
            ) { session ->
                val key = org.apache.sshd.common.keyprovider.FileKeyPairProvider(prepared.clientKeyPath)
                    .loadKeys(null)
                    .first()
                session.addPublicKeyIdentity(key)
            }
            assertTrue(keyOutput.contains("atermtester"))
        }
    }

    private fun connectAndRun(
        host: String,
        port: Int,
        username: String,
        auth: (org.apache.sshd.client.session.ClientSession) -> Unit,
    ): String {
        val client = SshClient.setUpDefaultClient().apply {
            serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            start()
        }
        try {
            client.connect(username, host, port).verify(Duration.ofSeconds(5)).session.use { session ->
                auth(session)
                session.auth().verify(Duration.ofSeconds(5))
                return session.executeRemoteCommand("printf '%s:%s' \"\$USER\" \"\$SSH_CONNECTION\"")
            }
        } finally {
            client.stop()
        }
    }
}
