package io.github.jtsang4.aterm.di

import android.content.Context
import io.github.jtsang4.aterm.core.data.DataModuleMarker
import io.github.jtsang4.aterm.core.security.SecurityModuleMarker
import io.github.jtsang4.aterm.core.ssh.SshSessionCoordinator
import io.github.jtsang4.aterm.core.ssh.SshModuleMarker
import io.github.jtsang4.aterm.core.terminal.TerminalModuleMarker
import io.github.jtsang4.aterm.feature.identities.GeneratedKeyIdentityService
import io.github.jtsang4.aterm.feature.identities.ImportedKeyImportService
import io.github.jtsang4.aterm.navigation.AppDestination

data class AppDependencySnapshot(
    val data: String,
    val security: String,
    val ssh: String,
    val terminal: String,
)

class AppContainer private constructor(
    private val foundationGraphFactory: (() -> AppFoundationGraph)?,
    val importedKeyImportService: ImportedKeyImportService,
    val generatedKeyIdentityService: GeneratedKeyIdentityService,
) {
    private val foundationGraphDelegate = lazy {
        checkNotNull(foundationGraphFactory) {
            "AppFoundationGraph is only available from a context-backed container."
        }.invoke()
    }
    private val sshSessionCoordinatorDelegate = lazy {
        SshSessionCoordinator(
            hostRepository = foundationGraph.hostRepository,
            identityRepository = foundationGraph.identityRepository,
            knownHostTrustRepository = foundationGraph.knownHostTrustRepository,
            sessionMetadataRepository = foundationGraph.sessionMetadataRepository,
        )
    }

    val topLevelDestinations: List<AppDestination> = AppDestination.topLevel

    val dependencySnapshot = AppDependencySnapshot(
        data = DataModuleMarker.description,
        security = SecurityModuleMarker.description,
        ssh = SshModuleMarker.description,
        terminal = TerminalModuleMarker.description,
    )

    val foundationGraph: AppFoundationGraph
        get() = foundationGraphDelegate.value

    val settingsRepositoryOrNull
        get() = foundationGraphFactory?.let { foundationGraph.settingsRepository }

    val sshSessionCoordinatorOrNull
        get() = foundationGraphFactory?.let { sshSessionCoordinator }

    val sshSessionCoordinator: SshSessionCoordinator
        get() = sshSessionCoordinatorDelegate.value

    fun close() {
        if (sshSessionCoordinatorDelegate.isInitialized()) {
            sshSessionCoordinator.close()
        }
        if (foundationGraphDelegate.isInitialized()) {
            foundationGraph.close()
        }
    }

    companion object {
        fun create(
            applicationContext: Context,
            importedKeyImportService: ImportedKeyImportService = ImportedKeyImportService(),
            generatedKeyIdentityService: GeneratedKeyIdentityService = GeneratedKeyIdentityService(),
        ): AppContainer = AppContainer(
            foundationGraphFactory = { buildAppFoundationGraph(applicationContext.applicationContext) },
            importedKeyImportService = importedKeyImportService,
            generatedKeyIdentityService = generatedKeyIdentityService,
        )

        fun preview(): AppContainer = AppContainer(
            foundationGraphFactory = null,
            importedKeyImportService = ImportedKeyImportService(),
            generatedKeyIdentityService = GeneratedKeyIdentityService(),
        )
    }
}
