package io.github.jtsang4.aterm.core.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun ComposeTerminalSurface(
    controller: TerminalController,
    modifier: Modifier = Modifier,
) {
    val terminalState = controller.observeTerminalUiState().collectAsState()
    ComposeTerminalSurface(
        terminalState = terminalState,
        onScrollPageUp = controller::scrollPageUp,
        onScrollPageDown = controller::scrollPageDown,
        onJumpToBottom = controller::jumpToBottom,
        modifier = modifier,
    )
}

@Composable
fun ComposeTerminalSurface(
    terminalState: TerminalUiState,
    onScrollPageUp: () -> Unit,
    onScrollPageDown: () -> Unit,
    onJumpToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(terminalState) {
        derivedStateOf { terminalState }
    }
    ComposeTerminalSurface(
        terminalState = state,
        onScrollPageUp = onScrollPageUp,
        onScrollPageDown = onScrollPageDown,
        onJumpToBottom = onJumpToBottom,
        modifier = modifier,
    )
}

@Composable
fun ComposeTerminalSurface(
    terminalState: State<TerminalUiState>,
    onScrollPageUp: () -> Unit,
    onScrollPageDown: () -> Unit,
    onJumpToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = terminalState.value.snapshot
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onScrollPageUp,
                modifier = Modifier.testTag("session_scrollback_up"),
            ) {
                Text("PgUp")
            }
            OutlinedButton(
                onClick = onScrollPageDown,
                modifier = Modifier.testTag("session_scrollback_down"),
            ) {
                Text("PgDn")
            }
            OutlinedButton(
                onClick = onJumpToBottom,
                modifier = Modifier.testTag("session_jump_to_live"),
            ) {
                Text("Live")
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp)
                .background(Color(0xFF101418))
                .padding(12.dp)
                .testTag("session_terminal_surface"),
        ) {
            itemsIndexed(snapshot.visibleLines) { index, line ->
                Text(
                    text = line.trimEnd().ifEmpty { " " },
                    color = Color(0xFFD7E3F4),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("session_terminal_line_$index"),
                )
            }
        }
        Text(
            text = buildString {
                append("Scrollback: ${snapshot.scrollbackLines.size} lines")
                if (!snapshot.isAtLiveBottom) {
                    append(" • viewing history")
                }
                if (snapshot.alternateScreenActive) {
                    append(" • full-screen program active")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("session_terminal_status"),
        )
    }
}

@Composable
fun TerminalSpecialKeyBar(
    enabled: Boolean,
    onSpecialKey: (TerminalSpecialKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("session_special_key_bar"),
    ) {
        TerminalSpecialKey.entries.forEach { key ->
            OutlinedButton(
                onClick = { onSpecialKey(key) },
                enabled = enabled,
                modifier = Modifier
                    .sizeIn(minWidth = 52.dp)
                    .testTag("session_special_key_${key.name}"),
            ) {
                Text(key.label)
            }
        }
    }
}
