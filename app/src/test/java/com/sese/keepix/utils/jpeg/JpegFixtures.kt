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
}
