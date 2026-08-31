package com.sese.keepix.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bin_items")
data class BinItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val mediaUri: String,
    val displayName: String = "",
    val mediaType: String = "IMAGE", // IMAGE or VIDEO
    val dateTaken: Long = 0L,
    val deletedAt: Long = System.currentTimeMillis(),
    val expiryAt: Long = 0L,        // 0 = session mode
    val sessionId: String = "",
    val retentionMode: String = "SESSION", // SESSION or TIMED
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    /**
     * True once cleanup has selected this row for permanent removal. The file is
     * only removed after the user confirms the system delete dialog; the row is
     * dropped only after that confirmation succeeds.
     */
    val pendingDeletion: Boolean = false
)
