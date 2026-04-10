package io.github.jtsang4.aterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.domain.FeatureArea

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BootstrapApp(featureAreas = FeatureArea.entries)
        }
    }
}

@Composable
private fun BootstrapApp(featureAreas: List<FeatureArea>) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(text = "aterm", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Android toolchain bootstrap complete. Feature modules are wired for future work.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                featureAreas.forEach { area ->
                    Text(text = "• ${area.label}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
