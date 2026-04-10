package io.github.jtsang4.aterm.core.data.repository

import io.github.jtsang4.aterm.core.data.local.dao.IdentityDao
import io.github.jtsang4.aterm.core.data.local.mapper.toDomain
import io.github.jtsang4.aterm.core.data.local.mapper.toEntity
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.security.crypto.EncryptedPayload
import io.github.jtsang4.aterm.core.security.crypto.SecretFieldCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomIdentityRepository(
    private val identityDao: IdentityDao,
    private val fieldCipher: SecretFieldCipher,
) : IdentityRepository {
    override fun observeIdentities(): Flow<List<Identity>> = identityDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun getIdentity(id: Long): Identity? = identityDao.getById(id)?.toDomain()

    override suspend fun upsert(identity: Identity, secrets: IdentitySecretMaterial?): Identity {
        val existing = if (identity.id != 0L) {
            identityDao.getById(identity.id)
        } else {
            null
        }
        val baseEntity = identity.toEntity(
            primaryCipherText = existing?.primaryCipherText,
            primaryIv = existing?.primaryIv,
            passphraseCipherText = existing?.passphraseCipherText,
            passphraseIv = existing?.passphraseIv,
        ).copy(
            hasSecret = secrets?.primarySecret != null || existing?.hasSecret == true || identity.hasSecret,
            hasPassphrase = secrets?.passphrase != null || existing?.hasPassphrase == true || identity.hasPassphrase,
        )

        val id = if (identity.id == 0L) {
            identityDao.insert(baseEntity.copy(id = 0))
        } else {
            identityDao.update(baseEntity)
            identity.id
        }

        if (secrets != null && !secrets.isEmpty) {
            val persisted = requireNotNull(identityDao.getById(id)) { "Identity $id was not persisted." }
            val primaryPayload = secrets.primarySecret?.let {
                encryptString(it, aadFor(id, "primary"))
            }
            val passphrasePayload = secrets.passphrase?.let {
                encryptString(it, aadFor(id, "passphrase"))
            }
            identityDao.update(
                persisted.copy(
                    hasSecret = secrets.primarySecret != null || persisted.hasSecret,
                    hasPassphrase = secrets.passphrase != null || persisted.hasPassphrase,
                    primaryCipherText = primaryPayload?.cipherText ?: persisted.primaryCipherText,
                    primaryIv = primaryPayload?.iv ?: persisted.primaryIv,
                    passphraseCipherText = passphrasePayload?.cipherText ?: persisted.passphraseCipherText,
                    passphraseIv = passphrasePayload?.iv ?: persisted.passphraseIv,
                ),
            )
        }

        return requireNotNull(getIdentity(id)) { "Identity $id was not persisted." }
    }

    override suspend fun getSecretMaterial(id: Long): IdentitySecretMaterial? {
        val entity = identityDao.getById(id) ?: return null
        val primarySecret = entity.primaryCipherText
            ?.takeIf { entity.primaryIv != null }
            ?.let { decryptString(EncryptedPayload(it, requireNotNull(entity.primaryIv)), aadFor(id, "primary")) }
        val passphrase = entity.passphraseCipherText
            ?.takeIf { entity.passphraseIv != null }
            ?.let { decryptString(EncryptedPayload(it, requireNotNull(entity.passphraseIv)), aadFor(id, "passphrase")) }
        return IdentitySecretMaterial(primarySecret = primarySecret, passphrase = passphrase)
    }

    override suspend fun deleteIdentity(id: Long) {
        identityDao.deleteById(id)
    }

    private fun encryptString(value: String, aad: ByteArray): EncryptedPayload =
        fieldCipher.encrypt(value.encodeToByteArray(), associatedData = aad)

    private fun decryptString(payload: EncryptedPayload, aad: ByteArray): String =
        fieldCipher.decrypt(payload, associatedData = aad).decodeToString()

    private fun aadFor(id: Long, slot: String): ByteArray = "identity:$id:$slot".encodeToByteArray()
}
