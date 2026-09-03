package com.sese.keepix.ui

import android.net.Uri
import com.sese.keepix.data.MediaItem
import com.sese.keepix.db.KeptItemDao
import com.sese.keepix.testutil.ViewModelTestHarness
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real [KeepixViewModel] favorite intent / MediaStore-sync
 * state machine -- a sibling of the mark-then-confirm deletion machine
 * covered by [DeletionStateMachineTest]. Favoriting only ever sets flags on
 * an existing kept row; it never drops one, and it never writes to
 * MediaStore itself (that is [confirmFavoriteSync]'s job, invoked by the
 * Activity only after the system dialog returns RESULT_OK). These tests run
 * the actual production methods through a [ViewModelTestHarness]-built
 * instance -- see that file for why a real constructor call isn't viable --
 * and verify behaviour with `coVerify` against a MockK-mocked [KeptItemDao],
 * so a pass here means the real method performed exactly that SQL-level
 * operation, not a reimplementation of it inline in the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteStateMachineTest {

    private lateinit var keptItemDao: KeptItemDao

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        keptItemDao = mockk(relaxed = true)
        // favoriteMedia builds a KeptItemEntity from MediaItem.uri.toString();
        // android.net.Uri.parse has no usable implementation on the host JVM
        // (see KeepixViewModelPaginationTest for the same issue/fix).
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun mediaItem(id: Long) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/$id"),
        dateAdded = 1_000L,
        isVideo = false,
        displayName = "photo_$id.jpg"
    )

    @Test
    fun `favoriteMedia records intent and queues a sync, writing nothing to MediaStore`() = runTest {
        val vm = ViewModelTestHarness.newViewModel(keptItemDao = keptItemDao)

        vm.favoriteMedia(mediaItem(id = 7L))
        advanceUntilIdle()

        coVerify { keptItemDao.insert(match { it.isFavorite && it.pendingFavoriteSync }) }
    }

    @Test
    fun `confirmFavoriteSync clears the pending flag and leaves the star set`() = runTest {
        val vm = ViewModelTestHarness.newViewModel(keptItemDao = keptItemDao)

        vm.confirmFavoriteSync(listOf(1, 2))
        advanceUntilIdle()

        coVerify(exactly = 1) { keptItemDao.clearPendingFavoriteSync(listOf(1, 2)) }
        coVerify(exactly = 0) { keptItemDao.setFavorite(any(), any()) }
    }

    @Test
    fun `deferFavoriteSync suppresses this session but leaves the row pending for retry`() = runTest {
        val vm = ViewModelTestHarness.newViewModel(keptItemDao = keptItemDao)

        vm.deferFavoriteSync(listOf(3))
        advanceUntilIdle()

        assertTrue(vm.favoritePromptedThisSession.value)
        // Nothing was destroyed, so unlike deletion there is nothing to undo:
        // the star stays set in-app. Unlike confirm, the pending flag must
        // NOT be cleared -- a cancelled star that silently never reaches
        // MediaStore is a divergence Keepix can't detect or repair later, so
        // the row stays queued and is retried on the next launch instead.
        coVerify(exactly = 0) { keptItemDao.clearPendingFavoriteSync(any()) }
        coVerify(exactly = 0) { keptItemDao.setFavorite(any(), any()) }
    }

    @Test
    fun `favoriting a new item re-arms the prompt after an earlier cancel latched it`() = runTest {
        // Cross-task regression pin: swipe up item A, dialog shown, user
        // cancels -- deferFavoriteSync latches favoritePromptedThisSession.
        // Ten minutes later in the same process, the user swipes up item B.
        // favoriteMedia(B) must re-arm the prompt (mirroring deleteBinItems'
        // re-arm on a fresh delete request), or MainActivity's
        // pendingFavoriteSync/favoritePromptedThisSession guard stays tripped
        // forever and B's star silently never reaches MediaStore for the rest
        // of the process.
        val vm = ViewModelTestHarness.newViewModel(keptItemDao = keptItemDao)

        vm.deferFavoriteSync(listOf(3))
        advanceUntilIdle()
        assertTrue(vm.favoritePromptedThisSession.value)

        vm.favoriteMedia(mediaItem(id = 8L))
        advanceUntilIdle()

        assertFalse(
            "favoriteMedia must clear favoritePromptedThisSession so a later item can prompt again",
            vm.favoritePromptedThisSession.value
        )
        coVerify { keptItemDao.insert(match { it.isFavorite && it.pendingFavoriteSync }) }
    }
}
