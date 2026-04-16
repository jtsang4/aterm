package io.github.jtsang4.aterm.core.data.repository

import androidx.room.withTransaction
import io.github.jtsang4.aterm.core.data.local.AtermDatabase
import io.github.jtsang4.aterm.core.data.local.dao.IdentityDao
import io.github.jtsang4.aterm.core.data.local.mapper.toDomain
import io.github.jtsang4.aterm.core.data.local.mapper.toEntity
import io.github.jtsang4.aterm.core.data.local.entity.IdentityEntity
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.SecretMaterialUnavailableException
import io.github.jtsang4.aterm.core.domain.model.SecretStorageState
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.security.crypto.EncryptedPayload
import io.github.jtsang4.aterm.core.security.crypto.SecretFieldCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomIdentityRepository(
    private val database: AtermDatabase,
    private val identityDao: IdentityDao,
    private val fieldCipher: SecretFieldCipher,
) : IdentityRepository {
    override fun observeIdentities(): Flow<List<Identity>> = identityDao.observeAll().map { entities ->
        entities.map { entity -> entity.toResolvedDomain() }
    }

    override suspend fun getIdentity(id: Long): Identity? = identityDao.getById(id)?.toResolvedDomain()

    override suspend fun upsert(identity: Identity, secrets: IdentitySecretMaterial?): Identity {
        val id = database.withTransaction {
            val existing = if (identity.id != 0L) {
                identityDao.getById(identity.id)
            } else {
                null
            }
            val replacingPrimarySecret = !secrets?.primarySecret.isNullOrEmpty()
            val storingPassphrase = !secrets?.passphrase.isNullOrEmpty()
            val finalHasSecret = when {
                replacingPrimarySecret -> true
                identity.id != 0L -> identity.hasSecret || existing?.hasSecret == true
                else -> identity.hasSecret
            }
            val finalHasPassphrase = identity.hasPassphrase
            val desiredSecretState = when {
                !finalHasSecret -> SecretStorageState.MISSING
                replacingPrimarySecret -> SecretStorageState.AVAILABLE
                else -> identity.secretStorageState
            }
            val desiredPassphraseState = when {
                !finalHasPassphrase -> SecretStorageState.MISSING
                storingPassphrase -> SecretStorageState.AVAILABLE
                else -> identity.passphraseStorageState
            }
            val baseEntity = identity.toEntity(
                primaryCipherText = existing?.primaryCipherText,
                primaryIv = existing?.primaryIv,
                passphraseCipherText = existing?.passphraseCipherText,
                passphraseIv = existing?.passphraseIv,
            ).copy(
                hasSecret = finalHasSecret,
                hasPassphrase = finalHasPassphrase,
                secretStorageState = desiredSecretState.name,
                passphraseStorageState = desiredPassphraseState.name,
            )

            val persistedId = if (identity.id == 0L) {
                identityDao.insert(baseEntity.copy(id = 0))
            } else {
                identityDao.update(baseEntity)
                identity.id
            }

            val persisted = requireNotNull(identityDao.getById(persistedId)) { "Identity $persistedId was not persisted." }
            val primaryPayload = secrets?.primarySecret?.takeIf(String::isNotBlank)?.let {
                encryptString(it, aadFor(persistedId, "primary"))
            }
            val passphrasePayload = secrets?.passphrase?.takeIf(String::isNotBlank)?.let {
                encryptString(it, aadFor(persistedId, "passphrase"))
            }
            identityDao.update(
                persisted.copy(
                    hasSecret = finalHasSecret,
                    hasPassphrase = finalHasPassphrase,
                    secretStorageState = desiredSecretState.name,
                    passphraseStorageState = desiredPassphraseState.name,
                    primaryCipherText = when {
                        !finalHasSecret -> null
                        replacingPrimarySecret -> primaryPayload?.cipherText
                        else -> persisted.primaryCipherText
                    },
                    primaryIv = when {
                        !finalHasSecret -> null
                        replacingPrimarySecret -> primaryPayload?.iv
                        else -> persisted.primaryIv
                    },
                    passphraseCipherText = when {
                        !finalHasPassphrase -> null
                        desiredPassphraseState == SecretStorageState.MISSING -> null
                        storingPassphrase -> passphrasePayload?.cipherText
                        else -> persisted.passphraseCipherText
                    },
                    passphraseIv = when {
                        !finalHasPassphrase -> null
                        desiredPassphraseState == SecretStorageState.MISSING -> null
                        storingPassphrase -> passphrasePayload?.iv
                        else -> persisted.passphraseIv
                    },
                ),
            )

            persistedId
        }
        return requireNotNull(getIdentity(id)) { "Identity $id was not persisted." }
    }

    override suspend fun getSecretMaterial(id: Long): IdentitySecretMaterial? {
        val entity = identityDao.getById(id) ?: return null
        val primaryState = resolveStorageState(
            hasSecret = entity.hasSecret,
            storedState = entity.secretStorageState,
            cipherText = entity.primaryCipherText,
            iv = entity.primaryIv,
            aad = aadFor(id, "primary"),
        )
        val passphraseState = resolveStorageState(
            hasSecret = entity.hasPassphrase,
            storedState = entity.passphraseStorageState,
            cipherText = entity.passphraseCipherText,
            iv = entity.passphraseIv,
            aad = aadFor(id, "passphrase"),
        )
        entity.persistResolvedStates(primaryState, passphraseState)

        if (primaryState == SecretStorageState.BLOCKED) {
            throw SecretMaterialUnavailableException()
        }

        val primarySecret = entity.primaryCipherText
            ?.takeIf { entity.primaryIv != null && primaryState == SecretStorageState.AVAILABLE }
            ?.let { decryptString(EncryptedPayload(it, requireNotNull(entity.primaryIv)), aadFor(id, "primary")) }
        val passphrase = entity.passphraseCipherText
            ?.takeIf { entity.passphraseIv != null && passphraseState == SecretStorageState.AVAILABLE }
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

    private suspend fun IdentityEntity.toResolvedDomain(): Identity {
        val primaryState = resolveStorageState(
            hasSecret = hasSecret,
            storedState = secretStorageState,
            cipherText = primaryCipherText,
            iv = primaryIv,
            aad = aadFor(id, "primary"),
        )
        val passphraseState = resolveStorageState(
            hasSecret = hasPassphrase,
            storedState = passphraseStorageState,
            cipherText = passphraseCipherText,
            iv = passphraseIv,
            aad = aadFor(id, "passphrase"),
        )
        persistResolvedStates(primaryState, passphraseState)
        return toDomain().copy(
            secretStorageState = primaryState,
            passphraseStorageState = passphraseState,
        )
    }

    private suspend fun IdentityEntity.persistResolvedStates(
        primaryState: SecretStorageState,
        passphraseState: SecretStorageState,
    ) {
        if (secretStorageState == primaryState.name && passphraseStorageState == passphraseState.name) {
            return
        }
        identityDao.update(
            copy(
                secretStorageState = primaryState.name,
                passphraseStorageState = passphraseState.name,
            ),
        )
    }

    private fun resolveStorageState(
        hasSecret: Boolean,
        storedState: String,
        cipherText: ByteArray?,
        iv: ByteArray?,
        aad: ByteArray,
    ): SecretStorageState {
        if (!hasSecret) {
            return SecretStorageState.MISSING
        }
        if (cipherText == null || iv == null) {
            return if (storedState == SecretStorageState.MISSING.name) {
                SecretStorageState.MISSING
            } else {
                SecretStorageState.BLOCKED
            }
        }

        return try {
            decryptString(EncryptedPayload(cipherText, iv), aad)
            SecretStorageState.AVAILABLE
        } catch (_: Exception) {
            if (storedState == SecretStorageState.MISSING.name) {
                SecretStorageState.MISSING
            } else {
                SecretStorageState.BLOCKED
            }
        }
    }
}
