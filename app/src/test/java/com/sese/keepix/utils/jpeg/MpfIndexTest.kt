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

    @Test
    fun readPrimaryImageSize_hugeFirstIfdOffset_returnsNullNotException() {
        // An MPF with first-IFD offset = 0x80000000 (as Long). Narrowing this
        // unbounded 32-bit value to Int before bounds-checking it yields
        // Int.MIN_VALUE, a negative index that `index + N > end` cannot catch,
        // leading to ArrayIndexOutOfBoundsException instead of null.
        val payload = byteArrayOf(
            // "MPF\0"
            'M'.code.toByte(), 'P'.code.toByte(), 'F'.code.toByte(), 0,
            // TIFF header: big endian
            'M'.code.toByte(), 'M'.code.toByte(),  // byte order
            0x00, 0x2A,                             // magic 0x002A
            0x80.toByte(), 0x00, 0x00, 0x00         // first-IFD offset = 0x80000000
        )
        val (bytes, seg) = segmentFor(payload)
        assertNull(MpfIndex.readPrimaryImageSize(bytes, seg))
    }

    @Test
    fun readPrimaryImageSize_hugeMpEntryArrayOffset_returnsNullNotException() {
        // An MPF with a valid IFD but MP-entry-array offset = 0x80000000 (as Long).
        // Narrowing this unbounded 32-bit value to Int before bounds-checking it
        // yields Int.MIN_VALUE, a negative index that `index + N > end` cannot catch,
        // leading to ArrayIndexOutOfBoundsException instead of null.
        val payload = ByteArray(30)  // "MPF\0" (4) + TIFF header (8) + IFD (18)
        var at = 0

        // "MPF\0"
        payload[at++] = 'M'.code.toByte()
        payload[at++] = 'P'.code.toByte()
        payload[at++] = 'F'.code.toByte()
        payload[at++] = 0

        // TIFF header (big endian)
        payload[at++] = 'M'.code.toByte()  // byte order
        payload[at++] = 'M'.code.toByte()
        payload[at++] = 0x00  // magic
        payload[at++] = 0x2A
        payload[at++] = 0x00  // first-IFD offset = 8 (into TIFF)
        payload[at++] = 0x00
        payload[at++] = 0x00
        payload[at++] = 0x08

        // IFD at offset 8 in TIFF
        payload[at++] = 0x00  // entry count = 1
        payload[at++] = 0x01
        // MP Entry
        payload[at++] = 0xB0.toByte()  // MP Entry tag high byte
        payload[at++] = 0x02             // MP Entry tag low byte
        payload[at++] = 0x00  // type = 7
        payload[at++] = 0x07
        payload[at++] = 0x00  // value count = 16
        payload[at++] = 0x00
        payload[at++] = 0x00
        payload[at++] = 0x10
        payload[at++] = 0x80.toByte()  // array offset = 0x80000000 (huge)
        payload[at++] = 0x00
        payload[at++] = 0x00
        payload[at++] = 0x00
        // Next IFD offset = 0
        payload[at++] = 0x00
        payload[at++] = 0x00
        payload[at++] = 0x00
        payload[at++] = 0x00

        val (bytes, seg) = segmentFor(payload)
        assertNull(MpfIndex.readPrimaryImageSize(bytes, seg))
    }
}
