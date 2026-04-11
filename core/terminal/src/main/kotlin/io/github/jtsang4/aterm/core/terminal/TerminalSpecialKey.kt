package io.github.jtsang4.aterm.core.terminal

enum class TerminalSpecialKey(
    val label: String,
    val encoded: String,
) {
    CtrlC("Ctrl+C", "\u0003"),
    Tab("Tab", "\t"),
    Esc("Esc", "\u001B"),
    ArrowUp("↑", "\u001B[A"),
    ArrowDown("↓", "\u001B[B"),
    ArrowLeft("←", "\u001B[D"),
    ArrowRight("→", "\u001B[C"),
}
