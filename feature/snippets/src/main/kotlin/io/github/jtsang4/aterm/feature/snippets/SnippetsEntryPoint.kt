package io.github.jtsang4.aterm.feature.snippets

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

object SnippetsEntryPoint {
    const val route = "snippets"
}

@Composable
fun SnippetsPlaceholder() {
    Text(text = "Snippets placeholder")
}
