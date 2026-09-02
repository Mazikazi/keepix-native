package com.sese.keepix

import com.sese.keepix.db.KeptItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteBatchTest {

    private fun item(id: Int, fav: Boolean) = KeptItemEntity(
        id = id, mediaId = id.toLong(), mediaUri = "content://m/$id",
        displayName = "$id.jpg", mediaType = "IMAGE", dateTaken = 0L, keptAt = 0L,
        isFavorite = fav, pendingFavoriteSync = true
    )

    @Test
    fun `a mixed pending set never produces a mixed batch`() {
        val batch = selectFavoriteBatch(
            listOf(item(1, true), item(2, false), item(3, true))
        )
        assertTrue("every item in a batch shares one target state",
            batch.all { it.isFavorite == batch.first().isFavorite })
    }

    @Test
    fun `favorites are sent before un-favorites`() {
        val batch = selectFavoriteBatch(listOf(item(1, false), item(2, true)))
        assertEquals(listOf(2), batch.map { it.id })
    }

    @Test
    fun `an all-unfavorite set still produces a batch`() {
        val batch = selectFavoriteBatch(listOf(item(1, false), item(2, false)))
        assertEquals(listOf(1, 2), batch.map { it.id })
    }

    @Test
    fun `an empty pending set produces an empty batch`() =
        assertTrue(selectFavoriteBatch(emptyList()).isEmpty())
}
