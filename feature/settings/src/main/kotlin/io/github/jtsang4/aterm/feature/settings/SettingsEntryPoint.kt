package io.github.jtsang4.aterm.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold

object SettingsEntryPoint {
    const val route = "settings"
}

@Composable
fun SettingsPlaceholder() {
    AppScreenScaffold(
        title = "Settings",
        supportingText = "Theme, font, and other local preferences will attach to this settings scaffold in later features.",
        modifier = Modifier.testTag("screen_settings"),
    )
}
