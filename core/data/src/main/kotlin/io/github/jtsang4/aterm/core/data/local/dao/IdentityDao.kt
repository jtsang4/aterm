package io.github.jtsang4.aterm.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.jtsang4.aterm.core.data.local.entity.IdentityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities ORDER BY name ASC")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun getById(id: Long): IdentityEntity?

    @Insert
    suspend fun insert(entity: IdentityEntity): Long

    @Update
    suspend fun update(entity: IdentityEntity)

    @Query("DELETE FROM identities WHERE id = :id")
    suspend fun deleteById(id: Long)
}
