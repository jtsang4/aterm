package io.github.jtsang4.aterm.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.hosts.HostsScreen
import io.github.jtsang4.aterm.feature.session.SessionPlaceholder
import io.github.jtsang4.aterm.feature.settings.SettingsPlaceholder
import io.github.jtsang4.aterm.feature.snippets.SnippetsPlaceholder

@Composable
fun AtermNavHost(
    navController: NavHostController,
    appContainer: AppContainer,
    identitiesScreen: @Composable () -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.Hosts.route,
    ) {
        composable(AppDestination.Hosts.route) {
            HostsScreen(
                hostRepository = appContainer.foundationGraph.hostRepository,
                identityRepository = appContainer.foundationGraph.identityRepository,
            )
        }
        composable(AppDestination.Identities.route) {
            identitiesScreen()
        }
        composable(AppDestination.Session.route) {
            SessionPlaceholder()
        }
        composable(AppDestination.Snippets.route) {
            SnippetsPlaceholder()
        }
        composable(AppDestination.Settings.route) {
            SettingsPlaceholder()
        }
    }
}
