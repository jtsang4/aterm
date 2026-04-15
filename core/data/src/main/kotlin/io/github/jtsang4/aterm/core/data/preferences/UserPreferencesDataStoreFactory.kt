package io.github.jtsang4.aterm.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ManagedUserPreferencesDataStore(
    val dataStore: DataStore<Preferences>,
    val clear: () -> Unit,
    val close: () -> Unit,
)

fun createUserPreferencesDataStore(
    context: Context,
    fileName: String = "user_preferences.preferences_pb",
): ManagedUserPreferencesDataStore {
    val applicationContext = context.applicationContext
    val file = applicationContext.preferencesDataStoreFile(fileName)
    val trackedStore = synchronized(dataStores) {
        dataStores[file.absolutePath]?.also { it.references.incrementAndGet() } ?: run {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            TrackedUserPreferencesDataStore(
                scope = scope,
                dataStore = PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { file },
                ),
            ).also { created ->
                dataStores[file.absolutePath] = created
            }
        }
    }
    return ManagedUserPreferencesDataStore(
        dataStore = trackedStore.dataStore,
        clear = {
            runBlocking {
                trackedStore.dataStore.edit { preferences ->
                    preferences.clear()
                }
                trackedStore.dataStore.data.first()
            }
        },
        close = {
            synchronized(dataStores) {
                val current = dataStores[file.absolutePath]
                if (current !== trackedStore) {
                    return@synchronized
                }
                if (trackedStore.references.decrementAndGet() <= 0) {
                    dataStores.remove(file.absolutePath, trackedStore)
                    trackedStore.scope.cancel()
                }
            }
        },
    )
}

private data class TrackedUserPreferencesDataStore(
    val scope: CoroutineScope,
    val dataStore: DataStore<Preferences>,
    val references: AtomicInteger = AtomicInteger(1),
)

private val dataStores = ConcurrentHashMap<String, TrackedUserPreferencesDataStore>()
