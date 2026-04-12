package io.github.jtsang4.aterm.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "snippets",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["hostId"]),
    ],
)
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String?,
    val tagsSerialized: String,
    val hostId: Long?,
    val savedTarget: String,
    val bodyCipherText: ByteArray?,
    val bodyIv: ByteArray?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastRunAtEpochMillis: Long?,
)
