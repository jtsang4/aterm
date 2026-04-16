package io.github.jtsang4.aterm.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.core.data.local.AtermDatabase
import io.github.jtsang4.aterm.core.data.local.mapper.toEntity
import io.github.jtsang4.aterm.core.data.preferences.PreferencesSettingsRepository
import io.github.jtsang4.aterm.core.data.preferences.createUserPreferencesDataStore
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
import io.github.jtsang4.aterm.core.domain.model.SecretStorageState
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionRecordInput
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionTargetKind
import io.github.jtsang4.aterm.core.domain.model.SnippetSavedTarget
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.core.security.crypto.EncryptedPayload
import io.github.jtsang4.aterm.core.security.crypto.KeystoreAesGcmCipher
import io.github.jtsang4.aterm.core.security.crypto.SecretFieldCipher
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        hostRepository = RoomHostRepository(
            database = database,
            hostDao = database.hostDao(),
            snippetDao = database.snippetDao(),
        )
        identityRepository = RoomIdentityRepository(database, database.identityDao(), cipher)
        snippetRepository = RoomSnippetRepository(
            database,
            database.snippetDao(),
            database.snippetExecutionHistoryDao(),
            cipher,
        )
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
                .copy(id = 0, kind = IdentityKind.PASSWORD, hasPassphrase = false),
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
    fun migration_drops_legacy_identity_username_and_keeps_hosts_authoritative() {
        val dbName = "identity-username-cleanup-${UUID.randomUUID()}"
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
                `secretStorageState` TEXT NOT NULL,
                `passphraseStorageState` TEXT NOT NULL,
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
                `identityId` INTEGER,
                `authKind` TEXT NOT NULL,
                `isFavorite` INTEGER NOT NULL,
                `lastUsedAtEpochMillis` INTEGER,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                FOREIGN KEY(`identityId`) REFERENCES `identities`(`id`) ON UPDATE CASCADE ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        supportDb.execSQL("CREATE INDEX IF NOT EXISTS `index_hosts_identityId` ON `hosts` (`identityId`)")
        supportDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `snippets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT,
                `tagsSerialized` TEXT NOT NULL,
                `hostId` INTEGER,
                `savedTarget` TEXT NOT NULL,
                `bodyCipherText` BLOB,
                `bodyIv` BLOB,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                `lastRunAtEpochMillis` INTEGER,
                FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) ON UPDATE CASCADE ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        supportDb.execSQL("CREATE INDEX IF NOT EXISTS `index_snippets_hostId` ON `snippets` (`hostId`)")
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
                `lastError` TEXT,
                FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        supportDb.execSQL("CREATE INDEX IF NOT EXISTS `index_session_metadata_hostId` ON `session_metadata` (`hostId`)")
        supportDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `snippet_execution_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `snippetId` INTEGER,
                `snippetTitle` TEXT NOT NULL,
                `targetKind` TEXT NOT NULL,
                `targetLabel` TEXT NOT NULL,
                `targetDetail` TEXT NOT NULL,
                `executedAtEpochMillis` INTEGER NOT NULL,
                FOREIGN KEY(`snippetId`) REFERENCES `snippets`(`id`) ON UPDATE CASCADE ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        supportDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_snippet_execution_history_snippetId` ON `snippet_execution_history` (`snippetId`)",
        )
        supportDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_snippet_execution_history_executedAtEpochMillis` ON `snippet_execution_history` (`executedAtEpochMillis`)",
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
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_known_host_trust_host_port` ON `known_host_trust` (`host`, `port`)",
        )
        supportDb.execSQL(
            """
            INSERT INTO identities (
                id, name, kind, username, publicKey, hasSecret, hasPassphrase,
                secretStorageState, passphraseStorageState, primaryCipherText, primaryIv,
                passphraseCipherText, passphraseIv, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                1, 'Legacy password identity', 'PASSWORD', 'legacy-user', NULL, 1, 0,
                'AVAILABLE', 'MISSING', X'01', X'02', NULL, NULL, 1, 1
            )
            """.trimIndent(),
        )
        supportDb.execSQL(
            """
            INSERT INTO hosts (
                id, label, address, port, username, identityId, authKind, isFavorite,
                lastUsedAtEpochMillis, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                1, 'Legacy host', 'legacy.example', 22, 'host-user', 1, 'PASSWORD', 0, NULL, 1, 1
            )
            """.trimIndent(),
        )
        supportDb.version = 6
        supportDb.close()

        val opened = Room.databaseBuilder(context, AtermDatabase::class.java, dbName)
            .addMigrations(AtermDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

        val identityColumns = opened.query(SimpleSQLiteQuery("PRAGMA table_info(`identities`)")).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }
        val hostUsername = opened.query(SimpleSQLiteQuery("SELECT username FROM hosts WHERE id = 1")).use {
            it.moveToFirst()
            it.getString(0)
        }
        val persistedIdentity = runBlocking { opened.identityDao().getById(1) }

        assertFalse(identityColumns.contains("username"))
        assertEquals("host-user", hostUsername)
        assertEquals("Legacy password identity", persistedIdentity?.name)
        assertEquals("PASSWORD", persistedIdentity?.kind)

        opened.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun replacing_encrypted_key_with_unencrypted_material_clears_bogus_passphrase_state() = runTest {
        val created = identityRepository.upsert(
            sampleIdentity().copy(id = 0, kind = IdentityKind.IMPORTED_KEY, hasPassphrase = true),
            IdentitySecretMaterial(
                primarySecret = "encrypted-private-key",
                passphrase = "correct-passphrase",
            ),
        )

        val updated = identityRepository.upsert(
            created.copy(
                publicKey = "ssh-rsa TEST_REPLACEMENT_PUBLIC_KEY",
                hasPassphrase = false,
                passphraseStorageState = SecretStorageState.MISSING,
            ),
            IdentitySecretMaterial(
                primarySecret = "unencrypted-replacement-key",
                passphrase = "stray-passphrase",
            ),
        )

        val persistedIdentity = identityRepository.getIdentity(updated.id)
        val persistedSecrets = identityRepository.getSecretMaterial(updated.id)
        val rawEntity = database.identityDao().getById(updated.id)

        assertEquals(false, persistedIdentity?.hasPassphrase)
        assertEquals(SecretStorageState.MISSING, persistedIdentity?.passphraseStorageState)
        assertEquals("unencrypted-replacement-key", persistedSecrets?.primarySecret)
        assertEquals(null, persistedSecrets?.passphrase)
        assertNull(rawEntity?.passphraseCipherText)
        assertNull(rawEntity?.passphraseIv)
    }

    @Test
    fun updating_only_passphrase_preserves_existing_key_material_and_identity_row() = runTest {
        val created = identityRepository.upsert(
            sampleIdentity().copy(id = 0, kind = IdentityKind.IMPORTED_KEY, hasPassphrase = true),
            IdentitySecretMaterial(
                primarySecret = "encrypted-private-key",
                passphrase = "initial-passphrase",
            ),
        )

        val updated = identityRepository.upsert(
            created.copy(
                passphraseStorageState = SecretStorageState.AVAILABLE,
            ),
            IdentitySecretMaterial(passphrase = "updated-passphrase"),
        )

        val persistedIdentity = identityRepository.getIdentity(updated.id)
        val persistedSecrets = identityRepository.getSecretMaterial(updated.id)
        val rawEntity = database.identityDao().getById(updated.id)

        assertEquals(created.id, updated.id)
        assertEquals(created.publicKey, persistedIdentity?.publicKey)
        assertEquals(SecretStorageState.AVAILABLE, persistedIdentity?.passphraseStorageState)
        assertEquals("encrypted-private-key", persistedSecrets?.primarySecret)
        assertEquals("updated-passphrase", persistedSecrets?.passphrase)
        assertNotNull(rawEntity?.primaryCipherText)
        assertNotNull(rawEntity?.passphraseCipherText)
    }

    @Test
    fun clearing_saved_passphrase_keeps_key_material_but_marks_identity_non_ready() = runTest {
        val created = identityRepository.upsert(
            sampleIdentity().copy(id = 0, kind = IdentityKind.IMPORTED_KEY, hasPassphrase = true),
            IdentitySecretMaterial(
                primarySecret = "encrypted-private-key",
                passphrase = "stored-passphrase",
            ),
        )

        val updated = identityRepository.upsert(
            created.copy(
                passphraseStorageState = SecretStorageState.MISSING,
            ),
            secrets = null,
        )

        val persistedIdentity = identityRepository.getIdentity(updated.id)
        val persistedSecrets = identityRepository.getSecretMaterial(updated.id)
        val rawEntity = database.identityDao().getById(updated.id)

        assertEquals(created.id, updated.id)
        assertEquals(true, persistedIdentity?.hasPassphrase)
        assertEquals(SecretStorageState.MISSING, persistedIdentity?.passphraseStorageState)
        assertFalse(persistedIdentity?.isAuthenticationReady == true)
        assertEquals("encrypted-private-key", persistedSecrets?.primarySecret)
        assertNull(persistedSecrets?.passphrase)
        assertNull(rawEntity?.passphraseCipherText)
        assertNull(rawEntity?.passphraseIv)
    }

    @Test
    fun replacing_encrypted_key_without_saving_new_passphrase_clears_stale_ciphertext_and_stays_non_ready() = runTest {
        val created = identityRepository.upsert(
            sampleIdentity().copy(id = 0, kind = IdentityKind.IMPORTED_KEY, hasPassphrase = true),
            IdentitySecretMaterial(
                primarySecret = "encrypted-private-key",
                passphrase = "stored-passphrase",
            ),
        )

        val updated = identityRepository.upsert(
            created.copy(
                publicKey = "ssh-rsa TEST_REPLACEMENT_ENCRYPTED_KEY",
                hasPassphrase = true,
                passphraseStorageState = SecretStorageState.MISSING,
            ),
            IdentitySecretMaterial(primarySecret = "replacement-encrypted-private-key"),
        )

        val persistedIdentity = identityRepository.getIdentity(updated.id)
        val persistedSecrets = identityRepository.getSecretMaterial(updated.id)
        val rawEntity = database.identityDao().getById(updated.id)

        assertEquals(created.id, updated.id)
        assertEquals(true, persistedIdentity?.hasPassphrase)
        assertEquals(SecretStorageState.MISSING, persistedIdentity?.passphraseStorageState)
        assertFalse(persistedIdentity?.isAuthenticationReady == true)
        assertEquals("replacement-encrypted-private-key", persistedSecrets?.primarySecret)
        assertNull(persistedSecrets?.passphrase)
        assertNull(rawEntity?.passphraseCipherText)
        assertNull(rawEntity?.passphraseIv)
    }

    @Test
    fun failed_passphrase_only_update_leaves_existing_identity_row_unchanged() = runTest {
        val created = identityRepository.upsert(
            sampleIdentity().copy(id = 0, kind = IdentityKind.IMPORTED_KEY, hasPassphrase = true),
            IdentitySecretMaterial(
                primarySecret = "encrypted-private-key",
                passphrase = "stored-passphrase",
            ),
        )
        val originalEntity = database.identityDao().getById(created.id)

        try {
            RoomIdentityRepository(
                database = database,
                identityDao = database.identityDao(),
                fieldCipher = object : SecretFieldCipher {
                    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): EncryptedPayload {
                        throw IllegalStateException("encrypt failure")
                    }

                    override fun decrypt(payload: EncryptedPayload, associatedData: ByteArray?): ByteArray =
                        cipher.decrypt(payload, associatedData)
                },
            ).upsert(
                created.copy(passphraseStorageState = SecretStorageState.AVAILABLE),
                IdentitySecretMaterial(passphrase = "new-passphrase"),
            )
            error("Expected encryption failure")
        } catch (expected: IllegalStateException) {
            assertEquals("encrypt failure", expected.message)
        }

        val persistedIdentity = identityRepository.getIdentity(created.id)
        val persistedSecrets = identityRepository.getSecretMaterial(created.id)
        val rawEntity = database.identityDao().getById(created.id)

        assertEquals(SecretStorageState.AVAILABLE, persistedIdentity?.passphraseStorageState)
        assertEquals("encrypted-private-key", persistedSecrets?.primarySecret)
        assertEquals("stored-passphrase", persistedSecrets?.passphrase)
        assertTrue(rawEntity?.passphraseCipherText?.contentEquals(originalEntity?.passphraseCipherText) == true)
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
        snippetRepository.markExecuted(
            snippet.id,
            SnippetExecutionRecordInput(
                snippetId = snippet.id,
                targetKind = SnippetExecutionTargetKind.SAVED_HOST,
                targetLabel = host.label,
                targetDetail = host.endpoint,
                executedAt = Instant.parse("2026-04-10T02:00:00Z"),
            ),
        )

        val persistedSnippet = snippetRepository.getSnippet(snippet.id)
        val body = snippetRepository.getBody(snippet.id)
        val rawEntity = database.snippetDao().getById(snippet.id)
        val history = snippetRepository.observeExecutionHistory().first()

        assertEquals(host.id, persistedSnippet?.hostId)
        assertEquals(SnippetSavedTarget.SAVED_HOST, persistedSnippet?.savedTarget)
        assertEquals("sudo systemctl restart aterm", body)
        assertEquals(1, history.size)
        assertEquals(snippet.id, history.single().snippetId)
        assertEquals("Primary host", history.single().targetLabel)
        assertEquals("example.internal:22", history.single().targetDetail)
        assertNotNull(rawEntity?.bodyCipherText)
        assertFalse(
            rawEntity!!.bodyCipherText!!.contentEquals(
                "sudo systemctl restart aterm".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun snippet_execution_history_survives_relaunch_and_keeps_deleted_entries_stable() = runTest {
        val persistentDbName = "snippet-history-${UUID.randomUUID()}"
        database.close()
        dataStoreFile.delete()

        val persistentDatabase = Room.databaseBuilder(context, AtermDatabase::class.java, persistentDbName)
            .allowMainThreadQueries()
            .build()
        val persistentCipher = KeystoreAesGcmCipher("repo.test.${UUID.randomUUID()}")
        val persistentSnippetRepository = RoomSnippetRepository(
            persistentDatabase,
            persistentDatabase.snippetDao(),
            persistentDatabase.snippetExecutionHistoryDao(),
            persistentCipher,
        )
        val persistentIdentityRepository = RoomIdentityRepository(
            persistentDatabase,
            persistentDatabase.identityDao(),
            persistentCipher,
        )
        val persistentHostRepository = RoomHostRepository(
            database = persistentDatabase,
            hostDao = persistentDatabase.hostDao(),
            snippetDao = persistentDatabase.snippetDao(),
        )

        try {
            val identity = persistentIdentityRepository.upsert(
                sampleIdentity().copy(id = 0),
                IdentitySecretMaterial(primarySecret = "pw"),
            )
            val host = persistentHostRepository.upsert(sampleHost(id = 0, identityId = identity.id))
            val snippet = persistentSnippetRepository.upsert(
                sampleSnippet(id = 0, hostId = host.id),
                body = "echo before",
            )
            persistentSnippetRepository.markExecuted(
                snippet.id,
                SnippetExecutionRecordInput(
                    snippetId = snippet.id,
                    targetKind = SnippetExecutionTargetKind.SAVED_HOST,
                    targetLabel = host.label,
                    targetDetail = host.endpoint,
                    executedAt = Instant.parse("2026-04-10T03:00:00Z"),
                ),
            )
            persistentSnippetRepository.deleteSnippet(snippet.id)

            persistentDatabase.close()

            val reopenedDatabase = Room.databaseBuilder(context, AtermDatabase::class.java, persistentDbName)
                .allowMainThreadQueries()
                .build()
            val reopenedCipher = KeystoreAesGcmCipher("repo.test.${UUID.randomUUID()}")
            try {
                val reopenedRepository = RoomSnippetRepository(
                    reopenedDatabase,
                    reopenedDatabase.snippetDao(),
                    reopenedDatabase.snippetExecutionHistoryDao(),
                    reopenedCipher,
                )
                val history = reopenedRepository.observeExecutionHistory().first()

                assertEquals(1, history.size)
                assertNull(history.single().snippetId)
                assertEquals("Restart service", history.single().snippetTitle)
                assertEquals("Primary host", history.single().targetLabel)
                assertEquals("example.internal:22", history.single().targetDetail)
            } finally {
                reopenedDatabase.close()
                reopenedCipher.deleteKey()
            }
        } finally {
            persistentDbName.let(context::deleteDatabase)
            persistentCipher.deleteKey()
        }
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
            snippetExecutionHistoryDao = database.snippetExecutionHistoryDao(),
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
    fun managed_user_preferences_data_store_releases_shared_instances_only_after_last_close() = runTest {
        val fileName = "managed-prefs-${UUID.randomUUID()}.preferences_pb"
        val first = createUserPreferencesDataStore(context, fileName)
        val second = createUserPreferencesDataStore(context, fileName)
        val firstRepository = PreferencesSettingsRepository(first.dataStore)
        val secondRepository = PreferencesSettingsRepository(second.dataStore)

        try {
            firstRepository.updateTheme(ThemePreference.DARK)
            assertEquals(ThemePreference.DARK, secondRepository.observePreferences().first().themePreference)

            first.close()

            secondRepository.updateTerminalFontScale(1.4f)
            assertEquals(1.4f, secondRepository.observePreferences().first().terminalFontScale)

            second.clear()
            val cleared = secondRepository.observePreferences().first()
            assertEquals(ThemePreference.SYSTEM, cleared.themePreference)
            assertEquals(1f, cleared.terminalFontScale)
            assertEquals(FeatureArea.Hosts, cleared.lastViewedArea)
        } finally {
            second.close()
            context.preferencesDataStoreFile(fileName).delete()
        }
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
        assertEquals(SnippetSavedTarget.SAVED_HOST, snippetRepository.getSnippet(snippet.id)?.savedTarget)
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
            .addMigrations(
                AtermDatabase.MIGRATION_1_2,
                AtermDatabase.MIGRATION_2_3,
                AtermDatabase.MIGRATION_3_4,
                AtermDatabase.MIGRATION_4_5,
                AtermDatabase.MIGRATION_5_6,
                AtermDatabase.MIGRATION_6_7,
            )
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
        val linkedSnippetSavedTarget = opened.query(SimpleSQLiteQuery("SELECT savedTarget FROM snippets WHERE id = 1")).use {
            it.moveToFirst()
            it.getString(0)
        }
        val orphanedSnippetSavedTarget = opened.query(SimpleSQLiteQuery("SELECT savedTarget FROM snippets WHERE id = 2")).use {
            it.moveToFirst()
            it.getString(0)
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
        assertEquals("SAVED_HOST", linkedSnippetSavedTarget)
        assertEquals("SAVED_HOST", orphanedSnippetSavedTarget)
        assertEquals("KEY", linkedHostAuthKind)
        assertEquals("UNKNOWN", orphanedHostAuthKind)

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
