package io.github.jtsang4.aterm.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.core.data.local.AtermDatabase
import io.github.jtsang4.aterm.core.data.preferences.PreferencesSettingsRepository
import io.github.jtsang4.aterm.core.data.repository.RoomHostRepository
import io.github.jtsang4.aterm.core.data.repository.RoomIdentityRepository
import io.github.jtsang4.aterm.core.data.repository.RoomKnownHostTrustRepository
import io.github.jtsang4.aterm.core.data.repository.RoomSessionMetadataRepository
import io.github.jtsang4.aterm.core.data.repository.RoomSnippetRepository
import io.github.jtsang4.aterm.core.domain.FeatureArea
import io.github.jtsang4.aterm.core.domain.fixtures.sampleHost
import io.github.jtsang4.aterm.core.domain.fixtures.sampleIdentity
import io.github.jtsang4.aterm.core.domain.fixtures.sampleKnownHostTrust
import io.github.jtsang4.aterm.core.domain.fixtures.sampleSessionMetadata
import io.github.jtsang4.aterm.core.domain.fixtures.sampleSnippet
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.core.security.crypto.KeystoreAesGcmCipher
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationRepositoriesInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: AtermDatabase
    private lateinit var hostRepository: RoomHostRepository
    private lateinit var identityRepository: RoomIdentityRepository
    private lateinit var snippetRepository: RoomSnippetRepository
    private lateinit var sessionRepository: RoomSessionMetadataRepository
    private lateinit var knownHostTrustRepository: RoomKnownHostTrustRepository
    private lateinit var settingsRepository: PreferencesSettingsRepository
    private lateinit var cipher: KeystoreAesGcmCipher
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AtermDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cipher = KeystoreAesGcmCipher("repo.test.${UUID.randomUUID()}")
        hostRepository = RoomHostRepository(database.hostDao())
        identityRepository = RoomIdentityRepository(database.identityDao(), cipher)
        snippetRepository = RoomSnippetRepository(database.snippetDao(), cipher)
        sessionRepository = RoomSessionMetadataRepository(database.sessionMetadataDao())
        knownHostTrustRepository = RoomKnownHostTrustRepository(database.knownHostTrustDao())
        dataStoreFile = File(context.cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
        settingsRepository = PreferencesSettingsRepository(
            PreferenceDataStoreFactory.create(produceFile = { dataStoreFile }),
        )
    }

    @After
    fun tearDown() {
        database.close()
        cipher.deleteKey()
        dataStoreFile.delete()
    }

    @Test
    fun host_and_session_repositories_round_trip_non_secret_models() = runTest {
        val identity = identityRepository.upsert(
            sampleIdentity().copy(id = 0),
            IdentitySecretMaterial(primarySecret = "hunter2", passphrase = "swordfish"),
        )
        val host = hostRepository.upsert(sampleHost(id = 0, identityId = identity.id))
        hostRepository.markUsed(host.id, Instant.parse("2026-04-10T01:00:00Z"))
        val session = sessionRepository.upsert(sampleSessionMetadata(id = 0, hostId = host.id))

        val persistedHost = hostRepository.getHost(host.id)
        val persistedSession = sessionRepository.getSession(session.id)

        assertEquals("example.internal:22", persistedHost?.endpoint)
        assertEquals(host.id, persistedSession?.hostId)
        assertTrue(hostRepository.observeHosts().first().any { it.id == host.id })
    }

    @Test
    fun identity_repository_encrypts_secret_material_and_restores_plaintext() = runTest {
        val identity = identityRepository.upsert(
            sampleIdentity().copy(id = 0),
            IdentitySecretMaterial(
                primarySecret = "correct horse battery staple",
                passphrase = "open sesame",
            ),
        )

        val secrets = identityRepository.getSecretMaterial(identity.id)
        val rawEntity = database.identityDao().getById(identity.id)

        assertTrue(identity.hasSecret)
        assertEquals("correct horse battery staple", secrets?.primarySecret)
        assertEquals("open sesame", secrets?.passphrase)
        assertNotNull(rawEntity?.primaryCipherText)
        assertFalse(
            rawEntity!!.primaryCipherText!!.contentEquals(
                "correct horse battery staple".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun snippet_repository_encrypts_body_and_preserves_host_association() = runTest {
        val identity = identityRepository.upsert(
            sampleIdentity().copy(id = 0),
            IdentitySecretMaterial(primarySecret = "pw"),
        )
        val host = hostRepository.upsert(sampleHost(id = 0, identityId = identity.id))
        val snippet = snippetRepository.upsert(
            sampleSnippet(id = 0, hostId = host.id),
            body = "sudo systemctl restart aterm",
        )
        snippetRepository.markExecuted(snippet.id, Instant.parse("2026-04-10T02:00:00Z"))

        val persistedSnippet = snippetRepository.getSnippet(snippet.id)
        val body = snippetRepository.getBody(snippet.id)
        val rawEntity = database.snippetDao().getById(snippet.id)

        assertEquals(host.id, persistedSnippet?.hostId)
        assertEquals("sudo systemctl restart aterm", body)
        assertNotNull(rawEntity?.bodyCipherText)
        assertFalse(
            rawEntity!!.bodyCipherText!!.contentEquals(
                "sudo systemctl restart aterm".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun settings_repository_persists_theme_font_and_last_viewed_area() = runTest {
        settingsRepository.updateTheme(ThemePreference.DARK)
        settingsRepository.updateTerminalFontScale(1.2f)
        settingsRepository.updateLastViewedArea(FeatureArea.Snippets)

        val preferences = settingsRepository.observePreferences().first()

        assertEquals(ThemePreference.DARK, preferences.themePreference)
        assertEquals(1.2f, preferences.terminalFontScale)
        assertEquals(FeatureArea.Snippets, preferences.lastViewedArea)
    }

    @Test
    fun known_host_trust_repository_keys_records_by_endpoint() = runTest {
        knownHostTrustRepository.upsert(sampleKnownHostTrust())

        val trust = knownHostTrustRepository.findTrustedHost("example.internal", 22)
        val missing = knownHostTrustRepository.findTrustedHost("example.internal", 2222)

        assertEquals("SHA256:examplefingerprint", trust?.fingerprint)
        assertEquals(null, missing)
    }
}
