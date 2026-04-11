package io.github.jtsang4.aterm.core.terminal

import kotlin.math.max

data class TerminalViewport(
    val columns: Int,
    val rows: Int,
    val widthPx: Int,
    val heightPx: Int,
)

fun calculateTerminalViewport(
    contentWidthPx: Int,
    contentHeightPx: Int,
    cellWidthPx: Int,
    cellHeightPx: Int,
): TerminalViewport? {
    val safeWidth = contentWidthPx.coerceAtLeast(0)
    val safeHeight = contentHeightPx.coerceAtLeast(0)
    if (safeWidth == 0 || safeHeight == 0) {
        return null
    }
    val safeCellWidth = max(1, cellWidthPx)
    val safeCellHeight = max(1, cellHeightPx)
    return TerminalViewport(
        columns = max(1, safeWidth / safeCellWidth),
        rows = max(1, safeHeight / safeCellHeight),
        widthPx = safeWidth,
        heightPx = safeHeight,
    )
}
