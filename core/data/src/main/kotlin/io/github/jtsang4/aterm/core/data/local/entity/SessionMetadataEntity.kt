package io.github.jtsang4.aterm.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_metadata",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["hostId"]),
    ],
)
data class SessionMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val state: String,
    val title: String?,
    val connectedAtEpochMillis: Long?,
    val disconnectedAtEpochMillis: Long?,
    val reconnectRequired: Boolean,
    val lastError: String?,
)
