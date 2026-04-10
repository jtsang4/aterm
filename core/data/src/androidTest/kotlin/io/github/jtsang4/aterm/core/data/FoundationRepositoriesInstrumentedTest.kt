package io.github.jtsang4.aterm.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.core.data.local.AtermDatabase
import io.github.jtsang4.aterm.core.data.local.mapper.toEntity
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
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.core.security.crypto.EncryptedPayload
import io.github.jtsang4.aterm.core.security.crypto.KeystoreAesGcmCipher
import io.github.jtsang4.aterm.core.security.crypto.SecretFieldCipher
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        identityRepository = RoomIdentityRepository(database, database.identityDao(), cipher)
        snippetRepository = RoomSnippetRepository(database, database.snippetDao(), cipher)
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
    fun password_identity_round_trips_and_preserves_secret_when_only_metadata_changes() = runTest {
        val created = identityRepository.upsert(
            sampleIdentity()
                .copy(id = 0, kind = IdentityKind.PASSWORD, hasPassphrase = false, username = null),
            IdentitySecretMaterial(primarySecret = "initial-password"),
        )

        val updated = identityRepository.upsert(
            created.copy(name = "Renamed password identity"),
            secrets = null,
        )

        val persistedIdentity = identityRepository.getIdentity(updated.id)
        val persistedSecrets = identityRepository.getSecretMaterial(updated.id)

        assertEquals(IdentityKind.PASSWORD, persistedIdentity?.kind)
        assertEquals("Renamed password identity", persistedIdentity?.name)
        assertEquals("initial-password", persistedSecrets?.primarySecret)
        assertEquals(null, persistedSecrets?.passphrase)
    }

    @Test
    fun identity_repository_marks_secret_blocked_when_keystore_access_is_lost() = runTest {
        val identity = identityRepository.upsert(
            sampleIdentity().copy(id = 0, hasPassphrase = false),
            IdentitySecretMaterial(primarySecret = "restart-safe-secret"),
        )

        cipher.deleteKey()

        try {
            identityRepository.getSecretMaterial(identity.id)
            error("Expected blocked secret state")
        } catch (expected: io.github.jtsang4.aterm.core.domain.model.SecretMaterialUnavailableException) {
            assertEquals(
                "Stored secret material is unavailable. Repair the identity to continue.",
                expected.message,
            )
        }

        val repairedState = identityRepository.getIdentity(identity.id)
        assertEquals("BLOCKED", repairedState?.secretStorageState?.name)
        assertTrue(repairedState?.hasSecret == true)
    }

    @Test
    fun identity_upsert_rolls_back_when_secret_encryption_fails() = runTest {
        val failingRepository = RoomIdentityRepository(
            database = database,
            identityDao = database.identityDao(),
            fieldCipher = FailingSecretFieldCipher,
        )

        try {
            failingRepository.upsert(
                sampleIdentity().copy(id = 0),
                IdentitySecretMaterial(primarySecret = "should fail"),
            )
            error("Expected encryption failure")
        } catch (expected: IllegalStateException) {
            assertEquals("encrypt failure", expected.message)
        }

        assertTrue(identityRepository.observeIdentities().first().isEmpty())
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
    fun snippet_upsert_rolls_back_when_body_encryption_fails() = runTest {
        val identity = identityRepository.upsert(
            sampleIdentity().copy(id = 0),
            IdentitySecretMaterial(primarySecret = "pw"),
        )
        val host = hostRepository.upsert(sampleHost(id = 0, identityId = identity.id))
        val failingRepository = RoomSnippetRepository(
            database = database,
            snippetDao = database.snippetDao(),
            fieldCipher = FailingSecretFieldCipher,
        )

        try {
            failingRepository.upsert(
                sampleSnippet(id = 0, hostId = host.id),
                body = "echo should fail",
            )
            error("Expected encryption failure")
        } catch (expected: IllegalStateException) {
            assertEquals("encrypt failure", expected.message)
        }

        assertTrue(snippetRepository.observeSnippets().first().isEmpty())
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

    @Test
    fun deleting_identity_preserves_host_record_and_clears_broken_link() = runTest {
        val identity = identityRepository.upsert(
            sampleIdentity().copy(id = 0),
            IdentitySecretMaterial(primarySecret = "repairable"),
        )
        val host = hostRepository.upsert(sampleHost(id = 0, identityId = identity.id))

        identityRepository.deleteIdentity(identity.id)

        val persistedHost = hostRepository.getHost(host.id)

        assertNotNull(persistedHost)
        assertNull(persistedHost?.identityId)
        assertFalse(requireNotNull(persistedHost).hasLinkedIdentity)
        assertEquals(HostAuthKind.KEY, persistedHost.authKind)
    }

    @Test
    fun deleting_host_cascades_sessions_and_detaches_targeted_snippets() = runTest {
        val identity = identityRepository.upsert(
            sampleIdentity().copy(id = 0),
            IdentitySecretMaterial(primarySecret = "pw"),
        )
        val host = hostRepository.upsert(sampleHost(id = 0, identityId = identity.id))
        val session = sessionRepository.upsert(sampleSessionMetadata(id = 0, hostId = host.id))
        val snippet = snippetRepository.upsert(
            sampleSnippet(id = 0, hostId = host.id),
            body = "echo host target",
        )

        hostRepository.deleteHost(host.id)

        assertNull(sessionRepository.getSession(session.id))
        assertNull(hostRepository.getHost(host.id))
        assertNull(snippetRepository.getSnippet(snippet.id)?.hostId)
    }

    @Test
    fun foreign_keys_reject_sessions_for_missing_hosts() = runTest {
        try {
            database.sessionMetadataDao().insert(sampleSessionMetadata(id = 0, hostId = 999).toEntity())
            error("Expected foreign key constraint failure")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            assertTrue(expected.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
        }
    }

    @Test
    fun migration_repairs_orphaned_relationships_for_existing_data() {
        val dbName = "migration-${UUID.randomUUID()}"
        val dbPath = context.getDatabasePath(dbName).absolutePath
        val supportDb = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        supportDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `identities` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `username` TEXT,
                `publicKey` TEXT,
                `hasSecret` INTEGER NOT NULL,
                `hasPassphrase` INTEGER NOT NULL,
                `primaryCipherText` BLOB,
                `primaryIv` BLOB,
                `passphraseCipherText` BLOB,
                `passphraseIv` BLOB,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `hosts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `label` TEXT NOT NULL,
                `address` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `username` TEXT NOT NULL,
                `identityId` INTEGER NOT NULL,
                `isFavorite` INTEGER NOT NULL,
                `lastUsedAtEpochMillis` INTEGER,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `session_metadata` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `hostId` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `title` TEXT,
                `connectedAtEpochMillis` INTEGER,
                `disconnectedAtEpochMillis` INTEGER,
                `reconnectRequired` INTEGER NOT NULL,
                `lastError` TEXT
            )
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `snippets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT,
                `tagsSerialized` TEXT NOT NULL,
                `hostId` INTEGER,
                `bodyCipherText` BLOB,
                `bodyIv` BLOB,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                `lastRunAtEpochMillis` INTEGER
            )
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `known_host_trust` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `host` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `algorithm` TEXT NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `hostKeyBase64` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_known_host_trust_host_port`
            ON `known_host_trust` (`host`, `port`)
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            INSERT INTO identities (
                id, name, kind, username, publicKey, hasSecret, hasPassphrase,
                primaryCipherText, primaryIv, passphraseCipherText, passphraseIv,
                createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                1, 'Identity', 'IMPORTED_KEY', 'factory', NULL, 0, 0,
                NULL, NULL, NULL, NULL, 1, 1
            )
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            INSERT INTO hosts (
                id, label, address, port, username, identityId, isFavorite,
                lastUsedAtEpochMillis, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES
                (1, 'Linked', 'example.internal', 22, 'factory', 1, 0, NULL, 1, 1),
                (2, 'Orphaned', 'orphan.internal', 22, 'factory', 77, 0, NULL, 1, 1)
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            INSERT INTO session_metadata (
                id, hostId, state, title, connectedAtEpochMillis, disconnectedAtEpochMillis, reconnectRequired, lastError
            ) VALUES
                (1, 1, 'CONNECTED', 'ok', NULL, NULL, 0, NULL),
                (2, 55, 'FAILED', 'orphan', NULL, NULL, 1, 'missing host')
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            INSERT INTO snippets (
                id, title, description, tagsSerialized, hostId, bodyCipherText, bodyIv,
                createdAtEpochMillis, updatedAtEpochMillis, lastRunAtEpochMillis
            ) VALUES
                (1, 'Linked snippet', NULL, '', 1, X'01', X'02', 1, 1, NULL),
                (2, 'Orphaned snippet', NULL, '', 44, X'03', X'04', 1, 1, NULL)
            """.trimIndent(),
        )
        supportDb.version = 1
        supportDb.close()

        val opened = Room.databaseBuilder(context, AtermDatabase::class.java, dbName)
            .addMigrations(AtermDatabase.MIGRATION_1_2, AtermDatabase.MIGRATION_2_3, AtermDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        val orphanedHostIdentity = opened.query(SimpleSQLiteQuery("SELECT identityId FROM hosts WHERE id = 2")).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
        val orphanedSessionCount = opened.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM session_metadata WHERE id = 2")).use {
            it.moveToFirst()
            it.getInt(0)
        }
        val orphanedSnippetHostId = opened.query(SimpleSQLiteQuery("SELECT hostId FROM snippets WHERE id = 2")).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
        val linkedHostAuthKind = opened.query(SimpleSQLiteQuery("SELECT authKind FROM hosts WHERE id = 1")).use {
            it.moveToFirst()
            it.getString(0)
        }
        val orphanedHostAuthKind = opened.query(SimpleSQLiteQuery("SELECT authKind FROM hosts WHERE id = 2")).use {
            it.moveToFirst()
            it.getString(0)
        }

        assertNull(orphanedHostIdentity)
        assertEquals(0, orphanedSessionCount)
        assertNull(orphanedSnippetHostId)
        assertEquals("KEY", linkedHostAuthKind)
        assertEquals("PASSWORD", orphanedHostAuthKind)

        opened.close()
        context.deleteDatabase(dbName)
    }

    private object FailingSecretFieldCipher : SecretFieldCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): EncryptedPayload {
            throw IllegalStateException("encrypt failure")
        }

        override fun decrypt(payload: EncryptedPayload, associatedData: ByteArray?): ByteArray {
            error("decrypt should not be called")
        }
    }
}
