package io.github.jtsang4.aterm.core.domain.repository

import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import kotlinx.coroutines.flow.Flow

interface KnownHostTrustRepository {
    fun observeTrustedHosts(): Flow<List<KnownHostTrust>>
    suspend fun findTrustedHost(host: String, port: Int): KnownHostTrust?
    suspend fun upsert(trust: KnownHostTrust): KnownHostTrust
    suspend fun deleteByEndpoint(host: String, port: Int)
}
