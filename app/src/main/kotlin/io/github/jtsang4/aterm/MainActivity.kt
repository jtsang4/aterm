package io.github.jtsang4.aterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appContainer = (application as AtermApplication).appContainer
            AtermApp(appContainer = appContainer)
        }
    }
}
