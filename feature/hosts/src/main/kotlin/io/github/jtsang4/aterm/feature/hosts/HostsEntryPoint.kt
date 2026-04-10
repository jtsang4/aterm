package io.github.jtsang4.aterm.feature.hosts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold

object HostsEntryPoint {
    const val route = "hosts"
}

@Composable
fun HostsPlaceholder() {
    AppScreenScaffold(
        title = "Hosts",
        supportingText = "This local-only host library scaffold is ready for saved endpoints, favorites, and connection flows.",
        modifier = Modifier.testTag("screen_hosts"),
    )
}
