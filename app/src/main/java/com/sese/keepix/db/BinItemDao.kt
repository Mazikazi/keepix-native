package com.sese.keepix.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BinItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BinItemEntity)

    @Query("SELECT * FROM bin_items ORDER BY deletedAt DESC")
    fun getAllItems(): Flow<List<BinItemEntity>>

    @Query("SELECT * FROM bin_items WHERE retentionMode = 'SESSION' AND sessionId != :currentSessionId")
    suspend fun getExpiredSessionItems(currentSessionId: String): List<BinItemEntity>

    @Query("SELECT * FROM bin_items WHERE retentionMode = 'TIMED' AND expiryAt <= :now AND expiryAt > 0")
    suspend fun getExpiredTimedItems(now: Long): List<BinItemEntity>

    @Query("SELECT COUNT(*) FROM bin_items")
    fun getBinCount(): Flow<Int>

    @Query("SELECT mediaId FROM bin_items")
    suspend fun getAllBinMediaIds(): List<Long>

    @Delete
    suspend fun delete(item: BinItemEntity)

    @Query("DELETE FROM bin_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM bin_items")
    suspend fun clearAll()

    @Query("DELETE FROM bin_items WHERE mediaId = :mediaId")
    suspend fun deleteByMediaId(mediaId: Long)
}
