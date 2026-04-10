package io.github.jtsang4.aterm.core.domain.repository

import io.github.jtsang4.aterm.core.domain.model.SessionMetadata
import kotlinx.coroutines.flow.Flow

interface SessionMetadataRepository {
    fun observeSessions(): Flow<List<SessionMetadata>>
    suspend fun getSession(id: Long): SessionMetadata?
    suspend fun upsert(sessionMetadata: SessionMetadata): SessionMetadata
    suspend fun deleteSession(id: Long)
    suspend fun clear()
}
