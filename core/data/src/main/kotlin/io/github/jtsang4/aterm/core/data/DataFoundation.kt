package io.github.jtsang4.aterm.core.data

import android.content.Context
import io.github.jtsang4.aterm.core.data.local.AtermDatabase
import io.github.jtsang4.aterm.core.data.preferences.PreferencesSettingsRepository
import io.github.jtsang4.aterm.core.data.preferences.createUserPreferencesDataStore
import io.github.jtsang4.aterm.core.data.repository.RoomHostRepository
import io.github.jtsang4.aterm.core.data.repository.RoomIdentityRepository
import io.github.jtsang4.aterm.core.data.repository.RoomKnownHostTrustRepository
import io.github.jtsang4.aterm.core.data.repository.RoomSessionMetadataRepository
import io.github.jtsang4.aterm.core.data.repository.RoomSnippetRepository
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import io.github.jtsang4.aterm.core.domain.repository.SessionMetadataRepository
import io.github.jtsang4.aterm.core.domain.repository.SettingsRepository
import io.github.jtsang4.aterm.core.domain.repository.SnippetRepository
import io.github.jtsang4.aterm.core.security.crypto.SecretFieldCipher

data class LocalDataFoundation(
    val hostRepository: HostRepository,
    val identityRepository: IdentityRepository,
    val snippetRepository: SnippetRepository,
    val sessionMetadataRepository: SessionMetadataRepository,
    val knownHostTrustRepository: KnownHostTrustRepository,
    val settingsRepository: SettingsRepository,
)

fun buildLocalDataFoundation(
    context: Context,
    fieldCipher: SecretFieldCipher,
): LocalDataFoundation {
    val database = AtermDatabase.build(context)
    return LocalDataFoundation(
        hostRepository = RoomHostRepository(database.hostDao()),
        identityRepository = RoomIdentityRepository(database.identityDao(), fieldCipher),
        snippetRepository = RoomSnippetRepository(database.snippetDao(), fieldCipher),
        sessionMetadataRepository = RoomSessionMetadataRepository(database.sessionMetadataDao()),
        knownHostTrustRepository = RoomKnownHostTrustRepository(database.knownHostTrustDao()),
        settingsRepository = PreferencesSettingsRepository(createUserPreferencesDataStore(context)),
    )
}
