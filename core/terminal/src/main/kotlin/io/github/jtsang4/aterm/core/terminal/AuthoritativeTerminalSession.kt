package io.github.jtsang4.aterm.core.terminal

import android.graphics.Canvas
import android.graphics.Typeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import kotlin.math.roundToInt
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class AuthoritativeTerminalSession(
    initialViewport: TerminalViewport = DEFAULT_VIEWPORT,
) {
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val terminalOutput = UiTerminalOutput()
    private val emulatorClient = UiTerminalSessionClient()
    private var fontScale: Float = 1f
    private var renderer: TerminalRenderer = createRenderer(fontScale)

    private var viewport: TerminalViewport = normalizeViewport(initialViewport)
    private var emulator: TerminalEmulator? = null
    private var topRow: Int = 0

    init {
        terminalOutput.attachSession(this)
        emulatorClient.attachSession(this)
        emulator = TerminalEmulator(
            terminalOutput,
            viewport.columns,
            viewport.rows,
            DEFAULT_TRANSCRIPT_ROWS,
            emulatorClient,
        )
    }

    fun reset(newViewport: TerminalViewport = viewport) {
        viewport = normalizeViewport(newViewport)
        activeEmulator().reset()
        activeEmulator().resize(viewport.columns, viewport.rows)
        topRow = 0
        notifyContentChanged()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onTerminalSnapshotChanged(snapshot())
        listener.onTerminalTextChanged(completeText())
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun appendRemoteBytes(bytes: ByteArray, count: Int = bytes.size) {
        if (count <= 0) return
        activeEmulator().append(bytes, count)
        notifyContentChanged()
    }

    fun appendRemoteText(text: String) {
        if (text.isEmpty()) return
        appendRemoteBytes(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun resize(newViewport: TerminalViewport) {
        val normalized = normalizeViewport(newViewport)
        viewport = normalized
        activeEmulator().resize(normalized.columns, normalized.rows)
        topRow = topRow.coerceIn(minTopRow(), 0)
        notifyContentChanged()
    }

    fun updateFontScale(scale: Float) {
        val normalized = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        if (normalized == fontScale) {
            return
        }
        fontScale = normalized
        renderer = createRenderer(fontScale)
        notifyContentChanged()
    }

    fun scrollPageUp() {
        topRow = (topRow - viewport.rows).coerceAtLeast(minTopRow())
        notifyContentChanged()
    }

    fun scrollPageDown() {
        topRow = (topRow + viewport.rows).coerceAtMost(0)
        notifyContentChanged()
    }

    fun jumpToBottom() {
        if (topRow == 0) return
        topRow = 0
        notifyContentChanged()
    }

    fun snapshot(): TerminalSnapshot {
        val emulator = activeEmulator()
        val screen = emulator.screen
        val clampedTopRow = topRow.coerceIn(minTopRow(), 0)
        val bottomRowExclusive = clampedTopRow + viewport.rows
        return TerminalSnapshot(
            columns = viewport.columns,
            rows = viewport.rows,
            scrollbackLines = screen.getSelectedText(
                0,
                -screen.activeTranscriptRows,
                viewport.columns,
                0,
                true,
                false,
            ).splitToTerminalLines(),
            visibleLines = screen.getSelectedText(
                0,
                clampedTopRow,
                viewport.columns,
                bottomRowExclusive,
                true,
                false,
            ).splitToTerminalLines(minSize = viewport.rows),
            isAtLiveBottom = clampedTopRow == 0,
            alternateScreenActive = emulator.isAlternateBufferActive,
        )
    }

    fun completeText(): String = snapshot().completeText

    fun renderInto(canvas: Canvas) {
        val emulator = activeEmulator()
        renderer.render(
            emulator,
            canvas,
            topRow.coerceIn(minTopRow(), 0),
            -1,
            -1,
            -1,
            -1,
        )
    }

    fun renderTopRow(): Int {
        val emulator = activeEmulator()
        return -emulator.screen.activeTranscriptRows
    }

    fun rendererMetrics(): RendererMetrics = RendererMetrics(
        cellWidthPx = renderer.fontWidth,
        cellHeightPx = renderer.fontLineSpacing,
    )

    fun currentFontScale(): Float = fontScale

    private fun notifyContentChanged() {
        if (emulator == null) return
        val snapshot = snapshot()
        val text = completeText()
        listeners.forEach { listener ->
            listener.onTerminalSnapshotChanged(snapshot)
            listener.onTerminalTextChanged(text)
        }
    }

    interface Listener {
        fun onTerminalSnapshotChanged(snapshot: TerminalSnapshot)
        fun onTerminalTextChanged(text: String)
    }

    private fun normalizeViewport(viewport: TerminalViewport): TerminalViewport = TerminalViewport(
        columns = viewport.columns.coerceAtLeast(2),
        rows = viewport.rows.coerceAtLeast(2),
        widthPx = viewport.widthPx.coerceAtLeast(viewport.columns.coerceAtLeast(2)),
        heightPx = viewport.heightPx.coerceAtLeast(viewport.rows.coerceAtLeast(2)),
    )

    private fun activeEmulator(): TerminalEmulator = checkNotNull(emulator)

    private fun minTopRow(): Int = -activeEmulator().screen.activeTranscriptRows

    private inner class UiTerminalOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            if (count <= 0) return
            appendRemoteBytes(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit

        override fun onCopyTextToClipboard(text: String?) = Unit

        override fun onPasteTextFromClipboard() = Unit

        override fun onBell() = Unit

        override fun onColorsChanged() = notifyContentChanged()

        fun attachSession(@Suppress("UNUSED_PARAMETER") session: AuthoritativeTerminalSession) = Unit
    }

    private inner class UiTerminalSessionClient : TerminalSessionClient {
        fun attachSession(@Suppress("UNUSED_PARAMETER") session: AuthoritativeTerminalSession) = Unit

        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) = notifyContentChanged()

        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) = notifyContentChanged()

        override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) = notifyContentChanged()

        override fun onCopyTextToClipboard(
            session: com.termux.terminal.TerminalSession,
            text: String,
        ) = Unit

        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) = Unit

        override fun onBell(session: com.termux.terminal.TerminalSession) = Unit

        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) = notifyContentChanged()

        override fun onTerminalCursorStateChange(state: Boolean) = notifyContentChanged()

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String, message: String) = Unit

        override fun logWarn(tag: String, message: String) = Unit

        override fun logInfo(tag: String, message: String) = Unit

        override fun logDebug(tag: String, message: String) = Unit

        override fun logVerbose(tag: String, message: String) = Unit

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit

        override fun logStackTrace(tag: String, e: Exception) = Unit
    }

    data class RendererMetrics(
        val cellWidthPx: Float,
        val cellHeightPx: Int,
    )

    companion object {
        private const val DEFAULT_TEXT_SIZE_SP = 28
        private const val MIN_FONT_SCALE = 0.75f
        private const val MAX_FONT_SCALE = 2f
        private const val DEFAULT_TRANSCRIPT_ROWS = 2_000
        private val DEFAULT_VIEWPORT = TerminalViewport(columns = 80, rows = 24, widthPx = 720, heightPx = 432)

        private fun createRenderer(fontScale: Float): TerminalRenderer = TerminalRenderer(
            (DEFAULT_TEXT_SIZE_SP * fontScale).roundToInt().coerceAtLeast(1),
            Typeface.MONOSPACE,
        )
    }
}

private fun String.splitToTerminalLines(minSize: Int = 0): List<String> {
    val normalized = if (isBlank()) emptyList() else split('\n')
    if (minSize <= 0) {
        return normalized
    }
    return if (normalized.size >= minSize) normalized else normalized + List(minSize - normalized.size) { "" }
}
