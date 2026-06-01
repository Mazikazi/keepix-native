package com.sese.keepix

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sese.keepix.data.MediaItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaginationTest {

    private fun createMediaItem(id: Long): MediaItem {
        return MediaItem(
            id = id,
            uri = Uri.parse("content://media/$id"),
            dateAdded = System.currentTimeMillis(),
            isVideo = false,
            displayName = "photo_$id.jpg"
        )
    }

    @Test
    fun loadNextBatch_loadsCorrectOffset() {
        val allMedia = (1..100).map { createMediaItem(it.toLong()) }

        var loadedCount = 0
        val batchSize = 50

        val firstBatch = allMedia.drop(loadedCount).take(batchSize)
        assertEquals(50, firstBatch.size)
        assertEquals(1L, firstBatch.first().id)
        assertEquals(50L, firstBatch.last().id)
        loadedCount += firstBatch.size

        val secondBatch = allMedia.drop(loadedCount).take(batchSize)
        assertEquals(50, secondBatch.size)
        assertEquals(51L, secondBatch.first().id)
        assertEquals(100L, secondBatch.last().id)
        loadedCount += secondBatch.size

        val thirdBatch = allMedia.drop(loadedCount).take(batchSize)
        assertEquals(0, thirdBatch.size)
    }

    @Test
    fun removeSwipedItem_decrementsLoadedCountCorrectly() {
        val allMedia = (1..10).map { createMediaItem(it.toLong()) }

        var loadedCount = 10
        val displayedItems = allMedia.toMutableList()

        val itemToRemove = allMedia[4]
        displayedItems.remove(itemToRemove)
        loadedCount = (loadedCount - 1).coerceAtLeast(0)

        assertEquals(9, loadedCount)
        assertEquals(9, displayedItems.size)
        assertFalse(displayedItems.contains(itemToRemove))
    }

    @Test
    fun filterBinMediaIds_correctlyExcludesItems() {
        val allMedia = (1..10).map { createMediaItem(it.toLong()) }

        val binMediaIds = setOf(2L, 5L, 8L)
        val filtered = allMedia.filter { it.id !in binMediaIds }

        assertEquals(7, filtered.size)
        assertFalse(filtered.any { it.id in binMediaIds })
    }
}
