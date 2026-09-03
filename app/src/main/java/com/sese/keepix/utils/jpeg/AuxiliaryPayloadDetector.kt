package com.sese.keepix.utils.jpeg

/**
 * Detects, from header bytes alone, that a JPEG's trailing payload is something
 * other than a discardable dual-camera duplicate.
 *
 * The MPF container format does not distinguish "throwaway secondary capture"
 * from "the HDR gain map that makes this photo render in HDR" or "the video
 * half of a motion photo" -- all three are stored the same way, as a second
 * JPEG (or, for a motion photo, an MP4) concatenated after the primary image's
 * EOI, sometimes announced by an APP2/MPF index and sometimes not. The MPF
 * structure alone cannot tell them apart.
 *
 * What CAN tell them apart is the XMP metadata the primary image carries
 * alongside it, in an APP1 segment: Google's Ultra HDR v1.0 (the GContainer
 * directory), and both generations of Google/Samsung motion photos all
 * self-describe with a recognisable ASCII marker string in that XMP. Any one
 * of those markers found anywhere in an APP1 payload makes the file
 * ineligible for the MPF-stripping rewrite, full stop. It is deliberately not
 * trying to fully parse XMP/RDF -- a plain byte-substring search is enough to
 * recognise these markers, and parsing more than that would be more code with
 * no more safety.
 *
 * This is fail-OPEN, not fail-closed, with respect to any auxiliary format it
 * has not been taught: [hasAuxiliaryPayloadMarker] proves the ABSENCE of six
 * known markers on the PRIMARY image, never the presence of an actual
 * discardable duplicate. Nothing here confirms that the trailing bytes are
 * safe to drop. Apple's "Most Compatible" HDR JPEG and an ISO 21496-1 gain map
 * (Ultra HDR v1.1 and newer) both carry none of these six markers on the
 * primary -- the gain map self-describes only in its OWN XMP, inside the
 * trailing bytes this scan never looks at. See [hasGainMapInTrailer] for the
 * check that covers that case.
 *
 * Zero Android imports: this runs in the same pure, JVM-testable layer as the
 * rest of `utils/jpeg`, because a bug here is a bug in the destructive path.
 */
object AuxiliaryPayloadDetector {

    /**
     * ASCII byte sequences that, found anywhere in an APP1 payload, indicate the
     * file's trailing bytes are NOT a discardable duplicate:
     *  - "hdrgm:" -- the Ultra HDR gain-map XMP namespace prefix.
     *  - the GContainer directory namespace -- describes appended items
     *    (including but not limited to gain maps) by content.
     *  - "Item:Semantic" -- the GContainer property naming what an item IS
     *    (GainMap, MotionPhoto, ...).
     *  - "GCamera:MicroVideo" / "MicroVideoOffset" -- legacy Google Motion
     *    Photo markers.
     *  - "MotionPhoto" -- Samsung's and Google's own motion-photo markers.
     */
    private val MARKERS: List<ByteArray> = listOf(
        "hdrgm:",
        "http://ns.google.com/photos/1.0/container/",
        "Item:Semantic",
        "GCamera:MicroVideo",
        "MicroVideoOffset",
        "MotionPhoto"
    ).map { it.toByteArray(Charsets.US_ASCII) }

    /**
     * True if any [segments] entry is an APP1 segment whose payload contains one
     * of [MARKERS]. Callers should treat true as "do not strip this file's
     * trailing bytes, no matter what else looks eligible."
     */
    fun hasAuxiliaryPayloadMarker(segments: List<JpegSegment>, bytes: ByteArray): Boolean {
        for (segment in segments) {
            if (segment.marker != JpegMarkers.APP1) continue
            val start = segment.payloadOffset
            val end = minOf(segment.offset + segment.length, bytes.size)
            if (start >= end) continue
            for (marker in MARKERS) {
                if (containsAt(bytes, start, end, marker)) return true
            }
        }
        return false
    }

    /** Plain byte-substring search for [marker] within `bytes[start, end)`. */
    private fun containsAt(bytes: ByteArray, start: Int, end: Int, marker: ByteArray): Boolean {
        val lastStart = end - marker.size
        var i = start
        while (i <= lastStart) {
            var j = 0
            while (j < marker.size && bytes[i + j] == marker[j]) j++
            if (j == marker.size) return true
            i++
        }
        return false
    }

    /**
     * ASCII byte sequences that self-describe a gain map wherever it is
     * embedded, whoever wrote it:
     *  - "hdrgm:" -- the same Ultra HDR gain-map XMP namespace prefix as
     *    above, but here matched against the SECONDARY image's own bytes
     *    rather than the primary's APP1 segments.
     *  - "http://ns.adobe.com/hdr-gain-map/" -- the gain-map XMP namespace
     *    URI itself, which Apple's "Most Compatible" HDR JPEG and an ISO
     *    21496-1 gain map (Ultra HDR v1.1+) carry in the gain map's own XMP.
     */
    private val TRAILER_GAIN_MAP_MARKERS: List<ByteArray> = listOf(
        "hdrgm:",
        "http://ns.adobe.com/hdr-gain-map/"
    ).map { it.toByteArray(Charsets.US_ASCII) }

    /**
     * True if `bytes[start, end)` -- the region AFTER the primary image's EOI,
     * i.e. exactly what an MPF strip would discard -- contains a gain-map
     * self-description marker.
     *
     * This exists because [hasAuxiliaryPayloadMarker] only ever looks at the
     * primary image's APP1 segments, which is by design (the analyser can
     * only afford to read a header-sized prefix of the file) but leaves a
     * real gap: Apple's "Most Compatible" HDR JPEG and an ISO 21496-1 gain map
     * put no marker on the primary at all -- the gain map describes itself
     * only in its own XMP, which lives in exactly the bytes this function
     * scans. A gain map always self-describes this way, whoever embedded it;
     * an ordinary dual-camera secondary is a plain JPEG carrying neither
     * string, so this does not risk over-blocking the common case.
     *
     * Deliberately NOT usable from the header-only analyser: it requires the
     * bytes after the primary EOI, which the analyser by design does not
     * read. It is meant to be the authoritative, whole-file check the
     * destructive rewrite path performs for itself rather than trusting the
     * analyser's optimistic, marker-checked estimate.
     */
    fun hasGainMapInTrailer(bytes: ByteArray, start: Int, end: Int): Boolean {
        val safeEnd = minOf(end, bytes.size)
        if (start >= safeEnd) return false
        for (marker in TRAILER_GAIN_MAP_MARKERS) {
            if (containsAt(bytes, start, safeEnd, marker)) return true
        }
        return false
    }
}
