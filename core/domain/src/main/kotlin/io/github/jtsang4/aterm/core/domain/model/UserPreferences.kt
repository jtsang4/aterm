package io.github.jtsang4.aterm.core.domain.model

import io.github.jtsang4.aterm.core.domain.FeatureArea

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

data class UserPreferences(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val terminalFontScale: Float = 1f,
    val lastViewedArea: FeatureArea = FeatureArea.Hosts,
) {
    init {
        require(terminalFontScale in 0.75f..2f) {
            "Terminal font scale must stay within the supported bounds."
        }
    }
}
