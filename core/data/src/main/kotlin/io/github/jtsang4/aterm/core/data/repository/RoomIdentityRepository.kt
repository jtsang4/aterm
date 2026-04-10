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
            val replacingPrimarySecret = secrets?.primarySecret != null
            val updatingSecrets = secrets != null
            val finalHasSecret = when {
                replacingPrimarySecret -> true
                updatingSecrets -> identity.hasSecret
                else -> existing?.hasSecret == true || identity.hasSecret
            }
            val finalHasPassphrase = when {
                updatingSecrets -> identity.hasPassphrase
                else -> existing?.hasPassphrase == true || identity.hasPassphrase
            }
            val baseEntity = identity.toEntity(
                primaryCipherText = existing?.primaryCipherText,
                primaryIv = existing?.primaryIv,
                passphraseCipherText = existing?.passphraseCipherText,
                passphraseIv = existing?.passphraseIv,
            ).copy(
                hasSecret = finalHasSecret,
                hasPassphrase = finalHasPassphrase,
                secretStorageState = when {
                    !finalHasSecret -> SecretStorageState.MISSING.name
                    replacingPrimarySecret -> SecretStorageState.AVAILABLE.name
                    else -> existing?.secretStorageState ?: identity.secretStorageState.name
                },
                passphraseStorageState = when {
                    !finalHasPassphrase -> SecretStorageState.MISSING.name
                    updatingSecrets -> {
                        if (secrets?.passphrase != null) {
                            SecretStorageState.AVAILABLE.name
                        } else if (identity.hasPassphrase) {
                            SecretStorageState.BLOCKED.name
                        } else {
                            SecretStorageState.MISSING.name
                        }
                    }

                    else -> existing?.passphraseStorageState ?: identity.passphraseStorageState.name
                },
            )

            val persistedId = if (identity.id == 0L) {
                identityDao.insert(baseEntity.copy(id = 0))
            } else {
                identityDao.update(baseEntity)
                identity.id
            }

            if (secrets != null && !secrets.isEmpty) {
                val persisted = requireNotNull(identityDao.getById(persistedId)) { "Identity $persistedId was not persisted." }
                val primaryPayload = secrets.primarySecret?.let {
                    encryptString(it, aadFor(persistedId, "primary"))
                }
                val passphrasePayload = secrets.passphrase?.let {
                    encryptString(it, aadFor(persistedId, "passphrase"))
                }
                identityDao.update(
                    persisted.copy(
                        hasSecret = finalHasSecret,
                        hasPassphrase = finalHasPassphrase,
                        secretStorageState = when {
                            !finalHasSecret -> SecretStorageState.MISSING.name
                            replacingPrimarySecret -> SecretStorageState.AVAILABLE.name
                            else -> persisted.secretStorageState
                        },
                        passphraseStorageState = when {
                            !finalHasPassphrase -> SecretStorageState.MISSING.name
                            identity.hasPassphrase && passphrasePayload != null -> SecretStorageState.AVAILABLE.name
                            updatingSecrets && identity.hasPassphrase -> SecretStorageState.BLOCKED.name
                            else -> persisted.passphraseStorageState
                        },
                        primaryCipherText = when {
                            replacingPrimarySecret -> primaryPayload?.cipherText
                            !finalHasSecret -> null
                            else -> persisted.primaryCipherText
                        },
                        primaryIv = when {
                            replacingPrimarySecret -> primaryPayload?.iv
                            !finalHasSecret -> null
                            else -> persisted.primaryIv
                        },
                        passphraseCipherText = when {
                            identity.hasPassphrase && passphrasePayload != null -> passphrasePayload.cipherText
                            !finalHasPassphrase -> null
                            else -> persisted.passphraseCipherText
                        },
                        passphraseIv = when {
                            identity.hasPassphrase && passphrasePayload != null -> passphrasePayload.iv
                            !finalHasPassphrase -> null
                            else -> persisted.passphraseIv
                        },
                    ),
                )
            }

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

        if (primaryState == SecretStorageState.BLOCKED || passphraseState == SecretStorageState.BLOCKED) {
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
            return SecretStorageState.BLOCKED
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
