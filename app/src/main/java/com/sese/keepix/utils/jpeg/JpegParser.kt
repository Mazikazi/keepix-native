package com.sese.keepix.utils.jpeg

/**
 * Walks a JPEG's marker chain. Reads only; never modifies and never decodes.
 *
 * The walker has two modes and alternates between them, which is what makes it
 * correct for progressive JPEGs as well as baseline ones: marker-chain mode
 * reads `FF xx <len> <payload>` segments, and after a SOS it switches to
 * entropy mode, which skips opaque scan bytes until a real marker appears.
 */
object JpegParser {

    /** Cap on how far an APPn identifier is searched for its NUL terminator. */
    private const val MAX_IDENTIFIER_LENGTH = 32

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    /**
     * Parses the marker chain of the first [available] bytes of [bytes], stopping
     * at the SOS. Use this when only a prefix of the file has been read.
     */
    fun parseHeader(bytes: ByteArray, available: Int = bytes.size): HeaderResult {
        val n = minOf(available, bytes.size)
        if (n < 2) return HeaderResult.NeedMoreBytes
        if (u8(bytes, 0) != 0xFF || u8(bytes, 1) != JpegMarkers.SOI) return HeaderResult.Malformed

        val segments = mutableListOf<JpegSegment>()
        var p = 2
        while (true) {
            if (p >= n) return HeaderResult.NeedMoreBytes
            if (u8(bytes, p) != 0xFF) return HeaderResult.Malformed

            // A run of FF bytes before a marker is legal fill.
            var q = p
            while (q < n && u8(bytes, q) == 0xFF) q++
            if (q >= n) return HeaderResult.NeedMoreBytes

            val marker = u8(bytes, q)
            if (marker == JpegMarkers.SOS) return HeaderResult.Ok(JpegHeader(segments, q - 1))
            if (marker == JpegMarkers.EOI) return HeaderResult.Malformed  // EOI before any scan
            if (JpegMarkers.isStandalone(marker)) { p = q + 1; continue }

            if (q + 2 >= n) return HeaderResult.NeedMoreBytes
            val declared = u16(bytes, q + 1)
            if (declared < 2) return HeaderResult.Malformed

            val segStart = q - 1
            val segLength = 2 + declared
            if (segStart + segLength > n) {
                // Could be either a truncated read or a corrupt length. Only the
                // full file can tell, so ask for more rather than condemning it.
                return HeaderResult.NeedMoreBytes
            }
            segments += JpegSegment(marker, segStart, segLength, identifierAt(bytes, marker, segStart, segLength))
            p = segStart + segLength
        }
    }

    /**
     * Walks the whole of [bytes] and locates the primary image's EOI. Returns null
     * for anything that is not a structurally coherent JPEG -- a null here means
     * "skip this file", never "rewrite it anyway".
     */
    fun parseFull(bytes: ByteArray): JpegStructure? {
        val n = bytes.size
        if (n < 4) return null
        if (u8(bytes, 0) != 0xFF || u8(bytes, 1) != JpegMarkers.SOI) return null

        val segments = mutableListOf<JpegSegment>()
        var p = 2
        while (true) {
            if (p >= n) return null
            if (u8(bytes, p) != 0xFF) return null

            var q = p
            while (q < n && u8(bytes, q) == 0xFF) q++
            if (q >= n) return null

            val marker = u8(bytes, q)
            if (marker == JpegMarkers.EOI) return JpegStructure(segments, q + 1, n)
            if (JpegMarkers.isStandalone(marker)) { p = q + 1; continue }

            if (q + 2 >= n) return null
            val declared = u16(bytes, q + 1)
            if (declared < 2) return null

            val segStart = q - 1
            val segLength = 2 + declared
            if (segStart + segLength > n) return null
            segments += JpegSegment(marker, segStart, segLength, identifierAt(bytes, marker, segStart, segLength))
            p = segStart + segLength

            if (marker == JpegMarkers.SOS) {
                p = skipEntropyCodedData(bytes, p, n) ?: return null
            }
        }
    }

    /**
     * Advances past entropy-coded scan data and returns the index of the `0xFF`
     * that begins the next real marker.
     *
     * Two byte sequences inside a scan look like markers but are not, and treating
     * either as one truncates the image:
     *  - `FF 00` is a stuffed literal `0xFF` sample byte.
     *  - `FF D0`..`FF D7` are restart markers, which are part of the scan.
     *
     * The scan bytes themselves are never interpreted beyond this -- that is the
     * whole basis of the lossless claim.
     */
    private fun skipEntropyCodedData(bytes: ByteArray, from: Int, n: Int): Int? {
        var i = from
        while (i < n) {
            if (u8(bytes, i) != 0xFF) { i++; continue }
            var j = i
            while (j < n && u8(bytes, j) == 0xFF) j++
            if (j >= n) return null
            val next = u8(bytes, j)
            if (next == 0x00 || next in JpegMarkers.RST0..JpegMarkers.RST7) { i = j + 1; continue }
            return j - 1
        }
        return null
    }

    /**
     * Reads an APPn segment's NUL-terminated ASCII identifier. Returns null unless
     * the payload really does start with printable ASCII followed by a NUL --
     * anything else is left unidentified, and an unidentified segment is kept.
     */
    private fun identifierAt(bytes: ByteArray, marker: Int, segStart: Int, segLength: Int): String? {
        if (!JpegMarkers.isApp(marker)) return null
        val start = segStart + 4
        val limit = minOf(segStart + segLength, start + MAX_IDENTIFIER_LENGTH, bytes.size)
        var i = start
        while (i < limit) {
            val c = u8(bytes, i)
            if (c == 0) {
                if (i == start) return null
                return String(bytes, start, i - start, Charsets.US_ASCII)
            }
            if (c < 0x20 || c > 0x7E) return null
            i++
        }
        return null
    }
}
