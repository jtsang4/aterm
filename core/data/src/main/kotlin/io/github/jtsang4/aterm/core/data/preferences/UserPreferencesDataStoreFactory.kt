package io.github.jtsang4.aterm.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap

fun createUserPreferencesDataStore(
    context: Context,
    fileName: String = "user_preferences.preferences_pb",
): DataStore<Preferences> {
    val applicationContext = context.applicationContext
    val file = applicationContext.preferencesDataStoreFile(fileName)
    return dataStores.getOrPut(file.absolutePath) {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { file },
        )
    }
}

private val dataStores = ConcurrentHashMap<String, DataStore<Preferences>>()
