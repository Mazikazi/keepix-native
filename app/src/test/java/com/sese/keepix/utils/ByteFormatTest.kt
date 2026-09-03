package com.sese.keepix.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {

    @Test
    fun zeroBytes_isGenuineZero() {
        assertEquals("0 MB", formatMegabytes(0L))
    }

    @Test
    fun oneByte_isNeverZero() {
        assertEquals("< 1 MB", formatMegabytes(1L))
    }

    @Test
    fun justUnderOneMegabyte_isNeverZero() {
        assertEquals("< 1 MB", formatMegabytes(1024L * 1024L - 1L))
    }

    @Test
    fun exactlyOneMegabyte_rendersAsOneMB() {
        assertEquals("1 MB", formatMegabytes(1024L * 1024L))
    }

    @Test
    fun justOverOneMegabyte_flooredToOneMB() {
        assertEquals("1 MB", formatMegabytes(1024L * 1024L + 1L))
    }

    @Test
    fun largeMultiMegabyteValue_rendersFlooredWholeMB() {
        // 5.5 MB worth of bytes should floor to 5 MB.
        val bytes = (5.5 * 1024 * 1024).toLong()
        assertEquals("5 MB", formatMegabytes(bytes))
    }

    @Test
    fun regressionCase_smallEligibleSavingIsNeverReportedAsZero() {
        // The concrete failure mode from the finding: a ~21 KB embedded
        // duplicate clears the 20 KB eligibility floor but is well under
        // 1 MB, so plain integer division rendered it as "0 MB".
        assertEquals("< 1 MB", formatMegabytes(21_000L))
    }
}
