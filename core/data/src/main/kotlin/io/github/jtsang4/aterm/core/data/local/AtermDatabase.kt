package io.github.jtsang4.aterm.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false,
)
abstract class AtermDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun identityDao(): IdentityDao
    abstract fun snippetDao(): SnippetDao
    abstract fun sessionMetadataDao(): SessionMetadataDao
    abstract fun knownHostTrustDao(): KnownHostTrustDao

    companion object {
        fun build(context: Context): AtermDatabase = Room.databaseBuilder(
            context.applicationContext,
            AtermDatabase::class.java,
            "aterm.db",
        ).build()
    }
}
