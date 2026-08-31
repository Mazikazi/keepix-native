package com.sese.keepix.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * SQLite (API 30-31) caps bound variables at 999 (`SQLITE_MAX_VARIABLE_NUMBER`).
 * An `IN (:ids)` query built from an unbounded list — a bin can ordinarily hold
 * several hundred to a thousand+ items — can throw past that cap. Every id-list
 * query below chunks through this constant so no call site has to remember to.
 */
private const val SQL_ID_CHUNK_SIZE = 900

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

    @Query("UPDATE bin_items SET pendingDeletion = 1 WHERE id IN (:ids)")
    suspend fun markPendingDeletionChunk(ids: List<Long>)

    /**
     * Marks rows for permanent removal. This never touches the file on disk and
     * never drops the row — both only happen after the user confirms. Chunked
     * internally so an arbitrarily large bin never trips SQLite's bound-variable
     * limit.
     */
    suspend fun markPendingDeletion(ids: List<Long>) {
        ids.chunked(SQL_ID_CHUNK_SIZE).forEach { markPendingDeletionChunk(it) }
    }

    @Query("UPDATE bin_items SET pendingDeletion = 0 WHERE id IN (:ids)")
    suspend fun unmarkPendingDeletionChunk(ids: List<Long>)

    /**
     * Un-marks rows previously marked pending deletion. Used when the user
     * cancels/defers the system delete dialog, so a cancelled prompt doesn't
     * permanently latch those rows into "pending" and force a re-prompt on every
     * future launch. Chunked internally for the same reason as [markPendingDeletion].
     */
    suspend fun unmarkPendingDeletion(ids: List<Long>) {
        ids.chunked(SQL_ID_CHUNK_SIZE).forEach { unmarkPendingDeletionChunk(it) }
    }

    @Query("SELECT COUNT(*) FROM bin_items")
    fun getBinCount(): Flow<Int>

    @Query("SELECT mediaId FROM bin_items")
    suspend fun getAllBinMediaIds(): List<Long>

    @Delete
    suspend fun delete(item: BinItemEntity)

    @Query("DELETE FROM bin_items WHERE id IN (:ids)")
    suspend fun deleteByIdsChunk(ids: List<Long>)

    /**
     * Drops rows by id. Callers must only pass ids whose file deletion has already
     * been confirmed by the user — see [com.sese.keepix.ui.KeepixViewModel.confirmDeletion].
     * Chunked internally for the same reason as [markPendingDeletion].
     */
    suspend fun deleteByIds(ids: List<Long>) {
        ids.chunked(SQL_ID_CHUNK_SIZE).forEach { deleteByIdsChunk(it) }
    }
}
