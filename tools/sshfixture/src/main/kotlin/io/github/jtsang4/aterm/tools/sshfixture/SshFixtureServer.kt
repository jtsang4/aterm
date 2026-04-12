package io.github.jtsang4.aterm.tools.sshfixture

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.channel.ChannelSessionAware
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.command.Command

class SshFixtureServer(
    private val preparedFixture: PreparedFixture,
) : AutoCloseable {
    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = preparedFixture.metadata.host
        port = preparedFixture.metadata.port
        keyPairProvider = KeyPairProvider.wrap(loadHostKey(preparedFixture.hostKeyPath))
        passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            if (username == preparedFixture.metadata.timeoutUsername) {
                Thread.sleep(preparedFixture.metadata.timeoutStallMillis)
                return@PasswordAuthenticator false
            }
            username == preparedFixture.metadata.username &&
                password == preparedFixture.password
        }
        publickeyAuthenticator = buildPublicKeyAuthenticator(preparedFixture.authorizedKeysPath)
        commandFactory = ProcessShellCommandFactory.INSTANCE
        shellFactory = PtyBackedShellFactory()
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

    private inner class PtyBackedShellFactory : org.apache.sshd.server.shell.ShellFactory {
        override fun createShell(channel: ChannelSession): Command = PtyShellCommand()
    }

    private inner class PtyShellCommand : Command, ChannelSessionAware, SignalListener {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var error: OutputStream? = null
        private var exitCallback: ExitCallback? = null
        private var channelSession: ChannelSession? = null
        private var process: Process? = null
        private var sizeFile: Path? = null
        private val running = AtomicBoolean(false)

        override fun setInputStream(inputStream: InputStream) {
            input = inputStream
        }

        override fun setOutputStream(outputStream: OutputStream) {
            output = outputStream
        }

        override fun setErrorStream(errorStream: OutputStream) {
            error = errorStream
        }

        override fun setExitCallback(callback: ExitCallback) {
            exitCallback = callback
        }

        override fun setChannelSession(channelSession: ChannelSession) {
            this.channelSession = channelSession
        }

        override fun start(channel: ChannelSession, env: Environment) {
            val environment = HashMap(env.getEnv())
            environment["TERM"] = environment["TERM"] ?: DEFAULT_TERM
            environment["HOME"] = environment["HOME"] ?: preparedFixture.runtimeDir.toString()
            environment["USER"] = environment["USER"] ?: preparedFixture.metadata.username
            environment["SHELL"] = BASH_BINARY
            val initialColumns = terminalColumns(env)
            val initialRows = terminalRows(env)
            val createdSizeFile = Files.createTempFile(preparedFixture.runtimeDir, "pty-size-", ".txt")
            sizeFile = createdSizeFile
            writeTerminalSize(createdSizeFile, initialColumns, initialRows)
            val processBuilder = ProcessBuilder(
                "python3",
                "-u",
                "-c",
                PYTHON_PTY_BRIDGE,
                createdSizeFile.toString(),
            )
            processBuilder.environment().putAll(environment)
            processBuilder.redirectErrorStream(true)
            val startedProcess = processBuilder.start()
            process = startedProcess
            running.set(true)
            env.addSignalListener(this)

            pump(input, startedProcess.outputStream, closeOutput = true)
            pump(startedProcess.inputStream, output, closeOutput = false)
            waitForExit(startedProcess)
        }

        override fun signal(channel: org.apache.sshd.common.channel.Channel, signal: org.apache.sshd.server.Signal) {
            val current = process ?: return
            if (signal != org.apache.sshd.server.Signal.WINCH) {
                return
            }
            val environment = channelSession?.environment ?: return
            val latestSizeFile = sizeFile ?: return
            writeTerminalSize(
                latestSizeFile,
                terminalColumns(environment),
                terminalRows(environment),
            )
            runCatching {
                ProcessBuilder("kill", "-WINCH", current.pid().toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }

        override fun destroy(channel: ChannelSession) {
            val current = process ?: return
            running.set(false)
            runCatching { current.destroy() }
            sizeFile?.let { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }

        private fun pump(source: InputStream?, sink: OutputStream?, closeOutput: Boolean) {
            if (source == null || sink == null) return
            Thread {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                try {
                    while (running.get()) {
                        val read = source.read(buffer)
                        if (read < 0) {
                            break
                        }
                        if (read == 0) {
                            continue
                        }
                        sink.write(buffer, 0, read)
                        sink.flush()
                    }
                } catch (_: IOException) {
                    // Shell teardown closes streams asynchronously.
                } finally {
                    if (closeOutput) {
                        runCatching { sink.close() }
                    } else {
                        runCatching { sink.flush() }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun waitForExit(process: Process) {
            Thread {
                val exitCode = try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    130
                } finally {
                    running.set(false)
                    sizeFile?.let { path ->
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
                exitCallback?.onExit(exitCode)
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun terminalColumns(environment: Environment): Int =
        environment.getEnv()[Environment.ENV_COLUMNS]?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_COLUMNS

    private fun terminalRows(environment: Environment): Int =
        environment.getEnv()[Environment.ENV_LINES]?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_ROWS

    private fun writeTerminalSize(path: Path, columns: Int, rows: Int) {
        Files.writeString(
            path,
            "${columns.coerceAtLeast(1)} ${rows.coerceAtLeast(1)}\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private companion object {
        private const val BASH_BINARY = "/bin/bash"
        private const val DEFAULT_TERM = "xterm-256color"
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private val PYTHON_PTY_BRIDGE = """
            import fcntl
            import os
            import pty
            import selectors
            import signal
            import struct
            import sys
            import termios
            
            size_path = sys.argv[1]
            
            def read_size():
                try:
                    with open(size_path, "r", encoding="utf-8") as handle:
                        raw = handle.read().strip().split()
                    cols = int(raw[0])
                    rows = int(raw[1])
                    return max(cols, 1), max(rows, 1)
                except Exception:
                    return 80, 24
            
            child_pid, master_fd = pty.fork()
            if child_pid == 0:
                os.environ.setdefault("TERM", "xterm-256color")
                attrs = termios.tcgetattr(0)
                attrs[3] |= termios.ECHO | termios.ICANON | termios.ISIG
                if hasattr(termios, "ECHOCTL"):
                    attrs[3] |= termios.ECHOCTL
                termios.tcsetattr(0, termios.TCSANOW, attrs)
                os.execv("/bin/bash", ["/bin/bash", "--noprofile", "--norc", "-i"])
            
            def apply_size():
                cols, rows = read_size()
                fcntl.ioctl(master_fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
            
            def on_winch(signum, frame):
                apply_size()
            
            def on_term(signum, frame):
                try:
                    os.kill(child_pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass

            def interrupt_foreground_process():
                target_pgid = None
                try:
                    target_pgid = os.tcgetpgrp(master_fd)
                except OSError:
                    target_pgid = None
                if target_pgid is None or target_pgid <= 0:
                    try:
                        target_pgid = os.getpgid(child_pid)
                    except ProcessLookupError:
                        target_pgid = None
                if target_pgid is None or target_pgid <= 0:
                    return
                try:
                    os.killpg(target_pgid, signal.SIGINT)
                except ProcessLookupError:
                    pass
            
            apply_size()
            signal.signal(signal.SIGWINCH, on_winch)
            signal.signal(signal.SIGTERM, on_term)
            
            selector = selectors.DefaultSelector()
            selector.register(sys.stdin.buffer, selectors.EVENT_READ, "stdin")
            selector.register(master_fd, selectors.EVENT_READ, "pty")
            
            stdin_open = True
            while True:
                for key, _ in selector.select():
                    if key.data == "stdin":
                        data = os.read(sys.stdin.fileno(), 4096)
                        if not data:
                            if stdin_open:
                                selector.unregister(sys.stdin.buffer)
                                stdin_open = False
                            continue
                        start = 0
                        while True:
                            interrupt_at = data.find(b"\x03", start)
                            if interrupt_at < 0:
                                chunk = data[start:]
                                if chunk:
                                    os.write(master_fd, chunk)
                                break
                            chunk = data[start:interrupt_at]
                            if chunk:
                                os.write(master_fd, chunk)
                            try:
                                interrupt_foreground_process()
                            except Exception:
                                pass
                            os.write(sys.stdout.fileno(), b"^C\r\n")
                            sys.stdout.flush()
                            start = interrupt_at + 1
                    else:
                        try:
                            data = os.read(master_fd, 4096)
                        except OSError:
                            data = b""
                        if not data:
                            _, status = os.waitpid(child_pid, 0)
                            if os.WIFEXITED(status):
                                sys.exit(os.WEXITSTATUS(status))
                            if os.WIFSIGNALED(status):
                                sys.exit(128 + os.WTERMSIG(status))
                            sys.exit(1)
                        os.write(sys.stdout.fileno(), data)
                        sys.stdout.flush()
        """.trimIndent()
    }
}
