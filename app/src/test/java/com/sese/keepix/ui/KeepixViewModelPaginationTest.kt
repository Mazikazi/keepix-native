package com.sese.keepix.ui

import android.net.Uri
import com.sese.keepix.data.MediaItem
import com.sese.keepix.data.MediaPageKey
import com.sese.keepix.data.MediaRepository
import com.sese.keepix.db.BinItemEntity
import com.sese.keepix.db.KeptItemEntity
import com.sese.keepix.testutil.ViewModelTestHarness
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real keyset-pagination logic that lives in
 * [KeepixViewModel]: [KeepixViewModel.restoreItem]/[KeepixViewModel.unkeepItem]
 * (which call the private `isPastCursor`/`spliceIntoQueue`), and
 * [KeepixViewModel.loadMedia] (which calls the private `fetchBatch` --
 * exclusion filtering, page top-up, and termination). See
 * [ViewModelTestHarness] for why these are reached through a bypass-
 * constructed instance rather than a real `KeepixViewModel(application)`.
 *
 * [MediaRepository] itself -- the actual SQL predicate it builds -- is
 * covered separately in `MediaRepositoryTest`; here it is a MockK stub that
 * hands back fixture pages, so what's under test is purely what
 * KeepixViewModel does with whatever the repository returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepixViewModelPaginationTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // KeepixViewModel.toMediaItem() (used by restoreItem/unkeepItem/
        // spliceIntoQueue) calls the real android.net.Uri.parse, which has no
        // usable implementation on the host JVM -- unmocked, it returns null
        // under isReturnDefaultValues, and Kotlin's platform-type null check
        // then throws inside the launched coroutine, silently swallowed by
        // viewModelScope's SupervisorJob (no assertion ever sees the real
        // exception, just a queue that never got spliced into).
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun mediaItem(id: Long, dateAdded: Long) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/$id"),
        dateAdded = dateAdded,
        isVideo = false,
        displayName = "photo_$id.jpg"
    )

    private fun binItem(mediaId: Long, dateTaken: Long) = BinItemEntity(
        mediaId = mediaId,
        mediaUri = "content://media/$mediaId",
        dateTaken = dateTaken
    )

    private fun mediaItemsFlow(vm: KeepixViewModel) =
        ViewModelTestHarness.getField<MutableStateFlow<List<MediaItem>>>(vm, "_mediaItems")

    // --- isPastCursor / spliceIntoQueue, via restoreItem -------------------

    @Test
    fun `restoring an item whose key exactly equals the cursor is spliced back in`() {
        // Regression pin for the fixed defect: isPastCursor used strict `>`
        // where the spec calls for "at or after" (>=). A restored item sitting
        // exactly on the page boundary must be treated as already-past, or it
        // is silently lost for the rest of the session: not in _mediaItems
        // (excluded when the page was read), not in seenMediaIds, and never
        // revisited by a future page (the next query's `_id < ?` bound skips
        // it forever).
        val vm = ViewModelTestHarness.newViewModel()
        ViewModelTestHarness.setField<MediaPageKey?>(vm, "pageCursor", MediaPageKey(1000L, 5L))

        vm.restoreItem(binItem(mediaId = 5L, dateTaken = 1000L * 1000))

        assertTrue(
            "an item exactly at the cursor must be spliced back into the queue",
            mediaItemsFlow(vm).value.any { it.id == 5L }
        )
        assertTrue(
            5L in ViewModelTestHarness.getField<MutableSet<Long>>(vm, "seenMediaIds")
        )
    }

    @Test
    fun `restoring an item newer than the cursor (already past) is spliced back in`() {
        val vm = ViewModelTestHarness.newViewModel()
        ViewModelTestHarness.setField<MediaPageKey?>(vm, "pageCursor", MediaPageKey(1000L, 5L))

        vm.restoreItem(binItem(mediaId = 9L, dateTaken = 2000L * 1000))

        assertTrue(mediaItemsFlow(vm).value.any { it.id == 9L })
    }

    @Test
    fun `restoring an item still ahead of the cursor is left for ordinary pagination`() {
        // Same dateAdded as the cursor, but a lower id: pagination's
        // `_id < ?` bound hasn't reached it yet, so splicing it in now would
        // place it ahead of rows the queue hasn't fetched, breaking the
        // DATE_ADDED-descending order spliceIntoQueue depends on.
        val vm = ViewModelTestHarness.newViewModel()
        ViewModelTestHarness.setField<MediaPageKey?>(vm, "pageCursor", MediaPageKey(1000L, 5L))

        vm.restoreItem(binItem(mediaId = 3L, dateTaken = 1000L * 1000))

        assertFalse(mediaItemsFlow(vm).value.any { it.id == 3L })
    }

    @Test
    fun `restoring an older item is left for ordinary pagination`() {
        val vm = ViewModelTestHarness.newViewModel()
        ViewModelTestHarness.setField<MediaPageKey?>(vm, "pageCursor", MediaPageKey(1000L, 5L))

        vm.restoreItem(binItem(mediaId = 99L, dateTaken = 500L * 1000))

        assertFalse(mediaItemsFlow(vm).value.any { it.id == 99L })
    }

    @Test
    fun `once the whole library has been read, every restore is spliced in regardless of cursor`() {
        val vm = ViewModelTestHarness.newViewModel()
        ViewModelTestHarness.setField<MediaPageKey?>(vm, "pageCursor", null)
        ViewModelTestHarness.getField<MutableStateFlow<Boolean>>(vm, "_reachedEnd").value = true

        vm.restoreItem(binItem(mediaId = 42L, dateTaken = 1L * 1000))

        assertTrue(mediaItemsFlow(vm).value.any { it.id == 42L })
    }

    @Test
    fun `splice inserts in DATE_ADDED-descending order and never duplicates an id already queued`() {
        val vm = ViewModelTestHarness.newViewModel()
        ViewModelTestHarness.getField<MutableStateFlow<Boolean>>(vm, "_reachedEnd").value = true
        mediaItemsFlow(vm).value = listOf(
            mediaItem(id = 1L, dateAdded = 2000L),
            mediaItem(id = 2L, dateAdded = 1000L)
        )

        vm.restoreItem(binItem(mediaId = 3L, dateTaken = 1500L * 1000))

        assertEquals(listOf(1L, 3L, 2L), mediaItemsFlow(vm).value.map { it.id })

        // Restoring an id that's already in the queue must be a no-op, not a
        // duplicate entry.
        vm.restoreItem(binItem(mediaId = 3L, dateTaken = 1500L * 1000))
        assertEquals(listOf(1L, 3L, 2L), mediaItemsFlow(vm).value.map { it.id })
    }

    @Test
    fun `unkeepItem uses the same past-cursor splice logic as restoreItem`() {
        val vm = ViewModelTestHarness.newViewModel()
        ViewModelTestHarness.setField<MediaPageKey?>(vm, "pageCursor", MediaPageKey(1000L, 5L))

        vm.unkeepItem(
            KeptItemEntity(
                mediaId = 5L,
                mediaUri = "content://media/5",
                displayName = "photo_5.jpg",
                mediaType = "IMAGE",
                dateTaken = 1000L * 1000,
                keptAt = System.currentTimeMillis()
            )
        )

        assertTrue(mediaItemsFlow(vm).value.any { it.id == 5L })
    }

    // --- fetchBatch (exclusion filter, top-up, termination, ties), via loadMedia ----

    @Test
    fun `loadMedia excludes bin and kept ids and tops up until batchSize is met`() {
        val repository = mockk<MediaRepository>()
        coEvery { repository.getMediaPage(null, 5) } returns listOf(
            mediaItem(1, 500), mediaItem(2, 400), mediaItem(3, 300),
            mediaItem(4, 200), mediaItem(5, 100)
        )
        coEvery { repository.getMediaPage(MediaPageKey(100L, 5L), 5) } returns listOf(
            mediaItem(6, 50), mediaItem(7, 25)
        )

        val binDao = mockk<com.sese.keepix.db.BinItemDao>(relaxed = true)
        coEvery { binDao.getAllBinMediaIds() } returns listOf(2L)
        val keptDao = mockk<com.sese.keepix.db.KeptItemDao>(relaxed = true)
        coEvery { keptDao.getAllKeptMediaIds() } returns listOf(3L)

        val prefs = ViewModelTestHarness.fakePrefs(batchSize = 5)
        val vm = ViewModelTestHarness.newViewModel(
            repository = repository,
            binItemDao = binDao,
            keptItemDao = keptDao,
            prefs = prefs
        )

        vm.loadMedia()

        // ids 2 (bin) and 3 (kept) must never surface in the queue.
        assertEquals(
            listOf(1L, 4L, 5L, 6L, 7L),
            mediaItemsFlow(vm).value.map { it.id }
        )
        // Page 2 came back shorter than the requested limit (2 < 5): that is
        // MediaRepository's termination contract, and must flip reachedEnd.
        assertTrue(ViewModelTestHarness.getField<MutableStateFlow<Boolean>>(vm, "_reachedEnd").value)
        // Excluded ids must never be added to seenMediaIds -- only ids
        // actually delivered into the queue are (see fetchBatch's guard).
        assertEquals(
            setOf(1L, 4L, 5L, 6L, 7L),
            ViewModelTestHarness.getField<MutableSet<Long>>(vm, "seenMediaIds")
        )
    }

    @Test
    fun `the page cursor after a tie on dateAdded resolves by id, not just the timestamp`() {
        // DATE_ADDED is second-precision, so two items landing in the same
        // second is a real, common case. The cursor MediaRepository is
        // handed next must be the exact (dateAdded, id) of the last row of
        // the page just consumed -- using only dateAdded here would either
        // re-deliver or skip whichever row of the tied pair sorted second.
        val repository = mockk<MediaRepository>()
        coEvery { repository.getMediaPage(null, 5) } returns listOf(
            mediaItem(10, 1000), mediaItem(9, 1000) // tie on dateAdded=1000, _ID DESC breaks it
        )

        val vm = ViewModelTestHarness.newViewModel(
            repository = repository,
            prefs = ViewModelTestHarness.fakePrefs(batchSize = 5)
        )

        vm.loadMedia()

        assertEquals(
            MediaPageKey(1000L, 9L),
            ViewModelTestHarness.getField<MediaPageKey?>(vm, "pageCursor")
        )
        assertEquals(listOf(10L, 9L), mediaItemsFlow(vm).value.map { it.id })
    }

    @Test
    fun `loadMedia stops as soon as a top-up page comes back empty`() {
        // Page 1 is a full, batchSize-sized page (5 == 5, so it does NOT by
        // itself signal "reached end"), but two of its five rows are
        // excluded (already binned), leaving only 3 new items -- short of
        // batchSize, so fetchBatch must ask for a top-up page. That second
        // page comes back genuinely empty (not just "shorter than limit"):
        // fetchBatch must both flip reachedEnd AND break immediately,
        // exercising the separate `if (page.isEmpty()) break` branch rather
        // than merely the `page.size < limit` one.
        val repository = mockk<MediaRepository>()
        coEvery { repository.getMediaPage(null, 5) } returns listOf(
            mediaItem(1, 500), mediaItem(2, 400), mediaItem(3, 300),
            mediaItem(4, 200), mediaItem(5, 100)
        )
        coEvery { repository.getMediaPage(MediaPageKey(100L, 5L), 5) } returns emptyList()

        val binDao = mockk<com.sese.keepix.db.BinItemDao>(relaxed = true)
        coEvery { binDao.getAllBinMediaIds() } returns listOf(2L, 4L)

        val vm = ViewModelTestHarness.newViewModel(
            repository = repository,
            binItemDao = binDao,
            prefs = ViewModelTestHarness.fakePrefs(batchSize = 5)
        )

        vm.loadMedia()

        assertEquals(listOf(1L, 3L, 5L), mediaItemsFlow(vm).value.map { it.id })
        assertTrue(ViewModelTestHarness.getField<MutableStateFlow<Boolean>>(vm, "_reachedEnd").value)
    }
}
