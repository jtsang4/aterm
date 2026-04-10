package io.github.jtsang4.aterm.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.jtsang4.aterm.core.data.local.entity.SessionMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionMetadataDao {
    @Query("SELECT * FROM session_metadata ORDER BY id ASC")
    fun observeAll(): Flow<List<SessionMetadataEntity>>

    @Query("SELECT * FROM session_metadata WHERE id = :id")
    suspend fun getById(id: Long): SessionMetadataEntity?

    @Insert
    suspend fun insert(entity: SessionMetadataEntity): Long

    @Update
    suspend fun update(entity: SessionMetadataEntity)

    @Query("DELETE FROM session_metadata WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM session_metadata")
    suspend fun clear()
}
