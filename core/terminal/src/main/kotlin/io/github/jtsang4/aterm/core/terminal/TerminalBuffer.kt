package io.github.jtsang4.aterm.core.terminal

import kotlin.math.max
import kotlin.math.min

data class TerminalSnapshot(
    val columns: Int,
    val rows: Int,
    val scrollbackLines: List<String>,
    val visibleLines: List<String>,
    val isAtLiveBottom: Boolean,
    val alternateScreenActive: Boolean,
) {
    val visibleText: String = visibleLines.joinToString(separator = "\n") { it.trimEnd() }
    val completeText: String = (scrollbackLines + visibleLines).joinToString(separator = "\n") { it.trimEnd() }
}

class TerminalBuffer(
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
    private val maxScrollbackLines: Int = DEFAULT_MAX_SCROLLBACK_LINES,
) {
    private var columns: Int = columns.coerceAtLeast(1)
    private var rows: Int = rows.coerceAtLeast(1)
    private var mainScreen = createScreen(this.rows, this.columns)
    private var mainCursorRow = 0
    private var mainCursorColumn = 0
    private val scrollback = ArrayDeque<String>()
    private var viewportOffsetLines = 0

    private var alternateScreen = createScreen(this.rows, this.columns)
    private var alternateCursorRow = 0
    private var alternateCursorColumn = 0
    private var alternateScreenActive = false

    private var escapeState: EscapeState = EscapeState.None

    @Synchronized
    fun append(text: String) {
        text.forEach(::appendChar)
    }

    @Synchronized
    fun clear() {
        mainScreen = createScreen(rows, columns)
        mainCursorRow = 0
        mainCursorColumn = 0
        scrollback.clear()
        viewportOffsetLines = 0
        alternateScreen = createScreen(rows, columns)
        alternateCursorRow = 0
        alternateCursorColumn = 0
        alternateScreenActive = false
        escapeState = EscapeState.None
    }

    @Synchronized
    fun resize(columns: Int, rows: Int) {
        val safeColumns = columns.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)
        if (safeColumns == this.columns && safeRows == this.rows) {
            return
        }
        this.columns = safeColumns
        this.rows = safeRows
        mainScreen = resizeScreen(mainScreen, safeRows, safeColumns)
        alternateScreen = resizeScreen(alternateScreen, safeRows, safeColumns)
        mainCursorRow = mainCursorRow.coerceIn(0, safeRows - 1)
        alternateCursorRow = alternateCursorRow.coerceIn(0, safeRows - 1)
        mainCursorColumn = mainCursorColumn.coerceIn(0, safeColumns - 1)
        alternateCursorColumn = alternateCursorColumn.coerceIn(0, safeColumns - 1)
        viewportOffsetLines = viewportOffsetLines.coerceAtMost(maxViewportOffset())
    }

    @Synchronized
    fun scrollPageUp() {
        viewportOffsetLines = min(maxViewportOffset(), viewportOffsetLines + rows)
    }

    @Synchronized
    fun scrollPageDown() {
        viewportOffsetLines = max(0, viewportOffsetLines - rows)
    }

    @Synchronized
    fun scrollByLines(delta: Int) {
        viewportOffsetLines = (viewportOffsetLines + delta).coerceIn(0, maxViewportOffset())
    }

    @Synchronized
    fun jumpToBottom() {
        viewportOffsetLines = 0
    }

    @Synchronized
    fun snapshot(): TerminalSnapshot {
        val history = scrollback.toList()
        val screenLines = activeScreen().map(::String)
        val combined = history + screenLines
        val effectiveRows = rows.coerceAtLeast(1)
        val start = (combined.size - effectiveRows - viewportOffsetLines).coerceAtLeast(0)
        val end = min(combined.size, start + effectiveRows)
        val visible = combined.subList(start, end).padToSize(effectiveRows)
        return TerminalSnapshot(
            columns = columns,
            rows = rows,
            scrollbackLines = history,
            visibleLines = visible,
            isAtLiveBottom = viewportOffsetLines == 0,
            alternateScreenActive = alternateScreenActive,
        )
    }

    private fun appendChar(char: Char) {
        when (val currentState = escapeState) {
            is EscapeState.None -> when (char) {
                '\u001B' -> escapeState = EscapeState.Started
                '\n' -> {
                    lineFeed()
                    setCursorColumn(0)
                }
                '\r' -> setCursorColumn(0)
                '\b' -> setCursorColumn(currentCursorColumn() - 1)
                '\t' -> repeat(8 - (currentCursorColumn() % 8)) { putChar(' ') }
                else -> if (char >= ' ') {
                    putChar(char)
                }
            }

            is EscapeState.Started -> {
                if (char == '[') {
                    escapeState = EscapeState.ControlSequence("")
                } else {
                    escapeState = EscapeState.None
                }
            }

            is EscapeState.ControlSequence -> {
                if (char in CONTROL_SEQUENCE_FINAL_CHARS) {
                    handleControlSequence(currentState.payload + char)
                    escapeState = EscapeState.None
                } else {
                    escapeState = EscapeState.ControlSequence(currentState.payload + char)
                }
            }
        }
    }

    private fun handleControlSequence(sequence: String) {
        val command = sequence.last()
        val payload = sequence.dropLast(1)
        val privateMode = payload.startsWith("?")
        val cleanPayload = payload.removePrefix("?")
        val params = cleanPayload.split(';')
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }

        if (privateMode && command in setOf('h', 'l')) {
            when (params.firstOrNull()) {
                47, 1047, 1049 -> setAlternateScreen(command == 'h')
            }
            return
        }

        when (command) {
            'A' -> setCursorRow(currentCursorRow() - params.firstOrDefault(1))
            'B' -> setCursorRow(currentCursorRow() + params.firstOrDefault(1))
            'C' -> setCursorColumn(currentCursorColumn() + params.firstOrDefault(1))
            'D' -> setCursorColumn(currentCursorColumn() - params.firstOrDefault(1))
            'H', 'f' -> {
                val row = params.getOrNull(0)?.takeIf { it > 0 } ?: 1
                val column = params.getOrNull(1)?.takeIf { it > 0 } ?: 1
                setCursorRow(row - 1)
                setCursorColumn(column - 1)
            }

            'J' -> when (params.firstOrNull() ?: 0) {
                2 -> clearActiveScreen(moveCursorHome = true)
                else -> clearFromCursorToEndOfScreen()
            }

            'K' -> when (params.firstOrNull() ?: 0) {
                2 -> clearCurrentLine()
                else -> clearFromCursorToEndOfLine()
            }

            'm', 'l', 'h' -> Unit
            else -> Unit
        }
    }

    private fun setAlternateScreen(enabled: Boolean) {
        if (alternateScreenActive == enabled) {
            return
        }
        alternateScreenActive = enabled
        if (enabled) {
            alternateScreen = createScreen(rows, columns)
            alternateCursorRow = 0
            alternateCursorColumn = 0
        } else {
            viewportOffsetLines = viewportOffsetLines.coerceAtMost(maxViewportOffset())
        }
    }

    private fun clearActiveScreen(moveCursorHome: Boolean) {
        val fresh = createScreen(rows, columns)
        if (alternateScreenActive) {
            alternateScreen = fresh
            if (moveCursorHome) {
                alternateCursorRow = 0
                alternateCursorColumn = 0
            }
        } else {
            mainScreen = fresh
            if (moveCursorHome) {
                mainCursorRow = 0
                mainCursorColumn = 0
            }
        }
    }

    private fun clearFromCursorToEndOfScreen() {
        clearFromCursorToEndOfLine()
        for (row in currentCursorRow() + 1 until rows) {
            activeScreen()[row] = blankLine()
        }
    }

    private fun clearCurrentLine() {
        activeScreen()[currentCursorRow()] = blankLine()
    }

    private fun clearFromCursorToEndOfLine() {
        val line = activeScreen()[currentCursorRow()]
        for (column in currentCursorColumn() until columns) {
            line[column] = ' '
        }
    }

    private fun putChar(char: Char) {
        if (currentCursorColumn() >= columns) {
            lineFeed()
            setCursorColumn(0)
        }
        activeScreen()[currentCursorRow()][currentCursorColumn()] = char
        if (currentCursorColumn() == columns - 1) {
            lineFeed()
            setCursorColumn(0)
        } else {
            setCursorColumn(currentCursorColumn() + 1)
        }
    }

    private fun lineFeed() {
        if (currentCursorRow() == rows - 1) {
            val screen = activeScreen()
            if (!alternateScreenActive) {
                scrollback += String(screen.first())
                while (scrollback.size > maxScrollbackLines) {
                    scrollback.removeFirst()
                }
            }
            for (index in 0 until rows - 1) {
                screen[index] = screen[index + 1]
            }
            screen[rows - 1] = blankLine()
        } else {
            setCursorRow(currentCursorRow() + 1)
        }
    }

    private fun activeScreen(): MutableList<CharArray> =
        if (alternateScreenActive) alternateScreen else mainScreen

    private fun currentCursorRow(): Int =
        if (alternateScreenActive) alternateCursorRow else mainCursorRow

    private fun currentCursorColumn(): Int =
        if (alternateScreenActive) alternateCursorColumn else mainCursorColumn

    private fun setCursorRow(value: Int) {
        if (alternateScreenActive) {
            alternateCursorRow = value.coerceIn(0, rows - 1)
        } else {
            mainCursorRow = value.coerceIn(0, rows - 1)
        }
    }

    private fun setCursorColumn(value: Int) {
        if (alternateScreenActive) {
            alternateCursorColumn = value.coerceIn(0, columns - 1)
        } else {
            mainCursorColumn = value.coerceIn(0, columns - 1)
        }
    }

    private fun maxViewportOffset(): Int =
        (scrollback.size + rows - rows).coerceAtLeast(0)

    private fun resizeScreen(
        source: MutableList<CharArray>,
        newRows: Int,
        newColumns: Int,
    ): MutableList<CharArray> {
        val resized = createScreen(newRows, newColumns)
        for (row in 0 until min(newRows, source.size)) {
            val original = source[row]
            for (column in 0 until min(newColumns, original.size)) {
                resized[row][column] = original[column]
            }
        }
        return resized
    }

    private fun blankLine(): CharArray = CharArray(columns) { ' ' }

    private fun createScreen(rows: Int, columns: Int): MutableList<CharArray> =
        MutableList(rows) { CharArray(columns) { ' ' } }

    private fun List<String>.padToSize(size: Int): List<String> =
        if (this.size >= size) this else this + List(size - this.size) { "" }

    private sealed interface EscapeState {
        data object None : EscapeState
        data object Started : EscapeState
        data class ControlSequence(val payload: String) : EscapeState
    }

    companion object {
        private val CONTROL_SEQUENCE_FINAL_CHARS =
            ('@'..'~').toSet()

        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_MAX_SCROLLBACK_LINES = 1_000
    }
}

private fun List<Int>.firstOrDefault(defaultValue: Int): Int =
    firstOrNull()?.takeIf { it > 0 } ?: defaultValue
