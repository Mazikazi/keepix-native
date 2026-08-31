package com.sese.keepix.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BinItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BinItemEntity)

    @Query("SELECT * FROM bin_items ORDER BY deletedAt DESC")
    fun getAllItems(): Flow<List<BinItemEntity>>

    @Query("SELECT * FROM bin_items WHERE retentionMode = 'SESSION' AND sessionId != :currentSessionId AND pendingDeletion = 0")
    suspend fun getExpiredSessionItems(currentSessionId: String): List<BinItemEntity>

    @Query("SELECT * FROM bin_items WHERE retentionMode = 'TIMED' AND expiryAt <= :now AND expiryAt > 0 AND pendingDeletion = 0")
    suspend fun getExpiredTimedItems(now: Long): List<BinItemEntity>

    /**
     * Rows that cleanup has selected for permanent removal but that have not yet
     * been confirmed by the user through the system delete dialog.
     */
    @Query("SELECT * FROM bin_items WHERE pendingDeletion = 1 ORDER BY deletedAt DESC")
    fun getPendingDeletion(): Flow<List<BinItemEntity>>

    /**
     * Marks rows for permanent removal. This never touches the file on disk and
     * never drops the row — both only happen after the user confirms.
     */
    @Query("UPDATE bin_items SET pendingDeletion = 1 WHERE id IN (:ids)")
    suspend fun markPendingDeletion(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM bin_items")
    fun getBinCount(): Flow<Int>

    @Query("SELECT mediaId FROM bin_items")
    suspend fun getAllBinMediaIds(): List<Long>

    @Delete
    suspend fun delete(item: BinItemEntity)

    @Query("DELETE FROM bin_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
