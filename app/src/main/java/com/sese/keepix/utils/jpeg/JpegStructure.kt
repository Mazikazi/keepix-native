package com.sese.keepix.utils.jpeg

/**
 * JPEG marker bytes -- the SECOND byte of each `FF xx` pair.
 *
 * Deliberately no Android imports anywhere in this package: everything here is
 * exercised on the host JVM, because this is the layer where a bug corrupts a
 * user's photo.
 */
object JpegMarkers {
    const val TEM = 0x01
    const val RST0 = 0xD0
    const val RST7 = 0xD7
    const val SOI = 0xD8
    const val EOI = 0xD9
    const val SOS = 0xDA
    const val APP0 = 0xE0
    const val APP1 = 0xE1
    const val APP2 = 0xE2
    const val APP15 = 0xEF

    /** Markers that carry no length field and no payload. */
    fun isStandalone(marker: Int): Boolean =
        marker == SOI || marker == EOI || marker == TEM || marker in RST0..RST7

    fun isApp(marker: Int): Boolean = marker in APP0..APP15
}

/**
 * One marker segment.
 *
 * @param marker the second byte of the `FF xx` pair.
 * @param offset index of the leading `0xFF` in the file.
 * @param length total bytes: `FF`, marker, the 2 length bytes, and the payload.
 * @param identifier for APPn segments, the NUL-terminated ASCII tag that names
 *   the payload format (`"Exif"`, `"ICC_PROFILE"`, `"MPF"`, `"JFIF"`). Null for
 *   non-APP segments and for an APP segment whose leading bytes are not a
 *   plausible identifier -- an unrecognised segment is KEPT, so failing to name
 *   one is always safe.
 */
data class JpegSegment(
    val marker: Int,
    val offset: Int,
    val length: Int,
    val identifier: String?
) {
    /** First byte after the 2-byte length field. */
    val payloadOffset: Int get() = offset + 4
    val payloadLength: Int get() = length - 4
}

/** Everything up to (not including) the first SOS. */
data class JpegHeader(
    val segments: List<JpegSegment>,
    /** Index of the `0xFF` of the `FFDA` pair. */
    val scanStartOffset: Int
)

/** A fully-walked file. */
data class JpegStructure(
    val segments: List<JpegSegment>,
    /** Exclusive: the index just past the primary image's `FFD9`. */
    val primaryEndOffset: Int,
    val totalLength: Int
) {
    /**
     * Bytes after the primary EOI. For a dual-camera JPEG this is the MPF
     * secondary image, stored as a whole second JPEG concatenated onto the first.
     */
    val trailingBytes: Int get() = totalLength - primaryEndOffset
}

/**
 * The outcome of parsing a PREFIX of a file. The three-way result is what lets
 * the analyser read 64 KB at a time instead of whole multi-megabyte files:
 * [NeedMoreBytes] means "read more and ask again", which a nullable return could
 * not distinguish from "this is not a JPEG".
 */
sealed interface HeaderResult {
    data class Ok(val header: JpegHeader) : HeaderResult
    data object NeedMoreBytes : HeaderResult
    data object Malformed : HeaderResult
}
