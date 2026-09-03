package com.sese.keepix.ui

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sese.keepix.data.MediaItem
import com.sese.keepix.data.MediaPageKey
import com.sese.keepix.db.BinItemEntity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Defect 10 regression test: restoring an item from the bin must splice it
 * back into the swipe queue in place, not reset the queue back to the top.
 *
 * Unlike the JVM-level pagination tests (`KeepixViewModelPaginationTest`),
 * this constructs a REAL `KeepixViewModel(application)` -- the actual
 * constructor, `init` block, Room database and WorkManager all run for real,
 * which is only possible on a device/emulator (WorkManager auto-initializes
 * from the app's ContentProvider, and Room needs real SQLite). What still
 * can't be relied on deterministically even on a device is the *content* of
 * the live MediaStore, so the swipe-queue state itself is staged directly
 * via reflection on the same private fields the JVM tests use -- only the
 * construction path differs (real vs. `Unsafe`-bypassed).
 */
@RunWith(AndroidJUnit4::class)
class RestoreSwipeQueueTest {

    private fun field(name: String) =
        KeepixViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun mediaItem(id: Long, dateAdded: Long) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/$id"),
        dateAdded = dateAdded,
        isVideo = false,
        displayName = "photo_$id.jpg"
    )

    @Test
    fun restoringAnItemSplicesItInPlaceWithoutResettingTheQueueToTheTop() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = KeepixViewModel(application)

        // Stage a swipe-queue snapshot as if pagination had already delivered
        // these two newer items and read past dateAdded=1000 (the cursor).
        val mediaItemsFlow = field("_mediaItems").get(vm) as MutableStateFlow<List<MediaItem>>
        mediaItemsFlow.value = listOf(mediaItem(1L, 3000L), mediaItem(2L, 2000L))
        field("pageCursor").set(vm, MediaPageKey(dateAdded = 1000L, id = 5L))

        val restored = BinItemEntity(
            mediaId = 5L,
            mediaUri = "content://media/5",
            dateTaken = 1000L * 1000 // toMediaItem() divides by 1000
        )

        vm.restoreItem(restored)

        // The queue must NOT reset to [restored] or to empty-then-reload: it
        // must still contain the two items that were already there, with the
        // restored item spliced in at its correct DATE_ADDED-descending spot
        // (here, after both since it's the oldest of the three) -- never
        // jumping to the front and never dropping what was already queued.
        val expectedOrder = listOf(1L, 2L, 5L)
        // restoreItem launches on viewModelScope; give it a moment to settle
        // since this runs against the real dispatcher on a device.
        var attempts = 0
        while (mediaItemsFlow.value.size < 3 && attempts < 50) {
            Thread.sleep(20)
            attempts++
        }
        assertEquals(expectedOrder, mediaItemsFlow.value.map { it.id })
    }
}
