package io.github.jtsang4.aterm.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@Stable
class AtermAppState(
    val navController: NavHostController,
) {
    val topLevelDestinations: List<AppDestination> = AppDestination.topLevel

    @Composable
    fun currentDestination(): AppDestination {
        val currentBackStackEntry = navController.currentBackStackEntryAsState()
        val currentNavDestination = currentBackStackEntry.value?.destination
        return topLevelDestinations.firstOrNull { destination ->
            currentNavDestination?.hierarchy?.any { it.route == destination.route } == true
        } ?: AppDestination.Hosts
    }

    fun navigateTo(destination: AppDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
}

@Composable
fun rememberAtermAppState(
    navController: NavHostController = rememberNavController(),
): AtermAppState = remember(navController) { AtermAppState(navController) }
