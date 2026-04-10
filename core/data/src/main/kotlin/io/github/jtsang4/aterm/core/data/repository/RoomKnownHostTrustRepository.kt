package io.github.jtsang4.aterm.core.data.repository

import io.github.jtsang4.aterm.core.data.local.dao.KnownHostTrustDao
import io.github.jtsang4.aterm.core.data.local.mapper.toDomain
import io.github.jtsang4.aterm.core.data.local.mapper.toEntity
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomKnownHostTrustRepository(
    private val knownHostTrustDao: KnownHostTrustDao,
) : KnownHostTrustRepository {
    override fun observeTrustedHosts(): Flow<List<KnownHostTrust>> =
        knownHostTrustDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun findTrustedHost(host: String, port: Int): KnownHostTrust? =
        knownHostTrustDao.findByEndpoint(host, port)?.toDomain()

    override suspend fun upsert(trust: KnownHostTrust): KnownHostTrust {
        knownHostTrustDao.upsert(trust.toEntity())
        return requireNotNull(findTrustedHost(trust.host, trust.port)) {
            "Known-host trust for ${trust.endpointKey} was not persisted."
        }
    }

    override suspend fun deleteByEndpoint(host: String, port: Int) {
        knownHostTrustDao.deleteByEndpoint(host, port)
    }
}
