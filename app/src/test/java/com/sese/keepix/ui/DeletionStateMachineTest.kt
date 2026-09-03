package com.sese.keepix.ui

import com.sese.keepix.db.BinItemDao
import com.sese.keepix.db.BinItemEntity
import com.sese.keepix.testutil.ViewModelTestHarness
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Exercises the real [KeepixViewModel] mark-then-confirm deletion state
 * machine -- the app's central invariant: a Room row for a bin item is never
 * dropped until the user has confirmed its file deletion through the system
 * dialog. All of these are the actual production methods, invoked through a
 * [ViewModelTestHarness]-built instance (see that file for why a real
 * constructor call isn't viable here); the DAO is a plain MockK interface
 * mock, so verifying `coVerify` against it confirms exactly which SQL-level
 * operation the real method performed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletionStateMachineTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun binItem(id: Long, mediaId: Long) = BinItemEntity(
        id = id,
        mediaId = mediaId,
        mediaUri = "content://media/$mediaId",
        retentionMode = "SESSION"
    )

    @Test
    fun `cleanup marks expired session items pending rather than deleting them`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        val expired = listOf(binItem(1, 10), binItem(2, 20))
        coEvery { dao.getExpiredSessionItems(any()) } returns expired
        coEvery { dao.getExpiredTimedItems(any()) } returns emptyList()

        // performLaunchCleanup only marks a *previous* session's leftovers,
        // so prefs must already have a lastSessionId for it to treat as "the
        // session that just ended" -- see KeepixViewModel.performLaunchCleanup.
        val prefs = ViewModelTestHarness.fakePrefs(lastSessionId = "previous-session")
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao, prefs = prefs)

        ViewModelTestHarness.invokePrivate(vm, "performLaunchCleanup")

        coVerify(exactly = 1) { dao.markPendingDeletion(listOf(1L, 2L)) }
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `cleanup marks expired timed items pending rather than deleting them`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        coEvery { dao.getExpiredSessionItems(any()) } returns emptyList()
        val expired = listOf(binItem(5, 50))
        coEvery { dao.getExpiredTimedItems(any()) } returns expired

        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao)

        ViewModelTestHarness.invokePrivate(vm, "performLaunchCleanup")

        coVerify(exactly = 1) { dao.markPendingDeletion(listOf(5L)) }
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `cleanup does not mark anything on the very first launch`() {
        // previousSessionId is empty ("") before the very first launch ever
        // rotates it -- performLaunchCleanup must skip the session-mode sweep
        // in that case (there is no "previous session" to expire).
        val dao = mockk<BinItemDao>(relaxed = true)
        coEvery { dao.getExpiredTimedItems(any()) } returns emptyList()
        val prefs = ViewModelTestHarness.fakePrefs(lastSessionId = "")
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao, prefs = prefs)

        ViewModelTestHarness.invokePrivate(vm, "performLaunchCleanup")

        coVerify(exactly = 0) { dao.getExpiredSessionItems(any()) }
        coVerify(exactly = 0) { dao.markPendingDeletion(any()) }
    }

    @Test
    fun `confirmDeletion drops exactly the given ids and nothing else`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao)

        vm.confirmDeletion(listOf(7L, 8L, 9L))

        coVerify(exactly = 1) { dao.deleteByIds(listOf(7L, 8L, 9L)) }
        coVerify(exactly = 0) { dao.markPendingDeletion(any()) }
        coVerify(exactly = 0) { dao.unmarkPendingDeletion(any()) }
    }

    @Test
    fun `confirmDeletion with an empty id list touches the dao not at all`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao)

        vm.confirmDeletion(emptyList())

        coVerify(exactly = 0) { dao.deleteByIds(any()) }
    }

    @Test
    fun `deferDeletion un-marks the rows instead of deleting them and sets prompted`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao)

        vm.deferDeletion(listOf(11L, 12L))

        coVerify(exactly = 1) { dao.unmarkPendingDeletion(listOf(11L, 12L)) }
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
        assertTrue(
            "deferDeletion must set promptedThisSession so an in-flight " +
                "recomposition doesn't re-show the dialog",
            ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<Boolean>>(
                vm,
                "_promptedThisSession"
            ).value
        )
    }

    @Test
    fun `a cancelled prompt does not re-prompt again within the same session`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao)
        val prompted = ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<Boolean>>(
            vm,
            "_promptedThisSession"
        )
        assertFalse(prompted.value)

        // First cancel: the dialog was shown and dismissed.
        vm.deferDeletion(listOf(1L))
        assertTrue(prompted.value)

        // Nothing else in the class re-arms the flag on its own -- a second,
        // unrelated deferDeletion call (e.g. a stray duplicate callback) must
        // leave it exactly as-is, true, not flip it back to false and permit
        // a second dialog this same session.
        vm.deferDeletion(listOf(1L))
        assertTrue(prompted.value)
    }

    @Test
    fun `deleteBinItems marks rows pending and re-arms the prompt for a fresh batch`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao)
        val prompted = ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<Boolean>>(
            vm,
            "_promptedThisSession"
        )
        prompted.value = true // simulate: an earlier batch was already shown+cancelled

        vm.deleteBinItems(listOf(binItem(1, 10), binItem(2, 20)))

        coVerify(exactly = 1) { dao.markPendingDeletion(listOf(1L, 2L)) }
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
        assertFalse(
            "an explicit new delete request must re-arm the prompt even after an earlier cancel",
            prompted.value
        )
    }

    @Test
    fun `deleteBinItems with an empty list is a no-op`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao)

        vm.deleteBinItems(emptyList())

        coVerify(exactly = 0) { dao.markPendingDeletion(any()) }
    }

    @Test
    fun `rearmPrompt resets the flag without touching the dao`() {
        val dao = mockk<BinItemDao>(relaxed = true)
        val vm = ViewModelTestHarness.newViewModel(binItemDao = dao)
        val prompted = ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<Boolean>>(
            vm,
            "_promptedThisSession"
        )
        prompted.value = true

        vm.rearmPrompt()

        assertFalse(prompted.value)
        coVerify(exactly = 0) { dao.markPendingDeletion(any()) }
        coVerify(exactly = 0) { dao.unmarkPendingDeletion(any()) }
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
    }
}
