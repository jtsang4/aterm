package io.github.jtsang4.aterm.core.data.repository

import androidx.room.withTransaction
import io.github.jtsang4.aterm.core.data.local.AtermDatabase
import io.github.jtsang4.aterm.core.data.local.dao.HostDao
import io.github.jtsang4.aterm.core.data.local.dao.SnippetDao
import io.github.jtsang4.aterm.core.data.local.mapper.toDomain
import io.github.jtsang4.aterm.core.data.local.mapper.toEntity
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomHostRepository(
    private val database: AtermDatabase,
    private val hostDao: HostDao,
    private val snippetDao: SnippetDao,
) : HostRepository {
    override fun observeHosts(): Flow<List<Host>> = hostDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun getHost(id: Long): Host? = hostDao.getById(id)?.toDomain()

    override suspend fun upsert(host: Host): Host {
        val entity = host.toEntity()
        val id = if (host.id == 0L) {
            hostDao.insert(entity.copy(id = 0))
        } else {
            hostDao.update(entity)
            host.id
        }
        return requireNotNull(getHost(id)) { "Host $id was not persisted." }
    }

    override suspend fun markUsed(id: Long, usedAt: Instant) {
        val existing = requireNotNull(hostDao.getById(id)) { "Host $id was not found." }
        hostDao.update(existing.copy(lastUsedAtEpochMillis = usedAt.toEpochMilli()))
    }

    override suspend fun deleteHost(id: Long) {
        database.withTransaction {
            snippetDao.getSavedHostTargeting(id).forEach { snippet ->
                snippetDao.update(snippet.copy(hostId = null))
            }
            hostDao.deleteById(id)
        }
    }
}
