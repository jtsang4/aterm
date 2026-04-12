package io.github.jtsang4.aterm.tools.sshfixture

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyPair
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils

const val SSH_FIXTURE_DISCONNECT_COMMAND = "aterm-fixture-disconnect"

data class SshFixtureConfig(
    val runtimeDir: Path,
    val host: String = "127.0.0.1",
    val port: Int = 3122,
    val username: String = "atermtester",
    val password: String = "aterm-password-fixture",
    val timeoutUsername: String = "atermtimeout",
    val timeoutStallMillis: Long = 25_000L,
)

data class PreparedFixture(
    val runtimeDir: Path,
    val hostKeyPath: Path,
    val hostPublicKeyPath: Path,
    val clientKeyPath: Path,
    val clientPublicKeyPath: Path,
    val authorizedKeysPath: Path,
    val secretEnvPath: Path,
    val metadataPath: Path,
    val disconnectScriptPath: Path,
    val password: String,
    val metadata: SshFixtureMetadata,
)

data class SshFixtureMetadata(
    val runtimeDir: String,
    val host: String,
    val port: Int,
    val username: String,
    val timeoutUsername: String,
    val timeoutPhase: String,
    val timeoutStallMillis: Long,
    val passwordEnvName: String,
    val secretEnvPath: String,
    val disconnectCommand: String,
    val hostPublicKey: String,
    val hostFingerprint: String,
    val hostKeyPath: String,
    val clientPublicKey: String,
    val clientPrivateKeyPath: String,
    val clientPublicKeyPath: String,
) {
    fun toEnvFile(): String = buildString {
        appendLine("ATERM_SSH_FIXTURE_RUNTIME_DIR=${shellEscape(runtimeDir)}")
        appendLine("ATERM_SSH_FIXTURE_HOST=$host")
        appendLine("ATERM_SSH_FIXTURE_EMULATOR_HOST=10.0.2.2")
        appendLine("ATERM_SSH_FIXTURE_PORT=$port")
        appendLine("ATERM_SSH_FIXTURE_ENDPOINT=$host:$port")
        appendLine("ATERM_SSH_FIXTURE_EMULATOR_ENDPOINT=10.0.2.2:$port")
        appendLine("ATERM_SSH_FIXTURE_USERNAME=$username")
        appendLine("ATERM_SSH_FIXTURE_TIMEOUT_USERNAME=$timeoutUsername")
        appendLine("ATERM_SSH_FIXTURE_TIMEOUT_PHASE=$timeoutPhase")
        appendLine("ATERM_SSH_FIXTURE_TIMEOUT_STALL_MILLIS=$timeoutStallMillis")
        appendLine("ATERM_SSH_FIXTURE_PASSWORD_ENV=$passwordEnvName")
        appendLine("ATERM_SSH_FIXTURE_SECRET_ENV_PATH=${shellEscape(secretEnvPath)}")
        appendLine("ATERM_SSH_FIXTURE_DISCONNECT_COMMAND=$disconnectCommand")
        appendLine("ATERM_SSH_FIXTURE_HOST_KEY_PATH=${shellEscape(hostKeyPath)}")
        appendLine("ATERM_SSH_FIXTURE_HOST_PUBLIC_KEY=${shellEscape(hostPublicKey)}")
        appendLine("ATERM_SSH_FIXTURE_HOST_FINGERPRINT=$hostFingerprint")
        appendLine("ATERM_SSH_FIXTURE_CLIENT_PUBLIC_KEY=${shellEscape(clientPublicKey)}")
        appendLine("ATERM_SSH_FIXTURE_CLIENT_PRIVATE_KEY_PATH=${shellEscape(clientPrivateKeyPath)}")
        appendLine("ATERM_SSH_FIXTURE_CLIENT_PUBLIC_KEY_PATH=${shellEscape(clientPublicKeyPath)}")
    }
}

fun SshFixtureConfig.prepareRuntime(
    hostKeyMaterial: TestKeyMaterial,
    clientKeyMaterial: TestKeyMaterial,
    authorizedClientPublicKey: String,
): PreparedFixture {
    Files.createDirectories(runtimeDir)
    val hostKeyPath = runtimeDir.resolve("host_key")
    val hostPublicKeyPath = runtimeDir.resolve("host_key.pub")
    val clientKeyPath = runtimeDir.resolve("client_key")
    val clientPublicKeyPath = runtimeDir.resolve("client_key.pub")
    val authorizedKeysPath = runtimeDir.resolve("authorized_keys")
    val secretEnvPath = runtimeDir.resolve("fixture-secrets.env")
    val metadataPath = runtimeDir.resolve("fixture-metadata.env")
    val disconnectScriptPath = runtimeDir.resolve(SSH_FIXTURE_DISCONNECT_COMMAND)
    val passwordEnvName = "ATERM_SSH_FIXTURE_PASSWORD"

    hostKeyPath.writeSecureText(hostKeyMaterial.privateKey.trim() + "\n")
    hostPublicKeyPath.writeSecureText(hostKeyMaterial.publicKey.trim() + "\n")
    clientKeyPath.writeSecureText(clientKeyMaterial.privateKey.trim() + "\n")
    clientPublicKeyPath.writeSecureText(clientKeyMaterial.publicKey.trim() + "\n")
    authorizedKeysPath.writeSecureText(authorizedClientPublicKey.trim() + "\n")
    secretEnvPath.writeSecureText("$passwordEnvName=${shellEscape(password)}\n")
    disconnectScriptPath.writeSecureText(
        """
        #!/bin/sh
        kill -KILL "${'$'}PPID"
        """.trimIndent() + "\n",
    )
    runCatching {
        Files.setPosixFilePermissions(
            disconnectScriptPath,
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }

    val metadata = SshFixtureMetadata(
        runtimeDir = runtimeDir.toString(),
        host = host,
        port = port,
        username = username,
        timeoutUsername = timeoutUsername,
        timeoutPhase = "password-auth",
        timeoutStallMillis = timeoutStallMillis,
        passwordEnvName = passwordEnvName,
        secretEnvPath = secretEnvPath.toString(),
        disconnectCommand = SSH_FIXTURE_DISCONNECT_COMMAND,
        hostPublicKey = hostKeyMaterial.publicKey.trim(),
        hostFingerprint = hostKeyMaterial.fingerprint,
        hostKeyPath = hostKeyPath.toString(),
        clientPublicKey = authorizedClientPublicKey.trim(),
        clientPrivateKeyPath = clientKeyPath.toString(),
        clientPublicKeyPath = clientPublicKeyPath.toString(),
    )
    metadataPath.writeSecureText(metadata.toEnvFile())

    return PreparedFixture(
        runtimeDir = runtimeDir,
        hostKeyPath = hostKeyPath,
        hostPublicKeyPath = hostPublicKeyPath,
        clientKeyPath = clientKeyPath,
        clientPublicKeyPath = clientPublicKeyPath,
        authorizedKeysPath = authorizedKeysPath,
        secretEnvPath = secretEnvPath,
        metadataPath = metadataPath,
        disconnectScriptPath = disconnectScriptPath,
        password = password,
        metadata = metadata,
    )
}

data class TestKeyMaterial(
    val privateKey: String,
    val publicKey: String,
    val fingerprint: String,
) {
    companion object {
        fun generate(comment: String): TestKeyMaterial {
            val keyPair = KeyMaterialFactory.generateRsa()
            return fromKeyPair(keyPair, comment)
        }

        fun fromKeyPair(keyPair: KeyPair, comment: String): TestKeyMaterial {
            val privateKey = ByteArrayOutputStream().use { output ->
                OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, comment, null, output)
                output.toString(Charsets.UTF_8.name())
            }
            val publicKey = PublicKeyEntry.toString(keyPair.public).let { encoded ->
                if (encoded.endsWith(" $comment")) {
                    encoded
                } else {
                    "$encoded $comment"
                }
            }
            val fingerprint = Fingerprints.sha256(keyPair.public.encoded)
            return TestKeyMaterial(
                privateKey = privateKey,
                publicKey = publicKey,
                fingerprint = fingerprint,
            )
        }
    }
}

fun loadOrCreateKeyMaterial(
    privateKeyPath: Path,
    publicKeyPath: Path,
    comment: String,
): TestKeyMaterial {
    if (Files.exists(privateKeyPath) && Files.exists(publicKeyPath)) {
        val keyPair = Files.newInputStream(privateKeyPath).use { input ->
            SecurityUtils.loadKeyPairIdentities(
                null,
                NamedResource.ofName(privateKeyPath.fileName.toString()),
                input,
                FilePasswordProvider.EMPTY,
            ).first()
        }
        return TestKeyMaterial(
            privateKey = Files.readString(privateKeyPath),
            publicKey = Files.readString(publicKeyPath).trim(),
            fingerprint = Fingerprints.sha256(keyPair.public.encoded),
        )
    }
    return TestKeyMaterial.generate(comment).also { generated ->
        privateKeyPath.writeSecureText(generated.privateKey.trimEnd() + "\n")
        publicKeyPath.writeSecureText(generated.publicKey.trim() + "\n")
    }
}

private fun Path.writeSecureText(content: String) {
    Files.writeString(
        this,
        content,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    runCatching {
        val permissions = setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        )
        Files.setPosixFilePermissions(this, permissions)
    }
}

private fun shellEscape(value: String): String = buildString {
    append('\'')
    value.forEach { char ->
        if (char == '\'') {
            append("'\"'\"'")
        } else {
            append(char)
        }
    }
    append('\'')
}
