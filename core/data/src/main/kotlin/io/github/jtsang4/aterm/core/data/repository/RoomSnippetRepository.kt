package io.github.jtsang4.aterm.core.data.repository

import io.github.jtsang4.aterm.core.data.local.dao.SnippetDao
import io.github.jtsang4.aterm.core.data.local.mapper.toDomain
import io.github.jtsang4.aterm.core.data.local.mapper.toEntity
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.repository.SnippetRepository
import io.github.jtsang4.aterm.core.security.crypto.EncryptedPayload
import io.github.jtsang4.aterm.core.security.crypto.SecretFieldCipher
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSnippetRepository(
    private val snippetDao: SnippetDao,
    private val fieldCipher: SecretFieldCipher,
) : SnippetRepository {
    override fun observeSnippets(): Flow<List<Snippet>> = snippetDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun getSnippet(id: Long): Snippet? = snippetDao.getById(id)?.toDomain()

    override suspend fun upsert(snippet: Snippet, body: String): Snippet {
        val existing = if (snippet.id != 0L) {
            snippetDao.getById(snippet.id)
        } else {
            null
        }
        val baseEntity = snippet.toEntity(
            bodyCipherText = existing?.bodyCipherText,
            bodyIv = existing?.bodyIv,
        )
        val id = if (snippet.id == 0L) {
            snippetDao.insert(baseEntity.copy(id = 0, bodyCipherText = null, bodyIv = null))
        } else {
            snippetDao.update(baseEntity)
            snippet.id
        }
        val encrypted = fieldCipher.encrypt(body.encodeToByteArray(), associatedData = aadFor(id))
        val persisted = requireNotNull(snippetDao.getById(id)) { "Snippet $id was not persisted." }
        snippetDao.update(
            persisted.copy(
                bodyCipherText = encrypted.cipherText,
                bodyIv = encrypted.iv,
            ),
        )
        return requireNotNull(getSnippet(id)) { "Snippet $id was not persisted." }
    }

    override suspend fun getBody(id: Long): String? {
        val entity = snippetDao.getById(id) ?: return null
        val cipherText = entity.bodyCipherText ?: return null
        val iv = entity.bodyIv ?: return null
        return fieldCipher.decrypt(
            EncryptedPayload(cipherText, iv),
            associatedData = aadFor(id),
        ).decodeToString()
    }

    override suspend fun markExecuted(id: Long, executedAt: Instant) {
        val entity = requireNotNull(snippetDao.getById(id)) { "Snippet $id was not found." }
        snippetDao.update(entity.copy(lastRunAtEpochMillis = executedAt.toEpochMilli()))
    }

    override suspend fun deleteSnippet(id: Long) {
        snippetDao.deleteById(id)
    }

    private fun aadFor(id: Long): ByteArray = "snippet:$id:body".encodeToByteArray()
}
