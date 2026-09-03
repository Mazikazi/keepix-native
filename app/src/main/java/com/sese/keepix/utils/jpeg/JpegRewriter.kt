package com.sese.keepix.utils.jpeg

import java.io.ByteArrayOutputStream

/**
 * What a rewrite of one file would remove.
 *
 * @param droppedSegmentOffsets [JpegSegment.offset] of every segment to omit.
 * @param truncateAt exclusive end of the output, i.e. just past the primary EOI.
 * @param bytesSaved dropped segments plus everything after the primary EOI.
 * @param outputSize the resulting file's size.
 */
data class StripPlan(
    val droppedSegmentOffsets: Set<Int>,
    val truncateAt: Int,
    val bytesSaved: Int,
    val outputSize: Int
)

/**
 * Rebuilds a JPEG without its MPF payload.
 *
 * The strip policy is the whole of spec §5 and lives here, next to the mechanism
 * that applies it, because the two change together. Its shape is a deliberate
 * allow-nothing default: a segment is dropped ONLY if positively identified as
 * MPF. Everything else -- ICC, XMP, EXIF, JFIF, and anything the parser could
 * not name -- is copied verbatim.
 *
 * Removing ICC in particular would leave pixels identical while rendering a
 * Display-P3 photo oversaturated in any colour-managed viewer: visibly wrong
 * output, which is exactly the failure this feature exists to avoid.
 */
object JpegRewriter {

    /** Spec §8: a rewrite must clear BOTH floors, strictly. */
    const val MIN_SAVING_BYTES: Int = 20 * 1024
    const val MIN_SAVING_RATIO: Double = 0.05

    private fun isMpf(s: JpegSegment): Boolean =
        s.marker == JpegMarkers.APP2 && s.identifier == "MPF"

    /**
     * The MPF secondary image is a whole second JPEG concatenated after the
     * primary EOI, so truncating there removes it. Its APP2 index segment goes
     * too -- left behind, it would point at bytes that no longer exist.
     */
    fun planStrip(structure: JpegStructure): StripPlan {
        val dropped = structure.segments.filter(::isMpf)
        val saved = dropped.sumOf { it.length } + structure.trailingBytes
        return StripPlan(
            droppedSegmentOffsets = dropped.map { it.offset }.toSet(),
            truncateAt = structure.primaryEndOffset,
            bytesSaved = saved,
            outputSize = structure.totalLength - saved
        )
    }

    /**
     * Below either floor the original is kept. Churning a user's photo library
     * for a rounding error is all risk and no reward, and every rewrite carries
     * some risk however small.
     */
    fun meetsSavingFloor(plan: StripPlan, originalSize: Int): Boolean {
        if (originalSize <= 0) return false
        return plan.bytesSaved > MIN_SAVING_BYTES &&
            plan.bytesSaved.toDouble() / originalSize > MIN_SAVING_RATIO
    }

    /**
     * Copies [bytes] into a new array, omitting the planned segments and stopping
     * at the primary EOI.
     *
     * Everything between two dropped segments is copied as one raw span, so the
     * entropy-coded scan data is moved without ever being examined -- the output
     * is bit-identical in pixels because the pixel bytes ARE the input's bytes.
     */
    fun rewrite(bytes: ByteArray, structure: JpegStructure, plan: StripPlan): ByteArray {
        require(plan.truncateAt <= bytes.size) { "truncateAt is past the end of the input" }
        val out = ByteArrayOutputStream(plan.outputSize)
        var cursor = 0
        // segments are in ascending offset order by construction.
        for (segment in structure.segments) {
            if (segment.offset !in plan.droppedSegmentOffsets) continue
            out.write(bytes, cursor, segment.offset - cursor)
            cursor = segment.offset + segment.length
        }
        out.write(bytes, cursor, plan.truncateAt - cursor)
        return out.toByteArray()
    }
}
