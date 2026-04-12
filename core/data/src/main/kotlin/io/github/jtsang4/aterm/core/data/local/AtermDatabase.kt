package io.github.jtsang4.aterm.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.jtsang4.aterm.core.data.local.dao.HostDao
import io.github.jtsang4.aterm.core.data.local.dao.IdentityDao
import io.github.jtsang4.aterm.core.data.local.dao.KnownHostTrustDao
import io.github.jtsang4.aterm.core.data.local.dao.SessionMetadataDao
import io.github.jtsang4.aterm.core.data.local.dao.SnippetDao
import io.github.jtsang4.aterm.core.data.local.dao.SnippetExecutionHistoryDao
import io.github.jtsang4.aterm.core.data.local.entity.HostEntity
import io.github.jtsang4.aterm.core.data.local.entity.IdentityEntity
import io.github.jtsang4.aterm.core.data.local.entity.KnownHostTrustEntity
import io.github.jtsang4.aterm.core.data.local.entity.SessionMetadataEntity
import io.github.jtsang4.aterm.core.data.local.entity.SnippetExecutionHistoryEntity
import io.github.jtsang4.aterm.core.data.local.entity.SnippetEntity

@Database(
    entities = [
        HostEntity::class,
        IdentityEntity::class,
        SnippetEntity::class,
        SnippetExecutionHistoryEntity::class,
        SessionMetadataEntity::class,
        KnownHostTrustEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AtermDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun identityDao(): IdentityDao
    abstract fun snippetDao(): SnippetDao
    abstract fun snippetExecutionHistoryDao(): SnippetExecutionHistoryDao
    abstract fun sessionMetadataDao(): SessionMetadataDao
    abstract fun knownHostTrustDao(): KnownHostTrustDao

    companion object {
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_snippet_execution_history_snippetId`
                    ON `snippet_execution_history` (`snippetId`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_snippet_execution_history_executedAtEpochMillis`
                    ON `snippet_execution_history` (`executedAtEpochMillis`)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `snippets_new` (
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
                db.execSQL(
                    """
                    INSERT INTO `snippets_new` (
                        `id`,
                        `title`,
                        `description`,
                        `tagsSerialized`,
                        `hostId`,
                        `savedTarget`,
                        `bodyCipherText`,
                        `bodyIv`,
                        `createdAtEpochMillis`,
                        `updatedAtEpochMillis`,
                        `lastRunAtEpochMillis`
                    )
                    SELECT
                        `id`,
                        `title`,
                        `description`,
                        `tagsSerialized`,
                        `hostId`,
                        CASE
                            WHEN `hostId` IS NOT NULL THEN 'SAVED_HOST'
                            ELSE 'ACTIVE_SESSION'
                        END,
                        `bodyCipherText`,
                        `bodyIv`,
                        `createdAtEpochMillis`,
                        `updatedAtEpochMillis`,
                        `lastRunAtEpochMillis`
                    FROM `snippets`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `snippets`")
                db.execSQL("ALTER TABLE `snippets_new` RENAME TO `snippets`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_snippets_hostId` ON `snippets` (`hostId`)")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `hosts`
                    ADD COLUMN `authKind` TEXT NOT NULL DEFAULT 'UNKNOWN'
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `hosts`
                    SET `authKind` = CASE
                        WHEN `identityId` IS NULL THEN 'UNKNOWN'
                        WHEN `identityId` IS NOT NULL AND EXISTS(
                            SELECT 1
                            FROM `identities`
                            WHERE `identities`.`id` = `hosts`.`identityId`
                                AND `identities`.`kind` != 'PASSWORD'
                        ) THEN 'KEY'
                        WHEN `identityId` IS NOT NULL AND EXISTS(
                            SELECT 1
                            FROM `identities`
                            WHERE `identities`.`id` = `hosts`.`identityId`
                                AND `identities`.`kind` = 'PASSWORD'
                        ) THEN 'PASSWORD'
                        ELSE 'UNKNOWN'
                    END
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `identities`
                    ADD COLUMN `secretStorageState` TEXT NOT NULL DEFAULT 'AVAILABLE'
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    ALTER TABLE `identities`
                    ADD COLUMN `passphraseStorageState` TEXT NOT NULL DEFAULT 'AVAILABLE'
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `identities`
                    SET `secretStorageState` = CASE
                        WHEN `hasSecret` = 1 THEN 'AVAILABLE'
                        ELSE 'MISSING'
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `identities`
                    SET `passphraseStorageState` = CASE
                        WHEN `hasPassphrase` = 1 THEN 'AVAILABLE'
                        ELSE 'MISSING'
                    END
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hosts_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `label` TEXT NOT NULL,
                        `address` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `username` TEXT NOT NULL,
                        `identityId` INTEGER,
                        `isFavorite` INTEGER NOT NULL,
                        `lastUsedAtEpochMillis` INTEGER,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`identityId`) REFERENCES `identities`(`id`) ON UPDATE CASCADE ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `hosts_new` (
                        `id`,
                        `label`,
                        `address`,
                        `port`,
                        `username`,
                        `identityId`,
                        `isFavorite`,
                        `lastUsedAtEpochMillis`,
                        `createdAtEpochMillis`,
                        `updatedAtEpochMillis`
                    )
                    SELECT
                        `id`,
                        `label`,
                        `address`,
                        `port`,
                        `username`,
                        CASE
                            WHEN EXISTS(SELECT 1 FROM `identities` WHERE `identities`.`id` = `hosts`.`identityId`)
                                THEN `identityId`
                            ELSE NULL
                        END,
                        `isFavorite`,
                        `lastUsedAtEpochMillis`,
                        `createdAtEpochMillis`,
                        `updatedAtEpochMillis`
                    FROM `hosts`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `hosts`")
                db.execSQL("ALTER TABLE `hosts_new` RENAME TO `hosts`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hosts_identityId` ON `hosts` (`identityId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_metadata_new` (
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
                db.execSQL(
                    """
                    INSERT INTO `session_metadata_new` (
                        `id`,
                        `hostId`,
                        `state`,
                        `title`,
                        `connectedAtEpochMillis`,
                        `disconnectedAtEpochMillis`,
                        `reconnectRequired`,
                        `lastError`
                    )
                    SELECT
                        `id`,
                        `hostId`,
                        `state`,
                        `title`,
                        `connectedAtEpochMillis`,
                        `disconnectedAtEpochMillis`,
                        `reconnectRequired`,
                        `lastError`
                    FROM `session_metadata`
                    WHERE EXISTS(SELECT 1 FROM `hosts` WHERE `hosts`.`id` = `session_metadata`.`hostId`)
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `session_metadata`")
                db.execSQL("ALTER TABLE `session_metadata_new` RENAME TO `session_metadata`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_metadata_hostId` ON `session_metadata` (`hostId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `snippets_new` (
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
                db.execSQL(
                    """
                    INSERT INTO `snippets_new` (
                        `id`,
                        `title`,
                        `description`,
                        `tagsSerialized`,
                        `hostId`,
                        `savedTarget`,
                        `bodyCipherText`,
                        `bodyIv`,
                        `createdAtEpochMillis`,
                        `updatedAtEpochMillis`,
                        `lastRunAtEpochMillis`
                    )
                    SELECT
                        `id`,
                        `title`,
                        `description`,
                        `tagsSerialized`,
                        CASE
                            WHEN `hostId` IS NOT NULL
                                AND EXISTS(SELECT 1 FROM `hosts` WHERE `hosts`.`id` = `snippets`.`hostId`)
                                THEN `hostId`
                            ELSE NULL
                        END,
                        CASE
                            WHEN `hostId` IS NOT NULL
                                AND EXISTS(SELECT 1 FROM `hosts` WHERE `hosts`.`id` = `snippets`.`hostId`)
                                THEN 'SAVED_HOST'
                            ELSE 'ACTIVE_SESSION'
                        END,
                        `bodyCipherText`,
                        `bodyIv`,
                        `createdAtEpochMillis`,
                        `updatedAtEpochMillis`,
                        `lastRunAtEpochMillis`
                    FROM `snippets`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `snippets`")
                db.execSQL("ALTER TABLE `snippets_new` RENAME TO `snippets`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_snippets_hostId` ON `snippets` (`hostId`)")
            }
        }

        fun build(context: Context): AtermDatabase = Room.databaseBuilder(
            context.applicationContext,
            AtermDatabase::class.java,
            "aterm.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    }
}
