package io.github.jtsang4.aterm.core.domain.repository

import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionHistoryEntry
import io.github.jtsang4.aterm.core.domain.model.SnippetExecutionRecordInput
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface SnippetRepository {
    fun observeSnippets(): Flow<List<Snippet>>
    fun observeExecutionHistory(): Flow<List<SnippetExecutionHistoryEntry>>
    suspend fun getSnippet(id: Long): Snippet?
    suspend fun upsert(snippet: Snippet, body: String): Snippet
    suspend fun getBody(id: Long): String?
    suspend fun markExecuted(
        id: Long,
        execution: SnippetExecutionRecordInput,
        executedAt: Instant = execution.executedAt,
    )
    suspend fun deleteSnippet(id: Long)
}
