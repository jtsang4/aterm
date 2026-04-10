package io.github.jtsang4.aterm.di

import io.github.jtsang4.aterm.core.data.DataModuleMarker
import io.github.jtsang4.aterm.core.security.SecurityModuleMarker
import io.github.jtsang4.aterm.core.ssh.SshModuleMarker
import io.github.jtsang4.aterm.core.terminal.TerminalModuleMarker
import io.github.jtsang4.aterm.navigation.AppDestination

data class AppDependencySnapshot(
    val data: String,
    val security: String,
    val ssh: String,
    val terminal: String,
)

class AppContainer {
    val topLevelDestinations: List<AppDestination> = AppDestination.topLevel

    val dependencySnapshot = AppDependencySnapshot(
        data = DataModuleMarker.description,
        security = SecurityModuleMarker.description,
        ssh = SshModuleMarker.description,
        terminal = TerminalModuleMarker.description,
    )
}
