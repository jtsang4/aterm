package io.github.jtsang4.aterm.feature.snippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold

object SnippetsEntryPoint {
    const val route = "snippets"
}

@Composable
fun SnippetsPlaceholder() {
    AppScreenScaffold(
        title = "Snippets",
        supportingText = "Saved command snippets and execution history will build on this placeholder without leaving the device.",
        modifier = Modifier.testTag("screen_snippets"),
    )
}
