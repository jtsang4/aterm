package io.github.jtsang4.aterm.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.jtsang4.aterm.core.data.local.entity.SnippetExecutionHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SnippetExecutionHistoryDao {
    @Query(
        """
        SELECT *
        FROM snippet_execution_history
        ORDER BY executedAtEpochMillis DESC, id DESC
        """,
    )
    fun observeAll(): Flow<List<SnippetExecutionHistoryEntity>>

    @Insert
    suspend fun insert(entity: SnippetExecutionHistoryEntity): Long
}
