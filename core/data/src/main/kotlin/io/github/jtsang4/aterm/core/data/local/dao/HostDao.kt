package io.github.jtsang4.aterm.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.jtsang4.aterm.core.data.local.entity.HostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY isFavorite DESC, label ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE isFavorite = 1 ORDER BY label ASC")
    fun observeFavorites(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE lastUsedAtEpochMillis IS NOT NULL ORDER BY lastUsedAtEpochMillis DESC, label ASC")
    fun observeRecents(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getById(id: Long): HostEntity?

    @Insert
    suspend fun insert(entity: HostEntity): Long

    @Update
    suspend fun update(entity: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
