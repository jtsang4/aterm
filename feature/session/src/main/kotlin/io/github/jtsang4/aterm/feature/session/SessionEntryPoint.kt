package io.github.jtsang4.aterm.feature.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold

object SessionEntryPoint {
    const val route = "session"
}

@Composable
fun SessionPlaceholder() {
    AppScreenScaffold(
        title = "Sessions",
        supportingText = "The app shell owns truthful navigation into the terminal session area while runtime SSH state stays outside screen chrome.",
        modifier = Modifier.testTag("screen_session"),
    )
}
