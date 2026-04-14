package io.github.jtsang4.aterm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.jtsang4.aterm.core.designsystem.AppScreenScaffold
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.core.domain.model.UserPreferences
import io.github.jtsang4.aterm.core.domain.repository.SettingsRepository
import kotlinx.coroutines.launch

object SettingsEntryPoint {
    const val route = "settings"
}

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
) {
    val preferences by settingsRepository.observePreferences().collectAsState(
        initial = UserPreferences(),
    )
    val coroutineScope = rememberCoroutineScope()

    AppScreenScaffold(
        title = "Settings",
        supportingText = "Theme and terminal font preferences apply across live and future local surfaces and stay available after relaunch.",
        modifier = Modifier.testTag("screen_settings"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.testTag("settings_content"),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_theme_card"),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Choose how hosts, snippets, session chrome, and confirmation surfaces are presented.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ThemePreference.entries.forEach { preference ->
                        ThemeOptionRow(
                            preference = preference,
                            selected = preferences.themePreference == preference,
                            onSelect = {
                                coroutineScope.launch {
                                    settingsRepository.updateTheme(preference)
                                }
                            },
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_terminal_font_card"),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "Terminal font size",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Current scale: ${"%.2f".format(preferences.terminalFontScale)}×",
                        modifier = Modifier.testTag("settings_terminal_font_value"),
                    )
                    Slider(
                        value = preferences.terminalFontScale,
                        onValueChange = { newScale ->
                            coroutineScope.launch {
                                settingsRepository.updateTerminalFontScale(newScale)
                            }
                        },
                        valueRange = 0.75f..2f,
                        steps = 4,
                        modifier = Modifier.testTag("settings_terminal_font_slider"),
                    )
                    Text(
                        text = "Updates the currently visible terminal and future sessions immediately.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    preference: ThemePreference,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
            )
            .padding(vertical = 4.dp)
            .testTag("settings_theme_option_${preference.name.lowercase()}"),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = preference.label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = preference.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ThemePreference.label: String
    get() = when (this) {
        ThemePreference.SYSTEM -> "System"
        ThemePreference.LIGHT -> "Light"
        ThemePreference.DARK -> "Dark"
    }

private val ThemePreference.description: String
    get() = when (this) {
        ThemePreference.SYSTEM -> "Follow the device theme."
        ThemePreference.LIGHT -> "Use the light app palette."
        ThemePreference.DARK -> "Use the dark app palette."
    }
