package com.sese.keepix.ui

import com.sese.keepix.db.CompressionJournalDao
import com.sese.keepix.db.CompressionJournalEntity
import com.sese.keepix.testutil.ViewModelTestHarness
import com.sese.keepix.utils.CompressionOutcome
import com.sese.keepix.utils.PhotoCompressor
import com.sese.keepix.utils.ReclaimEstimate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * A prior version of this test only checked that each URI was compressed
     * exactly once -- true even for `uris.map { async { compress(it) } }.awaitAll()`,
     * which would violate the one-file-at-a-time invariant PhotoCompressor's
     * crash-safety journal depends on. This version uses a compressor stub
     * that genuinely suspends (a [CompletableDeferred] await, not a timed
     * delay -- real suspension regardless of which TestDispatcher backs
     * Main) so overlapping compress() calls are actually observable: it
     * tracks the concurrently-in-flight count and the call order, and a
     * concurrent rewrite is caught two ways -- the loop would already have
     * reached "uri://b" while "uri://a" is still mid-write (asserted
     * immediately after the single onWriteGranted() call, before the gate is
     * ever opened), and separately the max observed concurrency would be 2,
     * not 1.
     */
    @Test
    fun onWriteGranted_compressionKind_compressesEveryUriInSequence() = runTest {
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var concurrent = 0
        var maxConcurrent = 0
        coEvery { compressor.compress(any()) } coAnswers {
            val uri = firstArg<String>()
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            order.add(uri)
            gate.await()
            concurrent--
            CompressionOutcome.Compressed(uri, 100_000)
        }
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a", "uri://b")
        vm.requestCompression()

        vm.onWriteGranted()

        // A serialized loop is suspended inside the very first compress()
        // call at this point and cannot have reached "uri://b" yet.
        assertEquals("only one file may be mid-write at a time", 1, concurrent)
        assertEquals(listOf("uri://a"), order)

        gate.complete(Unit)

        assertEquals("writes must never overlap", 1, maxConcurrent)
        assertEquals(
            "compress() must be called in the requested order",
            listOf("uri://a", "uri://b"), order
        )
        coVerify(exactly = 1) { compressor.compress("uri://a") }
        coVerify(exactly = 1) { compressor.compress("uri://b") }
        assertNull("the request must clear once it has run", vm.pendingWrite.value)
    }

    /**
     * Finding 1: `onWriteGranted()` reads `_pendingWrite` and does not clear it
     * until the whole batch's `finally`, but `photoCompressor.compress(uri)`
     * suspends -- a real dispatch away from Main and back on every file. A
     * Main-thread re-entry into `onWriteGranted()` during that window (a
     * duplicate activity-result callback, a config-change redelivery, ...)
     * must not launch a second concurrent pass over the same URIs, since
     * PhotoCompressor's crash-safety journal assumes at most one file is ever
     * mid-write.
     */
    @Test
    fun onWriteGranted_reentrantCallWhileInFlight_doesNotStartASecondPass() = runTest {
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var concurrent = 0
        var maxConcurrent = 0
        coEvery { compressor.compress(any()) } coAnswers {
            val uri = firstArg<String>()
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            order.add(uri)
            gate.await()
            concurrent--
            CompressionOutcome.Compressed(uri, 100_000)
        }
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a", "uri://b")
        vm.requestCompression()

        vm.onWriteGranted() // first pass starts, suspends mid compress("uri://a")
        vm.onWriteGranted() // re-entrant call while the first pass is still in flight
        vm.onWriteGranted() // and again, in case a caller retries

        assertEquals(
            "a re-entrant call while a run is in flight must not start a second pass",
            1, concurrent
        )
        assertEquals(listOf("uri://a"), order)

        gate.complete(Unit)

        assertEquals("writes must never overlap", 1, maxConcurrent)
        assertEquals(listOf("uri://a", "uri://b"), order)
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

    // --- Important 4: a failed recovery must not be silent ------------------

    @Test
    fun onWriteGranted_recoveryKind_realRestoreFailure_surfacesAnAlarmingError() = runTest {
        // restore() itself threw with the backup still present -- the photo
        // may genuinely be left in a bad state.
        coEvery { compressor.recover() } returns listOf(
            CompressionOutcome.Failed("uri://x", "restore failed: write denied", restored = false)
        )
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
            vm, "_pendingWrite"
        ).value = KeepixViewModel.PendingWriteRequest(KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://x"))

        vm.onWriteGranted()

        val error = vm.error.value
        assertNotNull("a genuine restore failure must not be silent", error)
        assertTrue(
            "the message must say something could not be restored: $error",
            error!!.contains("could not restore", ignoreCase = true)
        )
    }

    @Test
    fun onWriteGranted_recoveryKind_backupMissing_surfacesANonAlarmingMessage() = runTest {
        // Deliberate benign case (see PhotoCompressor.recover's doc): a process
        // death between releaseBackup()'s two deletes leaves a journal row with
        // no backup for a photo that was in fact already compressed correctly.
        coEvery { compressor.recover() } returns listOf(
            CompressionOutcome.Failed("uri://x", "backup missing", restored = false)
        )
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
            vm, "_pendingWrite"
        ).value = KeepixViewModel.PendingWriteRequest(KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://x"))

        vm.onWriteGranted()

        val error = vm.error.value
        assertNotNull("this must still be surfaced, not silent", error)
        assertTrue(
            "must not sound alarming for the benign case: $error",
            error!!.contains("already optimized", ignoreCase = true)
        )
        assertFalse(
            "must not use damaged/alarming language for the benign case: $error",
            error.contains("damaged", ignoreCase = true) || error.contains("could not restore", ignoreCase = true)
        )
    }

    @Test
    fun onWriteGranted_recoveryKind_mixedOutcomes_setsBothStatusAndErrorWithoutClobbering() = runTest {
        coEvery { compressor.recover() } returns listOf(
            CompressionOutcome.Failed("uri://a", "recovered an interrupted write", restored = true),
            CompressionOutcome.Failed("uri://b", "backup missing", restored = false),
            CompressionOutcome.Failed("uri://c", "restore failed: disk full", restored = false)
        )
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
            vm, "_pendingWrite"
        ).value = KeepixViewModel.PendingWriteRequest(
            KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://a", "uri://b", "uri://c")
        )

        vm.onWriteGranted()

        assertEquals(
            "Restored 1 photo after an interrupted optimization.",
            vm.compressionStatus.value
        )
        val error = vm.error.value
        assertNotNull("both failure kinds must appear, not just the last one set", error)
        assertTrue(error!!.contains("already optimized", ignoreCase = true))
        assertTrue(error.contains("could not restore", ignoreCase = true))
    }

    @Test
    fun onWriteGranted_recoveryKind_allRestoredSuccessfully_leavesErrorUntouched() = runTest {
        coEvery { compressor.recover() } returns
            listOf(CompressionOutcome.Failed("uri://x", "recovered an interrupted write", restored = true))
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
            vm, "_pendingWrite"
        ).value = KeepixViewModel.PendingWriteRequest(KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://x"))

        vm.onWriteGranted()

        assertNull("a clean recovery must not raise any error", vm.error.value)
    }

    // --- Minor 3: requestCompression() must not silently no-op --------------

    @Test
    fun requestCompression_whileAWriteIsAlreadyPending_setsAStatusMessageInsteadOfNoOp() = runTest {
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
            vm, "_pendingWrite"
        ).value = KeepixViewModel.PendingWriteRequest(KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://x"))
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a")

        vm.requestCompression()

        assertNotNull(
            "the user's tap must not be silently swallowed",
            vm.compressionStatus.value
        )
    }

    // --- Minor 1: the orphan backup sweep runs at recovery time --------------

    @Test
    fun checkForInterruptedCompressions_sweepsOrphanedBackupsEvenWhenNothingNeedsRecovering() = runTest {
        val dao = mockk<CompressionJournalDao>()
        coEvery { dao.getAll() } returns emptyList()
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor, compressionJournalDao = dao)

        vm.checkForInterruptedCompressions()

        // The sweep needs no write grant -- it only touches this app's own
        // backup directory -- so it must run even when there is no
        // interrupted write to recover, unlike the recovery request itself.
        coVerify(exactly = 1) { compressor.sweepOrphanedBackups(emptyList()) }
        assertNull("nothing to recover means nothing should be armed", vm.pendingWrite.value)
    }

    @Test
    fun checkForInterruptedCompressions_sweepsWithTheExactRowsUsedToArmRecovery() = runTest {
        val rows = listOf(CompressionJournalEntity("uri://x", "/tmp/x.bak", 1L, 1L))
        val dao = mockk<CompressionJournalDao>()
        coEvery { dao.getAll() } returns rows
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor, compressionJournalDao = dao)

        vm.checkForInterruptedCompressions()

        // Must sweep against the SAME rows used to decide what needs
        // recovering, not a fresh (potentially different) read -- sweeping
        // against a stale snapshot could delete a backup a live row depends on.
        coVerify(exactly = 1) { compressor.sweepOrphanedBackups(rows) }
        assertEquals(KeepixViewModel.WriteRequestKind.RECOVERY, vm.pendingWrite.value?.kind)
    }
}
