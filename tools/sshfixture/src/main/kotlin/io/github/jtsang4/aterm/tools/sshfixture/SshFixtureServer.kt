package io.github.jtsang4.aterm.tools.sshfixture

import java.io.IOException
import java.nio.file.Path
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.shell.ProcessShellFactory

class SshFixtureServer(
    private val preparedFixture: PreparedFixture,
) : AutoCloseable {
    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = preparedFixture.metadata.host
        port = preparedFixture.metadata.port
        keyPairProvider = KeyPairProvider.wrap(loadHostKey(preparedFixture.hostKeyPath))
        passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            username == preparedFixture.metadata.username &&
                password == preparedFixture.metadata.password
        }
        publickeyAuthenticator = buildPublicKeyAuthenticator(preparedFixture.authorizedKeysPath)
        commandFactory = ProcessShellCommandFactory.INSTANCE
        shellFactory = ProcessShellFactory("/bin/sh", "-i")
    }
    private val started = AtomicBoolean(false)

    fun start(): SshFixtureServer {
        if (started.compareAndSet(false, true)) {
            server.start()
        }
        return this
    }

    override fun close() {
        if (started.compareAndSet(true, false)) {
            runCatching { server.stop() }
        }
    }

    private fun loadHostKey(path: Path) =
        org.apache.sshd.common.keyprovider.FileKeyPairProvider(path).loadKeys(null).first()

    private fun buildPublicKeyAuthenticator(authorizedKeysPath: Path): PublickeyAuthenticator {
        val allowed = AuthorizedKeyEntry.readAuthorizedKeys(authorizedKeysPath)
            .map { entry -> entry.resolvePublicKey(null, PublicKeyEntryResolver.FAILING) }
        return PublickeyAuthenticator { username, key, _ ->
            username == preparedFixture.metadata.username &&
                allowed.any { existing -> existing.isSameKey(key) }
        }
    }

    private fun PublicKey.isSameKey(other: PublicKey): Boolean =
        encoded.contentEquals(other.encoded)
}
