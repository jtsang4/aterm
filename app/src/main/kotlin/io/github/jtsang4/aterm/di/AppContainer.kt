package io.github.jtsang4.aterm.di

import android.content.Context
import io.github.jtsang4.aterm.core.data.DataModuleMarker
import io.github.jtsang4.aterm.core.security.SecurityModuleMarker
import io.github.jtsang4.aterm.core.ssh.SshSessionCoordinator
import io.github.jtsang4.aterm.core.ssh.SshModuleMarker
import io.github.jtsang4.aterm.core.terminal.TerminalModuleMarker
import io.github.jtsang4.aterm.navigation.AppDestination

data class AppDependencySnapshot(
    val data: String,
    val security: String,
    val ssh: String,
    val terminal: String,
)

class AppContainer private constructor(
    private val foundationGraphFactory: (() -> AppFoundationGraph)?,
) {
    val topLevelDestinations: List<AppDestination> = AppDestination.topLevel

    val dependencySnapshot = AppDependencySnapshot(
        data = DataModuleMarker.description,
        security = SecurityModuleMarker.description,
        ssh = SshModuleMarker.description,
        terminal = TerminalModuleMarker.description,
    )

    val foundationGraph: AppFoundationGraph by lazy {
        checkNotNull(foundationGraphFactory) {
            "AppFoundationGraph is only available from a context-backed container."
        }.invoke()
    }

    val sshSessionCoordinator: SshSessionCoordinator by lazy {
        SshSessionCoordinator(
            hostRepository = foundationGraph.hostRepository,
            identityRepository = foundationGraph.identityRepository,
            knownHostTrustRepository = foundationGraph.knownHostTrustRepository,
        )
    }

    companion object {
        fun create(applicationContext: Context): AppContainer = AppContainer(
            foundationGraphFactory = { buildAppFoundationGraph(applicationContext.applicationContext) },
        )

        fun preview(): AppContainer = AppContainer(foundationGraphFactory = null)
    }
}
