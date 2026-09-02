package com.sese.keepix.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "kept_items")
data class KeptItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val mediaId: Long,
    val mediaUri: String,
    val displayName: String,
    val mediaType: String,
    val dateTaken: Long,
    val keptAt: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    /** The user's intent. This app's source of truth for the star. */
    val isFavorite: Boolean = false,
    /**
     * The MediaStore IS_FAVORITE flag does not match [isFavorite] yet. Direction
     * agnostic: the target state is whatever [isFavorite] says, so one flag covers
     * both favoriting and un-favoriting.
     */
    val pendingFavoriteSync: Boolean = false
)
