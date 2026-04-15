package io.github.jtsang4.aterm

import android.content.Context
import io.github.jtsang4.aterm.di.AppContainer
import java.io.File

internal fun resetTestPersistenceState(context: Context) {
    val application = context.applicationContext as? AtermApplication
    if (application != null) {
        application.clearPersistentStateForTesting()
    } else {
        AppContainer.closeAllTrackedContainersForTesting(clearPersistentState = true)
    }
    context.deleteDatabase("aterm.db")
    File(context.filesDir.parentFile, "datastore").deleteRecursively()
}
