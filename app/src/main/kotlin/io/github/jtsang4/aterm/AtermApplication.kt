package io.github.jtsang4.aterm

import android.app.Application
import io.github.jtsang4.aterm.di.AppContainer

class AtermApplication : Application() {
    private val defaultAppContainer: AppContainer by lazy { AppContainer.create(this) }

    @Volatile
    private var appContainerOverride: AppContainer? = null

    val appContainer: AppContainer
        get() = appContainerOverride ?: defaultAppContainer

    fun replaceAppContainerForTesting(container: AppContainer) {
        appContainerOverride = container
    }

    fun clearAppContainerOverrideForTesting() {
        appContainerOverride = null
    }
}
