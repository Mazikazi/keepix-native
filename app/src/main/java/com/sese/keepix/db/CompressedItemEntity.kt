package com.sese.keepix.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One finished shrink, kept so the Compressed tab can show what it bought.
 *
 * Written only after [com.sese.keepix.utils.PhotoCompressor] has verified the
 * rewrite, so a row here means the file on disk really is the smaller one.
 * [mediaUri] is the key: shrinking the same file twice replaces the row rather
 * than double-counting it, and [beforeBytes] then honestly refers to the size
 * it had going into the most recent pass.
 */
@Entity(tableName = "compressed_items")
data class CompressedItemEntity(
    @PrimaryKey val mediaUri: String,
    val displayName: String,
    val beforeBytes: Long,
    val afterBytes: Long,
    /** [com.sese.keepix.core.logic.QualityTier.name] at the time of the shrink. */
    val tier: String,
    val compressedAt: Long = System.currentTimeMillis(),
)

@Dao
interface CompressedItemDao {
    @Query("SELECT * FROM compressed_items ORDER BY compressedAt DESC")
    fun getAll(): Flow<List<CompressedItemEntity>>

    /**
     * Total bytes saved. SUM over an empty table is NULL in SQLite, hence the
     * nullable return -- a non-null Long here would throw on a fresh install.
     */
    @Query("SELECT SUM(beforeBytes - afterBytes) FROM compressed_items")
    fun getTotalSaved(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CompressedItemEntity)
}
