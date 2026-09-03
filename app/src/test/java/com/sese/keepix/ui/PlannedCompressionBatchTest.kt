package com.sese.keepix.ui

import com.sese.keepix.utils.ReclaimEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Important 3: SettingsScreen's card and confirmation dialog must promise
 * exactly what a single Optimize tap will do -- the first
 * [MAX_COMPRESSION_BATCH] eligible files -- not the whole-library totals a
 * scan found. [plannedCompressionBatch] is what computes that; these tests
 * exercise it directly, without needing Compose test infrastructure.
 */
class PlannedCompressionBatchTest {

    private fun estimateOf(bytesPerFile: List<Long>): ReclaimEstimate {
        val uris = bytesPerFile.indices.map { "uri://$it" }
        return ReclaimEstimate(
            scannedCount = uris.size,
            eligibleCount = uris.size,
            estimatedBytes = bytesPerFile.sum(),
            eligibleUris = uris,
            eligibleBytesSaved = bytesPerFile
        )
    }

    @Test
    fun underTheCap_plansEveryEligibleFileAndTheirFullByteTotal() {
        val estimate = estimateOf(listOf(100_000L, 200_000L, 50_000L))

        val (count, bytes) = plannedCompressionBatch(estimate)

        assertEquals(3, count)
        assertEquals(350_000L, bytes)
    }

    @Test
    fun exactlyAtTheCap_plansAllOfThem() {
        val perFile = List(MAX_COMPRESSION_BATCH) { 10_000L }
        val estimate = estimateOf(perFile)

        val (count, bytes) = plannedCompressionBatch(estimate)

        assertEquals(MAX_COMPRESSION_BATCH, count)
        assertEquals(MAX_COMPRESSION_BATCH * 10_000L, bytes)
    }

    @Test
    fun overTheCap_plansOnlyTheFirstBatchAndItsByteTotal_notTheWholeLibrary() {
        // 900 eligible photos, only the first 50 (per file scan order) will
        // actually be rewritten by a single Optimize tap.
        val perFile = List(900) { 1_000_000L } // 900 MB total across the library
        val estimate = estimateOf(perFile)

        val (count, bytes) = plannedCompressionBatch(estimate)

        assertEquals(
            "must promise only what requestCompression() actually takes",
            MAX_COMPRESSION_BATCH, count
        )
        assertEquals(
            "must be the byte total for exactly those 50 files, not a " +
                "proportional guess against the 900-photo estimate",
            MAX_COMPRESSION_BATCH * 1_000_000L, bytes
        )
        assertTrue(
            "the capped byte total must be far smaller than the whole-library estimate",
            bytes < estimate.estimatedBytes
        )
    }

    @Test
    fun unevenPerFileSizes_sumsExactlyTheFirstNInScanOrder_notASimpleAverage() {
        // First 2 files are huge, the rest are tiny -- an average-based
        // estimate would badly under-promise here.
        val perFile = listOf(5_000_000L, 5_000_000L) + List(100) { 1_000L }
        val estimate = estimateOf(perFile)
        val cappedAt = 3
        // Directly exercise the summing behaviour with a synthetic smaller
        // cap by slicing the same list plannedCompressionBatch would use.
        val expectedBytesForCapped3 = perFile.take(cappedAt).sum()

        assertEquals(10_001_000L, expectedBytesForCapped3)

        val (count, bytes) = plannedCompressionBatch(estimate)
        assertEquals(MAX_COMPRESSION_BATCH, count)
        assertEquals(perFile.take(MAX_COMPRESSION_BATCH).sum(), bytes)
    }

    @Test
    fun emptyEligibleBytesSaved_defaultsToZeroRatherThanCrashing() {
        // A caller (mostly tests) that doesn't populate eligibleBytesSaved at
        // all must not crash plannedCompressionBatch -- take(n).sum() on an
        // empty list is just 0.
        val estimate = ReclaimEstimate(
            scannedCount = 10, eligibleCount = 10, estimatedBytes = 5_000_000L,
            eligibleUris = List(10) { "uri://$it" }
        )

        val (count, bytes) = plannedCompressionBatch(estimate)

        assertEquals(10, count)
        assertEquals(0L, bytes)
    }
}
