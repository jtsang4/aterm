package io.github.jtsang4.aterm.core.domain.repository

import io.github.jtsang4.aterm.core.domain.model.Host
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface HostRepository {
    fun observeHosts(): Flow<List<Host>>
    suspend fun getHost(id: Long): Host?
    suspend fun upsert(host: Host): Host
    suspend fun markUsed(id: Long, usedAt: Instant = Instant.now())
    suspend fun deleteHost(id: Long)
}
