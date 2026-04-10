package io.github.jtsang4.aterm.core.domain.repository

import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun observeIdentities(): Flow<List<Identity>>
    suspend fun getIdentity(id: Long): Identity?
    suspend fun upsert(identity: Identity, secrets: IdentitySecretMaterial? = null): Identity
    suspend fun getSecretMaterial(id: Long): IdentitySecretMaterial?
    suspend fun deleteIdentity(id: Long)
}
