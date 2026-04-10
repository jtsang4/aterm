package io.github.jtsang4.aterm

import android.app.Application
import io.github.jtsang4.aterm.di.AppContainer

class AtermApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer() }
}
