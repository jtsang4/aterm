package io.github.jtsang4.aterm.core.data.repository

import io.github.jtsang4.aterm.core.data.local.dao.SessionMetadataDao
import io.github.jtsang4.aterm.core.data.local.mapper.toDomain
import io.github.jtsang4.aterm.core.data.local.mapper.toEntity
import io.github.jtsang4.aterm.core.domain.model.SessionMetadata
import io.github.jtsang4.aterm.core.domain.repository.SessionMetadataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSessionMetadataRepository(
    private val sessionMetadataDao: SessionMetadataDao,
) : SessionMetadataRepository {
    override fun observeSessions(): Flow<List<SessionMetadata>> =
        sessionMetadataDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getSession(id: Long): SessionMetadata? = sessionMetadataDao.getById(id)?.toDomain()

    override suspend fun upsert(sessionMetadata: SessionMetadata): SessionMetadata {
        val entity = sessionMetadata.toEntity()
        val id = if (sessionMetadata.id == 0L) {
            sessionMetadataDao.insert(entity.copy(id = 0))
        } else {
            sessionMetadataDao.update(entity)
            sessionMetadata.id
        }
        return requireNotNull(getSession(id)) { "Session metadata $id was not persisted." }
    }

    override suspend fun deleteSession(id: Long) {
        sessionMetadataDao.deleteById(id)
    }

    override suspend fun clear() {
        sessionMetadataDao.clear()
    }
}
