package io.github.jtsang4.aterm.tools.sshfixture

import java.nio.file.Files
import java.time.Duration
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.future.ConnectFuture
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SshFixtureServerTest {
    @Test
    fun fixture_accepts_password_and_public_key_authentication() {
        val runtimeDir = Files.createTempDirectory("ssh-fixture-server")
        val hostKey = TestKeyMaterial.generate("host@test")
        val clientKey = TestKeyMaterial.generate("client@test")
        val config = SshFixtureConfig(runtimeDir = runtimeDir, port = reservePort())
        val prepared = config.prepareRuntime(
            hostKeyMaterial = hostKey,
            clientKeyMaterial = clientKey,
            authorizedClientPublicKey = clientKey.publicKey,
        )
        val restartedPrepared = config.prepareRuntime(
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
                session.addPasswordIdentity(prepared.password)
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

    @Test
    fun fixture_shell_allocates_real_pty_for_interactive_sessions() {
        val runtimeDir = Files.createTempDirectory("ssh-fixture-pty")
        val hostKey = TestKeyMaterial.generate("host@test")
        val clientKey = TestKeyMaterial.generate("client@test")
        val prepared = SshFixtureConfig(runtimeDir = runtimeDir, port = reservePort()).prepareRuntime(
            hostKeyMaterial = hostKey,
            clientKeyMaterial = clientKey,
            authorizedClientPublicKey = clientKey.publicKey,
        )

        SshFixtureServer(prepared).start().use {
            val output = connectAndRunInteractiveShell(
                host = prepared.metadata.host,
                port = prepared.metadata.port,
                username = prepared.metadata.username,
                password = prepared.password,
                command = "stty size; printf 'TTY:%s\\n' \"$(tty)\"; printf '\\u001B[?1049hALT\\u001B[?1049l' \n",
            )

            assertTrue(output, output.contains("TTY:/dev/pts/"))
            assertTrue(output, output.contains("24 80"))
            assertTrue(output, output.contains("ALT"))
        }
    }

    @Test
    fun fixture_ctrl_c_interrupts_foreground_command_without_leaking_delayed_output() {
        val runtimeDir = Files.createTempDirectory("ssh-fixture-ctrlc")
        val hostKey = TestKeyMaterial.generate("host@test")
        val clientKey = TestKeyMaterial.generate("client@test")
        val prepared = SshFixtureConfig(runtimeDir = runtimeDir, port = reservePort()).prepareRuntime(
            hostKeyMaterial = hostKey,
            clientKeyMaterial = clientKey,
            authorizedClientPublicKey = clientKey.publicKey,
        )

        SshFixtureServer(prepared).start().use {
            val output = connectAndRunInteractiveShell(
                host = prepared.metadata.host,
                port = prepared.metadata.port,
                username = prepared.metadata.username,
                password = prepared.password,
                command = "cat >/dev/null\n",
                extraInput = "\u0003printf 'CTRL_C_RECOVERED\\n'\n",
                extraInputDelayMillis = 800,
            )

            assertTrue(output, output.containsOutputLine("CTRL_C_RECOVERED"))
        }
    }

    @Test
    fun fixture_timeout_username_stalls_auth_longer_than_client_connect_timeout() {
        val runtimeDir = Files.createTempDirectory("ssh-fixture-timeout")
        val hostKey = TestKeyMaterial.generate("host@test")
        val clientKey = TestKeyMaterial.generate("client@test")
        val prepared = SshFixtureConfig(
            runtimeDir = runtimeDir,
            port = reservePort(),
            timeoutStallMillis = 6_000L,
        ).prepareRuntime(
            hostKeyMaterial = hostKey,
            clientKeyMaterial = clientKey,
            authorizedClientPublicKey = clientKey.publicKey,
        )

        SshFixtureServer(prepared).start().use {
            val client = SshClient.setUpDefaultClient().apply {
                serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
                start()
            }
            try {
                val connectFuture: ConnectFuture = client.connect(
                    prepared.metadata.timeoutUsername,
                    prepared.metadata.host,
                    prepared.metadata.port,
                )
                val session = connectFuture.verify(Duration.ofSeconds(5)).session
                session.use {
                    session.addPasswordIdentity(prepared.password)
                    val authFuture = session.auth()
                    assertFalse(authFuture.await(Duration.ofSeconds(1)))
                    val startNanos = System.nanoTime()
                    authFuture.cancel()
                    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                    assertTrue("Cancel should return quickly after reproducer starts stalling", elapsedMillis < 2_000)
                }
            } finally {
                client.stop()
            }
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

    private fun connectAndRunInteractiveShell(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        extraInput: String = "",
        extraInputDelayMillis: Long = 0,
    ): String {
        val client = SshClient.setUpDefaultClient().apply {
            serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            start()
        }
        try {
            client.connect(username, host, port).verify(Duration.ofSeconds(5)).session.use { session ->
                session.addPasswordIdentity(password)
                session.auth().verify(Duration.ofSeconds(5))
                val output = StringBuilder()
                val channel = session.createShellChannel().apply {
                    setUsePty(true)
                    ptyType = "xterm-256color"
                    ptyColumns = 80
                    ptyLines = 24
                    ptyWidth = 720
                    ptyHeight = 432
                    setRedirectErrorStream(true)
                    open().verify(Duration.ofSeconds(5))
                }
                channel.use { shell ->
                    val reader = startReader(shell, output)
                    shell.invertedIn.use { stdin ->
                        stdin.write(command.toByteArray())
                        stdin.flush()
                        if (extraInput.isNotEmpty()) {
                            Thread.sleep(extraInputDelayMillis)
                            stdin.write(extraInput.toByteArray())
                            stdin.flush()
                        }
                    }
                    shell.waitFor(setOf(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), Duration.ofSeconds(5))
                    reader.join(Duration.ofSeconds(5).toMillis())
                }
                return output.toString()
            }
        } finally {
            client.stop()
        }
    }

    private fun startReader(
        channel: ChannelShell,
        sink: StringBuilder,
    ): Thread = Thread {
        val buffer = ByteArray(1024)
        while (true) {
            val read = channel.invertedOut.read(buffer)
            if (read <= 0) {
                break
            }
            synchronized(sink) {
                sink.append(String(buffer, 0, read))
            }
        }
    }.apply { start() }

    private fun reservePort(): Int = ServerSocket(0).use { socket ->
        socket.localPort
    }
}

private fun String.containsOutputLine(value: String): Boolean =
    lineSequence()
        .map { it.replace(Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]"""), "").trim() }
        .any { it == value }
