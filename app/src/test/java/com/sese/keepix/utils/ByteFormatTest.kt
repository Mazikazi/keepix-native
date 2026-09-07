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

    @Test
    fun `formatSizeShort keeps one decimal where the design shows one`() {
        assertEquals("4.2 MB", formatSizeShort(4_404_019L))
        assertEquals("3.3 MB", formatSizeShort(3_500_000L))
    }

    @Test
    fun `formatSizeShort drops the decimal once it stops carrying information`() {
        // Three significant figures is plenty at this size, and "104.9 MB"
        // is just noise on a tile.
        assertEquals("105 MB", formatSizeShort(110_000_000L))
    }

    @Test
    fun `formatSizeShort steps down through KB and bytes`() {
        assertEquals("0 KB", formatSizeShort(0L))
        assertEquals("0 KB", formatSizeShort(-1L))
        assertEquals("512 B", formatSizeShort(512L))
        assertEquals("1023 B", formatSizeShort(1023L))
        assertEquals("1 KB", formatSizeShort(1024L))
        assertEquals("1023 KB", formatSizeShort(1024L * 1024L - 1L))
    }
}
