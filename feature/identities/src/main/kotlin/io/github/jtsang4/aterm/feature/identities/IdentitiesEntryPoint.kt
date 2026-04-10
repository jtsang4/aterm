package io.github.jtsang4.aterm.feature.identities

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold

object IdentitiesEntryPoint {
    const val route = "identities"
}

@Composable
fun IdentitiesPlaceholder() {
    AppScreenScaffold(
        title = "Identities",
        supportingText = "Reusable password and key identities will plug into this scaffold without cloud or account dependencies.",
        modifier = Modifier.testTag("screen_identities"),
    )
}
