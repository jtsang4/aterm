package io.github.jtsang4.aterm.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val address: String,
    val port: Int,
    val username: String,
    val identityId: Long,
    val isFavorite: Boolean,
    val lastUsedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
