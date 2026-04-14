package io.github.jtsang4.aterm.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.hosts.HostsScreen
import io.github.jtsang4.aterm.feature.session.SessionsScreen
import io.github.jtsang4.aterm.feature.settings.SettingsPlaceholder
import io.github.jtsang4.aterm.feature.snippets.SnippetsScreen

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
                onOpenRecentHost = { hostId ->
                    navController.navigate(AppDestination.Session.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                    appContainer.sshSessionCoordinator.connect(hostId)
                },
            )
        }
        composable(AppDestination.Identities.route) {
            identitiesScreen()
        }
        composable(AppDestination.Session.route) {
            SessionsScreen(
                hostRepository = appContainer.foundationGraph.hostRepository,
                identityRepository = appContainer.foundationGraph.identityRepository,
                knownHostTrustRepository = appContainer.foundationGraph.knownHostTrustRepository,
                coordinator = appContainer.sshSessionCoordinator,
            )
        }
        composable(AppDestination.Snippets.route) {
            SnippetsScreen(
                snippetRepository = appContainer.foundationGraph.snippetRepository,
                hostRepository = appContainer.foundationGraph.hostRepository,
                sessionController = appContainer.sshSessionCoordinator,
            )
        }
        composable(AppDestination.Settings.route) {
            SettingsPlaceholder()
        }
    }
}
