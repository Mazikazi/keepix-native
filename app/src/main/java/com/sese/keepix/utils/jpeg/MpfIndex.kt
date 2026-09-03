package com.sese.keepix.utils.jpeg

/**
 * Reads the primary image's declared byte length out of an APP2/MPF index.
 *
 * READ-ONLY AND ADVISORY. Two uses, neither of which trusts it with a
 * destructive decision:
 *  - the analyser's estimate, which only needs a file's header rather than all
 *    of it, so a whole library can be measured affordably;
 *  - a cross-check against the authoritative EOI walk before a rewrite.
 *
 * Every parse failure returns null. An MPF index that cannot be read is not an
 * error -- it simply means the estimate falls back to zero and the cross-check
 * is skipped.
 */
object MpfIndex {

    private const val MP_ENTRY_TAG = 0xB002
    private const val MP_ENTRY_SIZE = 16
    private const val TIFF_MAGIC = 0x002A

    fun readPrimaryImageSize(bytes: ByteArray, segment: JpegSegment): Long? {
        if (segment.marker != JpegMarkers.APP2 || segment.identifier != "MPF") return null

        // The TIFF header starts right after "MPF\0"; every offset below is
        // relative to it, not to the file.
        val tiff = segment.payloadOffset + 4
        val end = segment.offset + segment.length
        if (end > bytes.size || tiff + 8 > end) return null

        val little = when {
            bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte() -> true
            bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (u16(bytes, tiff + 2, little) != TIFF_MAGIC) return null

        // TIFF offsets are attacker-controlled 32-bit values; bound them against
        // the segment's own (16-bit-declared) size BEFORE converting to Int and
        // adding to `tiff`. A raw `tiff + offset.toInt()` on an offset near
        // 0xFFFFFFFF overflows to a negative index, and a bounds check written as
        // `index + N > end` does not catch a negative index -- it would fall
        // through to an out-of-bounds read that throws instead of returning null.
        val span = end - tiff

        val ifdOffset = u32(bytes, tiff + 4, little)
        if (ifdOffset <= 0 || ifdOffset > span) return null
        val ifd = tiff + ifdOffset.toInt()
        if (ifd + 2 > end) return null

        val entryCount = u16(bytes, ifd, little)
        if (entryCount <= 0 || ifd + 2 + entryCount * 12 > end) return null

        for (i in 0 until entryCount) {
            val e = ifd + 2 + i * 12
            if (u16(bytes, e, little) != MP_ENTRY_TAG) continue

            val valueCount = u32(bytes, e + 4, little)
            if (valueCount < MP_ENTRY_SIZE) return null

            val arrayOffset = u32(bytes, e + 8, little)
            if (arrayOffset <= 0 || arrayOffset > span) return null
            val array = tiff + arrayOffset.toInt()
            if (array + MP_ENTRY_SIZE > end) return null

            // Bytes 4..7 of MP entry 0: the primary image's size, measured from
            // the start of the file.
            val size = u32(bytes, array + 4, little)
            return if (size > 0) size else null
        }
        return null
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int, little: Boolean): Int =
        if (little) (u8(b, i + 1) shl 8) or u8(b, i)
        else (u8(b, i) shl 8) or u8(b, i + 1)

    private fun u32(b: ByteArray, i: Int, little: Boolean): Long =
        if (little) {
            (u8(b, i + 3).toLong() shl 24) or (u8(b, i + 2).toLong() shl 16) or
                (u8(b, i + 1).toLong() shl 8) or u8(b, i).toLong()
        } else {
            (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
                (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
        }
}
