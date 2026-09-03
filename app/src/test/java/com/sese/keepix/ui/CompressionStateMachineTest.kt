package com.sese.keepix.ui

import com.sese.keepix.testutil.ViewModelTestHarness
import com.sese.keepix.utils.CompressionOutcome
import com.sese.keepix.utils.PhotoCompressor
import com.sese.keepix.utils.ReclaimEstimate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The compression request/grant state machine, exercised through the real
 * KeepixViewModel methods via [ViewModelTestHarness] -- see that file for why a
 * real constructor call is not viable on the host JVM.
 *
 * The property under test throughout is that a MediaStore write only ever
 * happens after an explicit grant, and that recovery of an interrupted write
 * always takes precedence over starting new work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompressionStateMachineTest {

    private lateinit var compressor: PhotoCompressor

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        compressor = mockk(relaxed = true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun estimate(vararg uris: String) =
        ReclaimEstimate(uris.size, uris.size, 1_000_000L, uris.toList())

    @Test
    fun requestCompression_armsAPendingWriteButCompressesNothingYet() = runTest {
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a", "uri://b")

        vm.requestCompression()

        val pending = vm.pendingWrite.value
        assertNotNull(pending)
        assertEquals(KeepixViewModel.WriteRequestKind.COMPRESSION, pending!!.kind)
        assertEquals(listOf("uri://a", "uri://b"), pending.uris)
        coVerify(exactly = 0) { compressor.compress(any()) }
    }

    @Test
    fun onWriteGranted_compressionKind_compressesEveryUriInSequence() = runTest {
        coEvery { compressor.compress(any()) } answers {
            CompressionOutcome.Compressed(firstArg(), 100_000)
        }
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a", "uri://b")
        vm.requestCompression()

        vm.onWriteGranted()

        coVerify(exactly = 1) { compressor.compress("uri://a") }
        coVerify(exactly = 1) { compressor.compress("uri://b") }
        assertNull("the request must clear once it has run", vm.pendingWrite.value)
    }

    @Test
    fun onWriteDenied_writesNothingAndClearsTheRequest() = runTest {
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a")
        vm.requestCompression()

        vm.onWriteDenied()

        coVerify(exactly = 0) { compressor.compress(any()) }
        assertNull(vm.pendingWrite.value)
    }

    @Test
    fun onWriteGranted_recoveryKind_recoversAndDoesNotCompress() = runTest {
        coEvery { compressor.recover() } returns
            listOf(CompressionOutcome.Failed("uri://x", "recovered", restored = true))
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
            vm, "_pendingWrite"
        ).value = KeepixViewModel.PendingWriteRequest(
            KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://x")
        )

        vm.onWriteGranted()

        coVerify(exactly = 1) { compressor.recover() }
        coVerify(exactly = 0) { compressor.compress(any()) }
        assertNull(vm.pendingWrite.value)
    }

    @Test
    fun requestCompression_yieldsWhileARecoveryIsPending() = runTest {
        // Recovery repairs a possibly-damaged file; starting new rewrites while
        // one is outstanding would queue a second dialog behind the first and
        // Android would silently drop one of them.
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        val pendingField = ViewModelTestHarness
            .getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
                vm, "_pendingWrite"
            )
        pendingField.value = KeepixViewModel.PendingWriteRequest(
            KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://x")
        )
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a")

        vm.requestCompression()

        assertEquals(
            "a pending recovery must not be replaced by a compression request",
            KeepixViewModel.WriteRequestKind.RECOVERY,
            pendingField.value!!.kind
        )
    }

    @Test
    fun requestCompression_withNoEligibleFiles_armsNothing() = runTest {
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = ReclaimEstimate(10, 0, 0L, emptyList())

        vm.requestCompression()

        assertNull(vm.pendingWrite.value)
    }

    @Test
    fun onWriteGranted_capsOneRunAtTheBatchLimit() = runTest {
        coEvery { compressor.compress(any()) } answers {
            CompressionOutcome.Compressed(firstArg(), 100_000)
        }
        val many = (1..120).map { "uri://$it" }
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = ReclaimEstimate(many.size, many.size, 1L, many)

        vm.requestCompression()
        assertEquals(MAX_COMPRESSION_BATCH, vm.pendingWrite.value!!.uris.size)

        vm.onWriteGranted()
        coVerify(exactly = MAX_COMPRESSION_BATCH) { compressor.compress(any()) }
    }
}
