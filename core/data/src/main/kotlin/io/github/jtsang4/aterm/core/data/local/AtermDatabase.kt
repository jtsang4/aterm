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
import io.github.jtsang4.aterm.core.data.local.entity.HostEntity
import io.github.jtsang4.aterm.core.data.local.entity.IdentityEntity
import io.github.jtsang4.aterm.core.data.local.entity.KnownHostTrustEntity
import io.github.jtsang4.aterm.core.data.local.entity.SessionMetadataEntity
import io.github.jtsang4.aterm.core.data.local.entity.SnippetEntity

@Database(
    entities = [
        HostEntity::class,
        IdentityEntity::class,
        SnippetEntity::class,
        SessionMetadataEntity::class,
        KnownHostTrustEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AtermDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun identityDao(): IdentityDao
    abstract fun snippetDao(): SnippetDao
    abstract fun sessionMetadataDao(): SessionMetadataDao
    abstract fun knownHostTrustDao(): KnownHostTrustDao

    companion object {
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
        ).addMigrations(MIGRATION_1_2)
            .build()
    }
}
