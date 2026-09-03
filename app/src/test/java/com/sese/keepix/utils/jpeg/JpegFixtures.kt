package com.sese.keepix.utils.jpeg

/**
 * Assembles synthetic JPEGs byte-by-byte so parser and rewriter tests can state
 * exactly what structure they are exercising. Deliberately hand-built rather
 * than loaded from binary test resources: a test that fails here should point at
 * a named shape ("progressive with two scans"), not at an opaque blob.
 */
object JpegFixtures {

    fun soi(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    fun eoi(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** `FF <marker> <len hi> <len lo> <payload>`, where len covers itself + payload. */
    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        require(len <= 0xFFFF) { "segment payload too large for a 16-bit length" }
        return byteArrayOf(
            0xFF.toByte(), marker.toByte(),
            (len shr 8).toByte(), (len and 0xFF).toByte()
        ) + payload
    }

    /** An APPn segment carrying a NUL-terminated ASCII identifier. */
    fun app(marker: Int, identifier: String, body: ByteArray = ByteArray(0)): ByteArray =
        segment(marker, identifier.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + body)

    /** A minimal SOF0 so fixtures look like real images. */
    fun sof0(width: Int = 8, height: Int = 8): ByteArray = segment(
        0xC0,
        byteArrayOf(
            8,
            (height shr 8).toByte(), (height and 0xFF).toByte(),
            (width shr 8).toByte(), (width and 0xFF).toByte(),
            1, 1, 0x11, 0
        )
    )

    /** A SOS segment header followed by raw entropy bytes. */
    fun sos(scan: ByteArray): ByteArray =
        segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) + scan

    /** Entropy data containing a stuffed FF, a restart marker, and plain bytes. */
    fun trickyScan(): ByteArray = byteArrayOf(
        0x12, 0x34,
        0xFF.toByte(), 0x00,             // stuffed FF -- must NOT end the scan
        0x56,
        0xFF.toByte(), 0xD0.toByte(),    // RST0 -- must NOT end the scan
        0x78, 0x9A.toByte()
    )

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    /**
     * An APP2/MPF payload declaring [imageSizes].size images with those byte
     * lengths. Layout after `"MPF "`: the 8-byte TIFF header, an IFD holding
     * one entry (2 + 12 + 4 bytes), then the MP entry array.
     *
     * Shared rather than re-derived per test class: three hand-rolled TIFF
     * writers that must agree byte-for-byte is three chances to encode the same
     * misunderstanding three different ways, and a fixture bug here would look
     * exactly like a parser bug. Tasks 2, 5 and 6 all build on this.
     */
    fun mpfPayload(imageSizes: List<Long>, littleEndian: Boolean = false): ByteArray {
        val tiff = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            if (littleEndian) { tiff.write(v and 0xFF); tiff.write(v shr 8) }
            else { tiff.write(v shr 8); tiff.write(v and 0xFF) }
        }
        fun u32(v: Long) {
            val b = intArrayOf(
                ((v shr 24) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(),
                ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt()
            )
            if (littleEndian) for (i in 3 downTo 0) tiff.write(b[i]) else for (i in 0..3) tiff.write(b[i])
        }

        if (littleEndian) { tiff.write('I'.code); tiff.write('I'.code) }
        else { tiff.write('M'.code); tiff.write('M'.code) }
        u16(0x002A)
        u32(8L)                              // the first IFD follows the header
        u16(1)                               // one entry
        u16(0xB002)                          // MP Entry
        u16(7)                               // UNDEFINED
        u32((imageSizes.size * 16).toLong())
        u32(8L + 2 + 12 + 4)                 // entry array offset, TIFF-relative
        u32(0L)                              // no next IFD
        for (s in imageSizes) { u32(0L); u32(s); u32(0L); u16(0); u16(0) }

        return "MPF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + tiff.toByteArray()
    }
}
