package io.github.jtsang4.aterm.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.jtsang4.aterm.core.data.local.entity.SnippetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY title ASC")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun getById(id: Long): SnippetEntity?

    @Query("SELECT * FROM snippets WHERE hostId = :hostId AND savedTarget = 'SAVED_HOST'")
    suspend fun getSavedHostTargeting(hostId: Long): List<SnippetEntity>

    @Insert
    suspend fun insert(entity: SnippetEntity): Long

    @Update
    suspend fun update(entity: SnippetEntity)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
