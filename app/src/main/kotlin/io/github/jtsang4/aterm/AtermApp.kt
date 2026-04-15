package io.github.jtsang4.aterm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import io.github.jtsang4.aterm.core.designsystem.AtermTheme
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.core.domain.model.UserPreferences
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.identities.IdentitiesScreen
import io.github.jtsang4.aterm.di.LocalAppContainer
import io.github.jtsang4.aterm.navigation.AppDestination
import io.github.jtsang4.aterm.navigation.AtermNavHost
import io.github.jtsang4.aterm.navigation.rememberAtermAppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtermApp(
    appContainer: AppContainer = AppContainer.preview(),
    identitiesScreen: @Composable (() -> Unit)? = null,
    showShellSummary: Boolean = BuildConfig.SHOW_SHELL_SUMMARY,
) {
    val settingsRepository = appContainer.settingsRepositoryOrNull
    val preferences by settingsRepository
        ?.observePreferences()
        ?.collectAsState(initial = UserPreferences())
        ?: remember {
            mutableStateOf(UserPreferences())
        }
    val darkTheme = when (preferences.themePreference) {
        ThemePreference.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val sshSessionCoordinator = appContainer.sshSessionCoordinatorOrNull

    AtermTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalAppContainer provides appContainer) {
            val appState = rememberAtermAppState()
            val currentDestination = appState.currentDestination()
            LaunchedEffect(currentDestination) {
                settingsRepository?.updateLastViewedArea(currentDestination.featureArea)
            }
            LaunchedEffect(preferences.terminalFontScale) {
                sshSessionCoordinator?.setTerminalFontScale(preferences.terminalFontScale)
            }

            Scaffold(
                modifier = Modifier.testTag("app_shell"),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = "aterm",
                                modifier = Modifier.testTag("app_title"),
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
                bottomBar = {
                    NavigationBar(modifier = Modifier.testTag("top_level_navigation")) {
                        appState.topLevelDestinations.forEach { destination ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav_${destination.route}"),
                                selected = destination == currentDestination,
                                onClick = { appState.navigateTo(destination) },
                                icon = { Text(text = destination.label.take(1)) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (showShellSummary) {
                        ShellSummaryRow(
                            currentDestination = currentDestination,
                            appContainer = appContainer,
                        )
                    }
                    AtermNavHost(
                        navController = appState.navController,
                        appContainer = appContainer,
                        identitiesScreen = identitiesScreen ?: {
                            IdentitiesScreen(
                                identityRepository = appContainer.foundationGraph.identityRepository,
                                importedKeyImportService = appContainer.importedKeyImportService,
                                generatedKeyIdentityService = appContainer.generatedKeyIdentityService,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellSummaryRow(
    currentDestination: AppDestination,
    appContainer: AppContainer,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shell_summary"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Local-only scaffold for future SSH, data, and settings features.",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Current area: ${currentDestination.label}",
                modifier = Modifier.testTag("current_area_label"),
            )
        }
        LazyRow(
            modifier = Modifier.testTag("dependency_snapshot"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                listOf(
                    "Data" to appContainer.dependencySnapshot.data,
                    "Security" to appContainer.dependencySnapshot.security,
                    "SSH" to appContainer.dependencySnapshot.ssh,
                    "Terminal" to appContainer.dependencySnapshot.terminal,
                ),
            ) { (label, description) ->
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("$label ready") },
                    modifier = Modifier.testTag("dependency_chip_$label"),
                )
                Text(text = description, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
