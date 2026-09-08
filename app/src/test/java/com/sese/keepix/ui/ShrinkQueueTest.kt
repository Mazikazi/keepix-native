package com.sese.keepix.ui

import android.net.Uri
import com.sese.keepix.core.logic.FREE_LIGHT_USES_PER_DAY
import com.sese.keepix.data.MediaItem
import com.sese.keepix.testutil.ViewModelTestHarness
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The deck's per-card shrink queue: [KeepixViewModel.queueCompression],
 * [KeepixViewModel.dequeueCompression] and
 * [KeepixViewModel.flushCompressionQueue].
 *
 * The property under test is that a swipe stages a URI and nothing else --
 * no disk write and no write request until the queue is flushed -- and that a
 * flush goes through the same one-at-a-time `_pendingWrite` gate every other
 * rewrite does. See [ViewModelTestHarness] for why the ViewModel is
 * bypass-constructed rather than really instantiated on the host JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShrinkQueueTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // queueCompression calls item.uri.toString(); android.net.Uri has no
        // implementation on the host JVM. Each parse gets its own mock whose
        // toString is the parsed string, so queued URIs stay distinguishable --
        // a single shared relaxed mock would make every card look like a
        // duplicate of the first.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val text = firstArg<String>()
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns text }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun photo(id: Long) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/$id"),
        dateAdded = id,
        isVideo = false,
        displayName = "photo_$id.jpg"
    )

    private fun video(id: Long) = photo(id).copy(isVideo = true)

    /**
     * The queue and its derived count are property initializers, which an
     * allocateInstance'd ViewModel never ran. The count is wired to the SAME
     * flow instance so a test can assert on either.
     */
    private fun newViewModel(): KeepixViewModel {
        val vm = ViewModelTestHarness.newViewModel()
        val queue = MutableStateFlow<List<String>>(emptyList())
        ViewModelTestHarness.setField(vm, "_shrinkQueue", queue)
        return vm
    }

    private fun queue(vm: KeepixViewModel) =
        ViewModelTestHarness.getField<MutableStateFlow<List<String>>>(vm, "_shrinkQueue").value

    @Test
    fun queueCompression_stagesTheUriAndArmsNoWriteRequest() = runTest {
        val vm = newViewModel()

        assertTrue(vm.queueCompression(photo(1)))
        assertTrue(vm.queueCompression(photo(2)))

        assertEquals(listOf("content://media/1", "content://media/2"), queue(vm))
        // The whole point of the queue: consent is asked for once, later.
        assertNull(vm.pendingWrite.value)
    }

    @Test
    fun queueCompression_refusesVideos() = runTest {
        val vm = newViewModel()

        // Video shrink is out of v1 (addendum A4) -- a video must never reach
        // the compressor, which only handles JPEG anyway.
        assertFalse(vm.queueCompression(video(1)))
        assertEquals(emptyList<String>(), queue(vm))
    }

    @Test
    fun queueCompression_refusesTheSameCardTwice() = runTest {
        val vm = newViewModel()

        assertTrue(vm.queueCompression(photo(1)))
        assertFalse(vm.queueCompression(photo(1)))
        assertEquals(listOf("content://media/1"), queue(vm))
    }

    @Test
    fun queueCompression_stopsAtTheFreeTiersDailyAllowance() = runTest {
        val vm = newViewModel()

        repeat(FREE_LIGHT_USES_PER_DAY) { i ->
            assertTrue(vm.queueCompression(photo(i.toLong())))
        }
        assertFalse(vm.queueCompression(photo(99)))
        assertEquals(FREE_LIGHT_USES_PER_DAY, queue(vm).size)
    }

    @Test
    fun dequeueCompression_removesTheItemAndRefundsItsUse() = runTest {
        val vm = newViewModel()
        repeat(FREE_LIGHT_USES_PER_DAY) { i -> vm.queueCompression(photo(i.toLong())) }

        vm.dequeueCompression(photo(0))

        assertEquals(FREE_LIGHT_USES_PER_DAY - 1, queue(vm).size)
        assertFalse("content://media/0" in queue(vm))
        // An undone shrink never happened, so its allowance comes back.
        assertTrue(vm.queueCompression(photo(99)))
    }

    @Test
    fun dequeueCompression_ignoresAnItemThatWasNeverQueued() = runTest {
        val vm = newViewModel()
        vm.queueCompression(photo(1))

        vm.dequeueCompression(photo(2))

        assertEquals(listOf("content://media/1"), queue(vm))
        // No phantom refund: one use is spent, so exactly the remaining
        // FREE_LIGHT_USES_PER_DAY - 1 land and the next one does not.
        repeat(FREE_LIGHT_USES_PER_DAY - 1) { i ->
            assertTrue(vm.queueCompression(photo(10L + i)))
        }
        assertFalse(vm.queueCompression(photo(99)))
    }

    @Test
    fun flushCompressionQueue_armsOneRequestForEverythingQueued() = runTest {
        val vm = newViewModel()
        vm.queueCompression(photo(1))
        vm.queueCompression(photo(2))

        vm.flushCompressionQueue()

        val pending = vm.pendingWrite.value
        assertEquals(KeepixViewModel.WriteRequestKind.COMPRESSION, pending?.kind)
        assertEquals(listOf("content://media/1", "content://media/2"), pending?.uris)
        // The armed request owns those URIs now; the chip must stop counting them.
        assertEquals(emptyList<String>(), queue(vm))
    }

    @Test
    fun flushCompressionQueue_doesNothingWhenTheQueueIsEmpty() = runTest {
        val vm = newViewModel()

        vm.flushCompressionQueue()

        assertNull(vm.pendingWrite.value)
    }

    @Test
    fun flushCompressionQueue_neverDisplacesAnOutstandingRequest() = runTest {
        val vm = newViewModel()
        vm.queueCompression(photo(1))
        val recovery = KeepixViewModel.PendingWriteRequest(
            KeepixViewModel.WriteRequestKind.RECOVERY, listOf("content://media/9")
        )
        ViewModelTestHarness.getField<MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
            vm, "_pendingWrite"
        ).value = recovery

        vm.flushCompressionQueue()

        // Recovery keeps the gate; a second IntentSender would be dropped by
        // Android and the loser's work would wait forever behind writeInFlight.
        assertEquals(recovery, vm.pendingWrite.value)
        // And the queue survives, so the next flush still has it.
        assertEquals(listOf("content://media/1"), queue(vm))
    }
}
