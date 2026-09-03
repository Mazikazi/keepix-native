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
     *
     * Trailing bytes alone, with no MPF segment present, are never a reason to
     * truncate: an MPF index is the only positive signal this function has that
     * whatever follows the primary EOI is the specific thing this feature exists
     * to remove. Without one, "there are bytes after EOI" describes a motion
     * photo's appended video just as well as a dual-camera secondary, and this
     * function has no way to tell those apart -- so it must not offer a saving
     * for either. This makes the zero-saving outcome self-justifying: callers no
     * longer need to trust a filter that lives in a different file (the
     * analyser) to keep a file like that out of the destructive path.
     *
     * The no-MPF plan below sets `truncateAt = structure.totalLength` --
     * i.e. no truncation at all -- rather than `primaryEndOffset`, precisely so
     * that "no truncation" is what the plan actually says whenever it also says
     * `bytesSaved = 0`. Setting `truncateAt = primaryEndOffset` here instead
     * would make the plan self-contradictory (it would claim zero bytes saved
     * while its own `truncateAt` describes truncating away every trailing
     * byte), and [rewrite]'s `truncateAt == primaryEndOffset` guard would let
     * such a plan straight through: with an empty `droppedSegmentOffsets`, it
     * would silently truncate a motion photo's appended video while reporting
     * that nothing was saved. Anchoring `truncateAt` to `totalLength` instead
     * turns that mistake into a loud [rewrite] `require` failure if such a plan
     * is ever fed to it, rather than a silent, uncaught-by-any-check strip.
     */
    fun planStrip(structure: JpegStructure): StripPlan {
        val dropped = structure.segments.filter(::isMpf)
        if (dropped.isEmpty()) {
            return StripPlan(
                droppedSegmentOffsets = emptySet(),
                truncateAt = structure.totalLength,
                bytesSaved = 0,
                outputSize = structure.totalLength
            )
        }
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
        val structureOffsets = structure.segments.map { it.offset }.toSet()
        val unknownDropped = plan.droppedSegmentOffsets - structureOffsets
        require(unknownDropped.isEmpty()) {
            "plan drops offsets absent from structure.segments: $unknownDropped"
        }
        require(plan.truncateAt == structure.primaryEndOffset) {
            "plan.truncateAt (${plan.truncateAt}) does not match " +
                "structure.primaryEndOffset (${structure.primaryEndOffset})"
        }
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
