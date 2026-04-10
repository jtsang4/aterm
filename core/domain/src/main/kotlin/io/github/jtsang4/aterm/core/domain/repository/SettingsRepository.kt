package io.github.jtsang4.aterm.core.domain.repository

import io.github.jtsang4.aterm.core.domain.FeatureArea
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.core.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observePreferences(): Flow<UserPreferences>
    suspend fun updateTheme(themePreference: ThemePreference)
    suspend fun updateTerminalFontScale(scale: Float)
    suspend fun updateLastViewedArea(featureArea: FeatureArea)
}
