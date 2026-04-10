package io.github.jtsang4.aterm.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_metadata")
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
