package com.sese.keepix.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface KeptItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: KeptItemEntity)

    @Query("SELECT * FROM kept_items ORDER BY keptAt DESC")
    fun getAllItems(): Flow<List<KeptItemEntity>>

    @Query("SELECT COUNT(*) FROM kept_items")
    fun getKeptCount(): Flow<Int>

    @Query("SELECT mediaId FROM kept_items")
    suspend fun getAllKeptMediaIds(): List<Long>

    @Delete
    suspend fun delete(item: KeptItemEntity)

    @Query("DELETE FROM kept_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("DELETE FROM kept_items")
    suspend fun clearAll()
}
