package io.github.jtsang4.aterm.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String?,
    val tagsSerialized: String,
    val hostId: Long?,
    val bodyCipherText: ByteArray?,
    val bodyIv: ByteArray?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastRunAtEpochMillis: Long?,
)
