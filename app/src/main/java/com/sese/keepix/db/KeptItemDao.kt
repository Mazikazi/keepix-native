package com.sese.keepix.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * SQLite (API 30-31) caps bound variables at 999. Chunked exactly as
 * [com.sese.keepix.db.BinItemDao] does, so no call site has to remember to.
 */
private const val SQL_ID_CHUNK_SIZE = 900

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

    @Query("SELECT * FROM kept_items WHERE isFavorite = 1 ORDER BY keptAt DESC")
    fun getFavoriteItems(): Flow<List<KeptItemEntity>>

    /** Rows whose star has not yet been written to MediaStore. */
    @Query("SELECT * FROM kept_items WHERE pendingFavoriteSync = 1 ORDER BY keptAt DESC")
    fun getPendingFavoriteSync(): Flow<List<KeptItemEntity>>

    @Query("UPDATE kept_items SET isFavorite = :favorite, pendingFavoriteSync = 1 WHERE id IN (:ids)")
    suspend fun setFavoriteChunk(ids: List<Int>, favorite: Boolean)

    /**
     * Records the user's intent and queues a MediaStore sync. Never writes to
     * MediaStore itself — that only happens after the user confirms the system
     * dialog. Chunked internally.
     */
    suspend fun setFavorite(ids: List<Int>, favorite: Boolean) {
        ids.chunked(SQL_ID_CHUNK_SIZE).forEach { setFavoriteChunk(it, favorite) }
    }

    @Query("UPDATE kept_items SET pendingFavoriteSync = 0 WHERE id IN (:ids)")
    suspend fun clearPendingFavoriteSyncChunk(ids: List<Int>)

    /**
     * Clears the sync flag. Called both when the system dialog confirms the write
     * and when it is cancelled — a cancelled star stays set in-app but stops
     * re-prompting, which is the deliberate difference from deletion (nothing was
     * destroyed, so there is nothing to undo).
     */
    suspend fun clearPendingFavoriteSync(ids: List<Int>) {
        ids.chunked(SQL_ID_CHUNK_SIZE).forEach { clearPendingFavoriteSyncChunk(it) }
    }
}
