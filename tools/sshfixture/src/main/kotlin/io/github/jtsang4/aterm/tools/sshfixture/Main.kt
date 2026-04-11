package io.github.jtsang4.aterm.tools.sshfixture

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.createDirectories

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "start"
    val runtimeDir = resolveRuntimeDir()
    runtimeDir.createDirectories()

    when (command) {
        "prepare" -> {
            val prepared = prepareFixture(runtimeDir)
            println("Prepared SSH fixture runtime at ${prepared.runtimeDir}")
            println("Metadata exported to ${prepared.metadataPath}")
        }

        "metadata" -> {
            val prepared = prepareFixture(runtimeDir)
            print(prepared.metadata.toEnvFile())
        }

        "start" -> {
            val prepared = prepareFixture(runtimeDir)
            SshFixtureServer(prepared).start().use {
                println("ATERM_SSH_FIXTURE_READY ${prepared.metadata.host}:${prepared.metadata.port}")
                println("ATERM_SSH_FIXTURE_HOST_FINGERPRINT ${prepared.metadata.hostFingerprint}")
                println("ATERM_SSH_FIXTURE_CLIENT_PRIVATE_KEY_PATH ${prepared.metadata.clientPrivateKeyPath}")
                while (true) {
                    Thread.sleep(1_000)
                }
            }
        }

        else -> error("Unknown command: $command")
    }
}

private fun resolveRuntimeDir(): Path {
    val configuredRuntimeDir = System.getenv("ATERM_SSH_FIXTURE_RUNTIME_DIR")
    if (!configuredRuntimeDir.isNullOrBlank()) {
        return Path.of(configuredRuntimeDir).toAbsolutePath().normalize()
    }

    val workingDirectory = Path.of("").toAbsolutePath().normalize()
    val repoRoot = generateSequence(workingDirectory) { current -> current.parent }
        .firstOrNull { candidate ->
            candidate.resolve("settings.gradle.kts").exists() ||
                candidate.resolve("settings.gradle").exists()
        }
        ?: workingDirectory

    return repoRoot.resolve("tools/sshfixture/runtime").normalize()
}

private fun prepareFixture(runtimeDir: Path): PreparedFixture {
    val config = SshFixtureConfig(runtimeDir = runtimeDir)
    val hostKey = loadOrCreateKeyMaterial(
        privateKeyPath = runtimeDir.resolve("host_key"),
        publicKeyPath = runtimeDir.resolve("host_key.pub"),
        comment = "aterm-fixture-host",
    )
    val clientKey = loadOrCreateKeyMaterial(
        privateKeyPath = runtimeDir.resolve("client_key"),
        publicKeyPath = runtimeDir.resolve("client_key.pub"),
        comment = "aterm-fixture-client",
    )
    return config.prepareRuntime(
        hostKeyMaterial = hostKey,
        clientKeyMaterial = clientKey,
        authorizedClientPublicKey = clientKey.publicKey,
    )
}
