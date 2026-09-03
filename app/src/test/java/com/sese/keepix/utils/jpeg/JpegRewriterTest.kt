package com.sese.keepix.utils.jpeg

import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegRewriterTest {

    private val scan = ByteArray(2048) { (it % 251).toByte() }

    /** A dual-camera-shaped file: MPF index segment plus a whole trailing JPEG. */
    private fun fileWithMpf(secondaryPayload: Int = 200_000): ByteArray {
        val primary = concat(
            soi(),
            app(JpegMarkers.APP0, "JFIF", ByteArray(9)),
            app(JpegMarkers.APP1, "Exif", ByteArray(2000)),
            app(JpegMarkers.APP2, "ICC_PROFILE", ByteArray(3000)),
            app(JpegMarkers.APP2, "MPF", ByteArray(80)),
            app(JpegMarkers.APP1, "http://ns.adobe.com/xap/1.0/", ByteArray(400)),
            sof0(), sos(scan), eoi()
        )
        val secondary = concat(soi(), sof0(), sos(ByteArray(secondaryPayload) { 7 }), eoi())
        return concat(primary, secondary)
    }

    @Test
    fun planStrip_dropsOnlyTheMpfSegment_andTruncatesAtThePrimaryEoi() {
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val plan = JpegRewriter.planStrip(s)

        val mpf = s.segments.single { it.marker == JpegMarkers.APP2 && it.identifier == "MPF" }
        assertEquals(setOf(mpf.offset), plan.droppedSegmentOffsets)
        assertEquals(s.primaryEndOffset, plan.truncateAt)
        assertEquals(mpf.length + s.trailingBytes, plan.bytesSaved)
        assertEquals(bytes.size - plan.bytesSaved, plan.outputSize)
    }

    @Test
    fun rewrite_preservesScanDataByteForByte() {
        // The core lossless claim, asserted directly against the actual bytes.
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val out = JpegRewriter.rewrite(bytes, s, JpegRewriter.planStrip(s))

        val outStruct = JpegParser.parseFull(out)!!
        val inSos = s.segments.last { it.marker == JpegMarkers.SOS }
        val outSos = outStruct.segments.last { it.marker == JpegMarkers.SOS }

        val inScan = bytes.copyOfRange(inSos.offset + inSos.length, s.primaryEndOffset)
        val outScan = out.copyOfRange(outSos.offset + outSos.length, outStruct.primaryEndOffset)
        assertArrayEquals("entropy-coded scan data must be untouched", inScan, outScan)
    }

    @Test
    fun rewrite_keepsIccXmpExifAndJfifByteForByte() {
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val out = JpegRewriter.rewrite(bytes, s, JpegRewriter.planStrip(s))
        val outStruct = JpegParser.parseFull(out)!!

        val kept = listOf("JFIF", "Exif", "ICC_PROFILE", "http://ns.adobe.com/xap/1.0/")
        for (id in kept) {
            val a = s.segments.single { it.identifier == id }
            val b = outStruct.segments.single { it.identifier == id }
            assertArrayEquals(
                "segment $id must survive byte-for-byte",
                bytes.copyOfRange(a.offset, a.offset + a.length),
                out.copyOfRange(b.offset, b.offset + b.length)
            )
        }
        assertTrue(
            "MPF must be gone",
            outStruct.segments.none { it.marker == JpegMarkers.APP2 && it.identifier == "MPF" }
        )
    }

    @Test
    fun rewrite_outputIsAStructurallyValidJpegWithNoTrailingBytes() {
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val out = JpegRewriter.rewrite(bytes, s, JpegRewriter.planStrip(s))

        val outStruct = JpegParser.parseFull(out)
        assertNotNull(outStruct)
        assertEquals(0, outStruct!!.trailingBytes)
        assertEquals(out.size, outStruct.primaryEndOffset)
    }

    @Test
    fun planStrip_fileWithNothingToStrip_savesNothing() {
        val bytes = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(100)), sof0(), sos(scan), eoi())
        val s = JpegParser.parseFull(bytes)!!
        val plan = JpegRewriter.planStrip(s)

        assertEquals(0, plan.bytesSaved)
        assertTrue(plan.droppedSegmentOffsets.isEmpty())
        assertFalse(JpegRewriter.meetsSavingFloor(plan, bytes.size))
    }

    @Test
    fun planStrip_trailingBytesWithNoMpfSegment_savesNothingDespiteTrailingBytes() {
        // A motion-photo video (or any other trailer) appended after the
        // primary EOI with no MPF index at all must never be offered a saving:
        // trailing bytes alone are not evidence of a discardable duplicate,
        // only an MPF index is. This is what keeps the destructive rewrite path
        // from depending on a filter that lives only in a different file.
        val primary = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(100)), sof0(), sos(scan), eoi())
        val trailer = ByteArray(500_000) { 0x42 } // e.g. an appended MP4, not a JPEG at all
        val bytes = concat(primary, trailer)
        val s = JpegParser.parseFull(bytes)!!
        check(s.trailingBytes == trailer.size) { "fixture must actually carry trailing bytes" }

        val plan = JpegRewriter.planStrip(s)

        assertEquals(0, plan.bytesSaved)
        assertTrue(plan.droppedSegmentOffsets.isEmpty())
        assertEquals(bytes.size, plan.outputSize)
        assertFalse(JpegRewriter.meetsSavingFloor(plan, bytes.size))
    }

    @Test
    fun planStrip_trailingBytesWithNoMpfSegment_truncateAtIsSelfConsistentWithZeroSavings() {
        // Minor 2: the earlier version of this branch set truncateAt to
        // primaryEndOffset while also claiming bytesSaved = 0 -- an internally
        // contradictory plan (it describes discarding every trailing byte
        // while claiming nothing was saved). meetsSavingFloor rejects
        // bytesSaved = 0 today, so this plan never reaches rewrite() in
        // production, but the safety property should not depend only on that
        // composition. Anchoring truncateAt to totalLength makes the plan
        // consistent on its own, AND makes rewrite()'s existing
        // truncateAt-must-equal-primaryEndOffset guard catch this plan loudly
        // if it were ever fed there directly, instead of silently truncating
        // away a motion photo's video while reporting nothing was saved.
        val primary = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(100)), sof0(), sos(scan), eoi())
        val trailer = ByteArray(500_000) { 0x42 }
        val bytes = concat(primary, trailer)
        val s = JpegParser.parseFull(bytes)!!

        val plan = JpegRewriter.planStrip(s)

        assertEquals("truncateAt must describe an actual no-op, matching bytesSaved = 0", s.totalLength, plan.truncateAt)
        assertEquals(bytes.size, plan.outputSize)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            JpegRewriter.rewrite(bytes, s, plan)
        }
        assertTrue(
            "rewrite() must refuse this plan loudly rather than truncate the trailer silently",
            ex.message!!.contains(s.primaryEndOffset.toString())
        )
    }

    @Test
    fun meetsSavingFloor_requiresBothAbsoluteAndRelativeGains() {
        fun plan(saved: Int) = StripPlan(emptySet(), 0, saved, 0)

        // 25 KB saved out of 10 MB is 0.24% -- fails the ratio.
        assertFalse(JpegRewriter.meetsSavingFloor(plan(25 * 1024), 10 * 1024 * 1024))
        // 10 KB saved out of 100 KB is 10% -- fails the absolute floor.
        assertFalse(JpegRewriter.meetsSavingFloor(plan(10 * 1024), 100 * 1024))
        // Exactly at the absolute-byte boundary (20 KB saved), ratio comfortably
        // clear at 50%: pins the byte comparison's strictness on its own -- this
        // fails if (and only if) the byte check stops being strictly greater-than.
        assertFalse(
            JpegRewriter.meetsSavingFloor(
                plan(JpegRewriter.MIN_SAVING_BYTES),
                2 * JpegRewriter.MIN_SAVING_BYTES
            )
        )
        // Exactly at the ratio boundary (5%), byte count comfortably clear at
        // 100 KB saved: pins the ratio comparison's strictness on its own -- this
        // fails if (and only if) the ratio check stops being strictly greater-than.
        assertFalse(JpegRewriter.meetsSavingFloor(plan(100 * 1024), 20 * 100 * 1024))
        // 200 KB out of 3 MB clears both.
        assertTrue(JpegRewriter.meetsSavingFloor(plan(200 * 1024), 3 * 1024 * 1024))
    }

    @Test
    fun planStrip_keepsAnyAppSegmentItCannotIdentify() {
        // Unknown means unknown. Never drop on a guess.
        val bytes = concat(
            soi(),
            JpegFixtures.segment(JpegMarkers.APP2, ByteArray(64) { 0x5A }),  // no NUL-terminated id
            sof0(), sos(scan), eoi()
        )
        val s = JpegParser.parseFull(bytes)!!
        val plan = JpegRewriter.planStrip(s)
        assertTrue(plan.droppedSegmentOffsets.isEmpty())

        val out = JpegRewriter.rewrite(bytes, s, plan)
        assertArrayEquals("an unrecognised segment must be copied unchanged", bytes, out)
    }

    @Test
    fun rewrite_multipleMpfSegments_dropsAllOfThem() {
        val bytes = concat(
            soi(),
            app(JpegMarkers.APP2, "MPF", ByteArray(40)),
            app(JpegMarkers.APP1, "Exif", ByteArray(100)),
            app(JpegMarkers.APP2, "MPF", ByteArray(40)),
            sof0(), sos(scan), eoi()
        )
        val s = JpegParser.parseFull(bytes)!!
        val plan = JpegRewriter.planStrip(s)
        assertEquals(2, plan.droppedSegmentOffsets.size)

        val out = JpegRewriter.rewrite(bytes, s, plan)
        assertEquals(plan.outputSize, out.size)
        assertNotNull(JpegParser.parseFull(out))
    }

    @Test
    fun rewrite_rejectsAPlanThatDropsAnOffsetAbsentFromTheStructure() {
        // A stale plan built against a different (or since-changed) structure
        // must be rejected loudly, not fed to the byte-copying loop below.
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val realPlan = JpegRewriter.planStrip(s)
        val staleOffset = s.segments.last().offset + s.segments.last().length + 1
        val stalePlan = realPlan.copy(droppedSegmentOffsets = setOf(staleOffset))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            JpegRewriter.rewrite(bytes, s, stalePlan)
        }
        assertTrue(
            "message should name the offending offset: ${ex.message}",
            ex.message!!.contains(staleOffset.toString())
        )
    }

    @Test
    fun rewrite_rejectsATruncateAtThatDoesNotMatchThePrimaryEoi() {
        // A truncateAt short of the real primary EOI would silently emit a
        // truncated, corrupt JPEG (still >= cursor, so no ArrayIndexOutOfBounds)
        // -- this is the dangerous mismatch a size-only check would miss.
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val realPlan = JpegRewriter.planStrip(s)
        val shortPlan = realPlan.copy(truncateAt = realPlan.truncateAt - 10)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            JpegRewriter.rewrite(bytes, s, shortPlan)
        }
        assertTrue(
            "message should name both the mismatched value and the expected one: ${ex.message}",
            ex.message!!.contains(shortPlan.truncateAt.toString()) &&
                ex.message!!.contains(s.primaryEndOffset.toString())
        )
    }
}
