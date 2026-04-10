package io.github.jtsang4.aterm.di

import android.content.Context
import io.github.jtsang4.aterm.core.data.buildLocalDataFoundation
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import io.github.jtsang4.aterm.core.domain.repository.SessionMetadataRepository
import io.github.jtsang4.aterm.core.domain.repository.SettingsRepository
import io.github.jtsang4.aterm.core.domain.repository.SnippetRepository
import io.github.jtsang4.aterm.core.security.crypto.KeystoreAesGcmCipher
import io.github.jtsang4.aterm.core.security.crypto.SecretFieldCipher

data class AppFoundationGraph(
    val hostRepository: HostRepository,
    val identityRepository: IdentityRepository,
    val snippetRepository: SnippetRepository,
    val sessionMetadataRepository: SessionMetadataRepository,
    val knownHostTrustRepository: KnownHostTrustRepository,
    val settingsRepository: SettingsRepository,
    val fieldCipher: SecretFieldCipher,
)

fun buildAppFoundationGraph(context: Context): AppFoundationGraph {
    val fieldCipher = KeystoreAesGcmCipher()
    val localDataFoundation = buildLocalDataFoundation(context, fieldCipher)
    return AppFoundationGraph(
        hostRepository = localDataFoundation.hostRepository,
        identityRepository = localDataFoundation.identityRepository,
        snippetRepository = localDataFoundation.snippetRepository,
        sessionMetadataRepository = localDataFoundation.sessionMetadataRepository,
        knownHostTrustRepository = localDataFoundation.knownHostTrustRepository,
        settingsRepository = localDataFoundation.settingsRepository,
        fieldCipher = fieldCipher,
    )
}
