package io.github.jtsang4.aterm

import android.app.Application
import io.github.jtsang4.aterm.di.AppContainer

class AtermApplication : Application() {
    @Volatile
    private var _defaultAppContainer: AppContainer? = null

    private val defaultAppContainer: AppContainer
        get() = _defaultAppContainer ?: AppContainer.create(this).also {
            _defaultAppContainer = it
        }

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

    fun resetDefaultContainerForTesting() {
        _defaultAppContainer = null
        appContainerOverride = null
    }

    fun clearPersistentStateForTesting() {
        _defaultAppContainer?.foundationGraph?.clearPersistentState?.invoke()
        appContainerOverride?.foundationGraph?.clearPersistentState?.invoke()
    }
}
