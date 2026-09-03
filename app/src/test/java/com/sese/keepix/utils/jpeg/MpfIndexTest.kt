package com.sese.keepix.utils.jpeg

import com.sese.keepix.utils.jpeg.JpegFixtures.mpfPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpfIndexTest {

    private fun segmentFor(payload: ByteArray): Pair<ByteArray, JpegSegment> {
        val file = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.segment(JpegMarkers.APP2, payload))
        val seg = JpegParser.parseHeader(
            JpegFixtures.concat(file, JpegFixtures.sof0(), JpegFixtures.sos(byteArrayOf(1)), JpegFixtures.eoi())
        ).let { (it as HeaderResult.Ok).header.segments.first() }
        return JpegFixtures.concat(file, JpegFixtures.sof0(), JpegFixtures.sos(byteArrayOf(1)), JpegFixtures.eoi()) to seg
    }

    @Test
    fun readPrimaryImageSize_bigEndian_returnsFirstEntrySize() {
        val (bytes, seg) = segmentFor(mpfPayload(listOf(123456L, 7890L)))
        assertEquals(123456L, MpfIndex.readPrimaryImageSize(bytes, seg))
    }

    @Test
    fun readPrimaryImageSize_littleEndian_returnsFirstEntrySize() {
        val (bytes, seg) = segmentFor(mpfPayload(listOf(999L, 111L), littleEndian = true))
        assertEquals(999L, MpfIndex.readPrimaryImageSize(bytes, seg))
    }

    @Test
    fun readPrimaryImageSize_notAnMpfSegment_returnsNull() {
        val (bytes, seg) = segmentFor("ICC_PROFILE".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + ByteArray(20))
        assertNull(MpfIndex.readPrimaryImageSize(bytes, seg))
    }

    @Test
    fun readPrimaryImageSize_truncatedOrCorrupt_returnsNullRatherThanGuessing() {
        // Valid identifier, garbage TIFF.
        val (b1, s1) = segmentFor("MPF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + ByteArray(12))
        assertNull(MpfIndex.readPrimaryImageSize(b1, s1))

        // Correct header, but the entry array offset points past the segment.
        val good = mpfPayload(listOf(500L))
        val truncated = good.copyOf(good.size - 8)
        val (b2, s2) = segmentFor(truncated)
        assertNull(MpfIndex.readPrimaryImageSize(b2, s2))
    }
}
