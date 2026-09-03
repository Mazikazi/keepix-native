package com.sese.keepix.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CompressionJournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CompressionJournalEntity)

    /**
     * Oldest first, so recovery repairs files in the order they were damaged.
     * A suspend list rather than a Flow: recovery reads this once at launch and
     * acts on it, and a Flow would re-fire as recovery deleted its own rows.
     */
    @Query("SELECT * FROM compression_journal ORDER BY startedAt ASC")
    suspend fun getAll(): List<CompressionJournalEntity>

    @Query("SELECT COUNT(*) FROM compression_journal")
    suspend fun count(): Int

    @Query("DELETE FROM compression_journal WHERE mediaUri = :mediaUri")
    suspend fun deleteByUri(mediaUri: String)
}
