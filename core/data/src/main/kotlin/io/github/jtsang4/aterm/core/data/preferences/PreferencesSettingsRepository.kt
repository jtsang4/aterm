package io.github.jtsang4.aterm.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jtsang4.aterm.core.domain.FeatureArea
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.core.domain.model.UserPreferences
import io.github.jtsang4.aterm.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PreferencesSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override fun observePreferences(): Flow<UserPreferences> = dataStore.data.map { preferences ->
        UserPreferences(
            themePreference = preferences[THEME_KEY]?.let(ThemePreference::valueOf)
                ?: ThemePreference.SYSTEM,
            terminalFontScale = preferences[FONT_SCALE_KEY] ?: 1f,
            lastViewedArea = preferences[LAST_VIEWED_AREA_KEY]?.let(FeatureArea::valueOf)
                ?: FeatureArea.Hosts,
        )
    }

    override suspend fun updateTheme(themePreference: ThemePreference) {
        dataStore.edit { preferences ->
            preferences[THEME_KEY] = themePreference.name
        }
    }

    override suspend fun updateTerminalFontScale(scale: Float) {
        dataStore.edit { preferences ->
            preferences[FONT_SCALE_KEY] = scale.coerceIn(0.75f, 2f)
        }
    }

    override suspend fun updateLastViewedArea(featureArea: FeatureArea) {
        dataStore.edit { preferences ->
            preferences[LAST_VIEWED_AREA_KEY] = featureArea.name
        }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_preference")
        val FONT_SCALE_KEY = floatPreferencesKey("terminal_font_scale")
        val LAST_VIEWED_AREA_KEY = stringPreferencesKey("last_viewed_area")
    }
}
