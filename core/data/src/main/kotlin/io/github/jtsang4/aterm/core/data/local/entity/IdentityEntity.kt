package io.github.jtsang4.aterm.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val username: String?,
    val publicKey: String?,
    val hasSecret: Boolean,
    val hasPassphrase: Boolean,
    val primaryCipherText: ByteArray?,
    val primaryIv: ByteArray?,
    val passphraseCipherText: ByteArray?,
    val passphraseIv: ByteArray?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
