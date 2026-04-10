package io.github.jtsang4.aterm.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.jtsang4.aterm.core.data.local.entity.KnownHostTrustEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownHostTrustDao {
    @Query("SELECT * FROM known_host_trust ORDER BY host ASC, port ASC")
    fun observeAll(): Flow<List<KnownHostTrustEntity>>

    @Query("SELECT * FROM known_host_trust WHERE host = :host AND port = :port")
    suspend fun findByEndpoint(host: String, port: Int): KnownHostTrustEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KnownHostTrustEntity): Long

    @Query("DELETE FROM known_host_trust WHERE host = :host AND port = :port")
    suspend fun deleteByEndpoint(host: String, port: Int)
}
