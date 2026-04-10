package io.github.jtsang4.aterm.core.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "known_host_trust",
    indices = [Index(value = ["host", "port"], unique = true)],
)
data class KnownHostTrustEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val hostKeyBase64: String,
    val createdAtEpochMillis: Long,
)
