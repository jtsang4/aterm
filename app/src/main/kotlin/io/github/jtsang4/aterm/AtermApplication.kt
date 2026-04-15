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
        val previousOverride = appContainerOverride
        val previousDefault = _defaultAppContainer
        appContainerOverride = container
        if (previousOverride !== container) {
            previousOverride?.close()
        }
        if (previousDefault !== container) {
            previousDefault?.close()
            if (previousDefault != null) {
                _defaultAppContainer = null
            }
        }
    }

    fun clearAppContainerOverrideForTesting() {
        appContainerOverride?.close()
        appContainerOverride = null
    }

    fun resetDefaultContainerForTesting() {
        AppContainer.closeAllTrackedContainersForTesting(clearPersistentState = true)
        _defaultAppContainer = null
        appContainerOverride = null
    }

    fun clearPersistentStateForTesting() {
        AppContainer.closeAllTrackedContainersForTesting(clearPersistentState = true)
        _defaultAppContainer = null
        appContainerOverride = null
    }
}
