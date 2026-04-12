package io.github.jtsang4.aterm.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "snippet_execution_history",
    foreignKeys = [
        ForeignKey(
            entity = SnippetEntity::class,
            parentColumns = ["id"],
            childColumns = ["snippetId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["snippetId"]),
        Index(value = ["executedAtEpochMillis"]),
    ],
)
data class SnippetExecutionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snippetId: Long?,
    val snippetTitle: String,
    val targetKind: String,
    val targetLabel: String,
    val targetDetail: String,
    val executedAtEpochMillis: Long,
)
