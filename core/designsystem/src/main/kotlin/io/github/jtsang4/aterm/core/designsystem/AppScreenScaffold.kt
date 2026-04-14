package io.github.jtsang4.aterm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun AppScreenScaffold(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    content: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineMedium)
                Text(text = supportingText, style = MaterialTheme.typography.bodyLarge)
                content?.invoke()
            }
            Text(
                text = if (LocalAtermDarkTheme.current) "dark" else "light",
                color = Color.Transparent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .testTag("theme_mode_marker"),
            )
        }
    }
}
