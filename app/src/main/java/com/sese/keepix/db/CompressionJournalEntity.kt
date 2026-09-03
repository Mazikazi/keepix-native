package com.sese.keepix.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One in-flight rewrite.
 *
 * A row exists from just before the first byte is written until after the
 * read-back comparison passes, and for exactly that window a backup of the
 * original sits at [backupPath]. So a row still present at launch means a write
 * did not finish, and the original must be restored before anything else runs.
 *
 * [mediaUri] is the primary key: one rewrite per file at a time, and re-entering
 * recovery for the same file is idempotent.
 */
@Entity(tableName = "compression_journal")
data class CompressionJournalEntity(
    @PrimaryKey val mediaUri: String,
    /** Absolute path of the app-private copy of the original. */
    val backupPath: String,
    val originalSize: Long,
    val startedAt: Long
)
