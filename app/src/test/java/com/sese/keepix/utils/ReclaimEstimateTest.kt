package com.sese.keepix.utils

import android.content.Context
import com.sese.keepix.utils.jpeg.HeaderResult
import com.sese.keepix.utils.jpeg.JpegFixtures
import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import com.sese.keepix.utils.jpeg.JpegMarkers
import com.sese.keepix.utils.jpeg.JpegParser
import io.mockk.mockk
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReclaimEstimateTest {

    @Test
    fun estimateFromHeader_mpfPresent_countsTheTrailingImageAndTheIndexSegment() {
        val payload = JpegFixtures.mpfPayload(listOf(2_000_000L))
        val bytes = concat(
            // segment(), NOT app(): mpfPayload already begins with "MPF ", and app()
            // would prepend a second copy, leaving MpfIndex unable to read the index.
            soi(), JpegFixtures.segment(JpegMarkers.APP2, payload), sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header
        val mpfSegment = header.segments.single { it.identifier == "MPF" }

        val estimate = PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 2_500_000L)

        assertEquals(500_000L + mpfSegment.length, estimate)
    }

    @Test
    fun estimateFromHeader_noMpfSegment_estimatesZero() {
        val bytes = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(50)), sof0(), sos(byteArrayOf(1)), eoi())
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header
        assertEquals(0L, PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 1_000_000L))
    }

    /** An XMP-shaped APP1 segment carrying [text] in its packet body. */
    private fun xmpApp1(text: String): ByteArray =
        app(JpegMarkers.APP1, "http://ns.adobe.com/xap/1.0/", text.toByteArray(Charsets.US_ASCII))

    @Test
    fun estimateFromHeader_ultraHdrGainMapMarker_estimatesZeroDespiteAValidMpfIndex() {
        // The central C1 case: an Ultra HDR file's MPF index is perfectly
        // readable and looks exactly like a dual-camera secondary from the MPF
        // structure alone -- only the XMP hdrgm: marker on the primary image
        // reveals that the "secondary" is the HDR gain map, not a duplicate.
        val payload = JpegFixtures.mpfPayload(listOf(2_000_000L))
        val bytes = concat(
            soi(),
            xmpApp1("xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\""),
            JpegFixtures.segment(JpegMarkers.APP2, payload),
            sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header

        val estimate = PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 2_500_000L)

        assertEquals(0L, estimate)
    }

    @Test
    fun estimateFromHeader_gContainerItemSemanticMarker_estimatesZeroDespiteAValidMpfIndex() {
        val payload = JpegFixtures.mpfPayload(listOf(2_000_000L))
        val bytes = concat(
            soi(),
            xmpApp1("http://ns.google.com/photos/1.0/container/ Item:Semantic=\"GainMap\""),
            JpegFixtures.segment(JpegMarkers.APP2, payload),
            sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header

        assertEquals(0L, PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 2_500_000L))
    }

    @Test
    fun estimateFromHeader_motionPhotoMarker_estimatesZeroDespiteAValidMpfIndex() {
        val payload = JpegFixtures.mpfPayload(listOf(2_000_000L))
        val bytes = concat(
            soi(),
            xmpApp1("GCamera:MicroVideo=\"1\" GCamera:MicroVideoOffset=\"123456\""),
            JpegFixtures.segment(JpegMarkers.APP2, payload),
            sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header

        assertEquals(0L, PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 2_500_000L))
    }

    @Test
    fun estimateFromHeader_unreadableIndex_estimatesZeroRatherThanGuessing() {
        val bytes = concat(
            soi(), app(JpegMarkers.APP2, "MPF", ByteArray(12)), sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header
        assertEquals(0L, PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 5_000_000L))
    }

    @Test
    fun estimateFromHeader_declaredSizeExceedsTheFile_estimatesZero() {
        // A nonsensical index must not produce a negative or inflated figure.
        val bytes = concat(
            soi(), JpegFixtures.segment(JpegMarkers.APP2, JpegFixtures.mpfPayload(listOf(9_000_000L))),
            sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header
        assertEquals(0L, PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 1_000_000L))
    }

    /**
     * Task 1's review flagged this: [JpegParser.parseHeader] returns
     * [HeaderResult.NeedMoreBytes] -- never [HeaderResult.Malformed] -- when a
     * segment's DECLARED length overruns the buffer, because a prefix genuinely
     * cannot tell "read more" apart from "this length is impossible" (see
     * JpegParser.kt's `segStart + segLength > n` branch). That makes the
     * analyser's own read-more loop, [PhotoCompressionAnalyzer.readHeader], the
     * thing that has to terminate: nothing upstream will ever hand it a
     * Malformed to stop on.
     *
     * This builds a file that never completes its header -- a single APP1
     * segment whose declared length (0xFFFF) is never satisfied by what's
     * actually in the stream -- and supplies it through a real [ByteArrayInputStream]
     * so the genuine EOF contract (read() returning -1, not a mocked answer) is
     * what the loop has to react to. The file is sized to cross one
     * HEADER_CHUNK_BYTES (64 KB) boundary, so the buffer-growth branch runs at
     * least once before EOF is hit.
     *
     * This proves termination and the EOF-skip path only -- NOT that growth
     * preserves the already-read bytes. A prior version of this docstring
     * claimed the latter too, but `assertNull` can't tell the two apart: a
     * mutation that drops the already-read prefix on growth (replacing
     * `buffer.copyOf(...)` with a fresh, zeroed `ByteArray(...)`) still returns
     * null here, just for an unrelated reason (a zeroed buffer no longer starts
     * with the SOI marker, so `parseHeader` reports `Malformed` instead of
     * `NeedMoreBytes` -- same null result as genuine EOF). Buffer-growth
     * correctness is covered separately, by
     * `readHeader_headerSpansMultipleChunks_growsTheBufferAndParsesTheFullChain`
     * below, which asserts on the actual parsed segment content of a header
     * that only comes out right if the already-read bytes survived the resize.
     */
    @Test(timeout = 10_000)
    fun readHeader_headerNeverCompletesEvenAfterTheWholeFileIsRead_terminatesAndSkips() {
        val declaredLength = 0xFFFF // max 16-bit length; segment "needs" 4 + 0xFFFF bytes total.
        val header = byteArrayOf(
            0xFF.toByte(), JpegMarkers.APP1.toByte(),
            (declaredLength shr 8).toByte(), (declaredLength and 0xFF).toByte()
        )
        // Sized so the total is just over one HEADER_CHUNK_BYTES (64 KB) --
        // forcing the buffer to grow once and a second, shorter read to happen
        // after that -- while staying just under what the declared segment
        // length demands, so it never stops asking for more.
        val filler = ByteArray(64 * 1024 - 4) { 0x00 }
        val bytes = soi() + header + filler
        check(bytes.size < 4 + declaredLength) {
            "fixture must never satisfy the declared segment length"
        }

        val analyzer = PhotoCompressionAnalyzer(mockk<Context>())

        val result = analyzer.readHeader(ByteArrayInputStream(bytes))

        assertNull(result)
    }

    /**
     * Closes the gap the review found in the test above: that one only proves
     * the loop terminates, never that growing the buffer preserves what was
     * already read. Proven by mutation -- replacing `buffer.copyOf(...)` with a
     * fresh, zeroed `ByteArray(...)` left the whole suite green, because a
     * zeroed buffer fails at byte 0 (no longer starts with the SOI marker) the
     * exact same way an exhausted stream does: both return null, and
     * `assertNull` can't tell them apart.
     *
     * This builds a header that spans more than one HEADER_CHUNK_BYTES (64 KB)
     * chunk -- two oversized filler APP1 segments push the marker chain past
     * the boundary before SOF0, the MPF index segment, and SOS -- so
     * [PhotoCompressionAnalyzer.readHeader] must grow its buffer at least once
     * before it can reach [HeaderResult.Ok]. Unlike the null-returning tests,
     * this one asserts on the actual parsed segments -- their markers,
     * identifiers, and offsets. If growth ever dropped the already-read
     * prefix, parsing would restart against a corrupted buffer and either fail
     * outright (no leading SOI) or misreport the chain; only an assertion on
     * the real parsed content, not mere non-nullness, catches that.
     */
    @Test
    fun readHeader_headerSpansMultipleChunks_growsTheBufferAndParsesTheFullChain() {
        val soiBytes = soi()
        // Two oversized filler segments -- their exact content is irrelevant,
        // only their combined size, chosen to land past one 64 KB chunk before
        // the chain reaches SOS.
        val filler1 = app(JpegMarkers.APP1, "Exif", ByteArray(40_000))
        val filler2 = app(JpegMarkers.APP1, "Exif", ByteArray(30_000))
        val mpfSegment = JpegFixtures.segment(JpegMarkers.APP2, JpegFixtures.mpfPayload(listOf(1_000_000L)))
        val sofBytes = sof0()
        val bytes = concat(soiBytes, filler1, filler2, mpfSegment, sofBytes, sos(byteArrayOf(1)), eoi())

        val scanStartOffset = soiBytes.size + filler1.size + filler2.size + mpfSegment.size + sofBytes.size
        check(scanStartOffset > 64 * 1024) {
            "fixture must cross one HEADER_CHUNK_BYTES (64 KB) boundary before SOS"
        }

        val analyzer = PhotoCompressionAnalyzer(mockk<Context>())
        val result = analyzer.readHeader(ByteArrayInputStream(bytes))

        assertNotNull("expected a parsed header, not a null (EOF/malformed) result", result)
        val header = result!!.first

        assertEquals(scanStartOffset, header.scanStartOffset)
        assertEquals(4, header.segments.size)

        assertEquals(JpegMarkers.APP1, header.segments[0].marker)
        assertEquals("Exif", header.segments[0].identifier)
        assertEquals(soiBytes.size, header.segments[0].offset)
        assertEquals(filler1.size, header.segments[0].length)

        assertEquals(JpegMarkers.APP1, header.segments[1].marker)
        assertEquals("Exif", header.segments[1].identifier)
        assertEquals(soiBytes.size + filler1.size, header.segments[1].offset)
        assertEquals(filler2.size, header.segments[1].length)

        assertEquals(JpegMarkers.APP2, header.segments[2].marker)
        assertEquals("MPF", header.segments[2].identifier)
        assertEquals(soiBytes.size + filler1.size + filler2.size, header.segments[2].offset)
        assertEquals(mpfSegment.size, header.segments[2].length)

        assertEquals(0xC0, header.segments[3].marker)
        assertEquals(
            soiBytes.size + filler1.size + filler2.size + mpfSegment.size,
            header.segments[3].offset
        )
        assertEquals(sofBytes.size, header.segments[3].length)
    }

    /**
     * The reviewer hand-traced the MAX_HEADER_BYTES-cap branch as correct but
     * nothing exercised it: a chain of well-formed segments that keeps
     * demanding more bytes past the 1 MB cap must be skipped, and the buffer
     * must stop growing there rather than continuing to read an arbitrarily
     * large file.
     *
     * The fixture is a long run of identical, individually well-formed APP1
     * filler segments with no SOS anywhere, so every call to
     * [JpegParser.parseHeader] returns [HeaderResult.NeedMoreBytes] -- never
     * [HeaderResult.Malformed] or [HeaderResult.Ok] -- all the way up through
     * the doubling sequence 64 KB -> 128 KB -> 256 KB -> 512 KB -> 1 MB. The
     * stream deliberately carries more than 1 MB of data (padding past the
     * cap): if the loop kept growing/reading instead of stopping at the cap,
     * it would consume more than 1 MB from the stream. Asserting on
     * [ByteArrayInputStream.available] after the call proves exactly how many
     * bytes were consumed -- the cap, not a byte more -- which is the
     * buffer-growth ceiling actually being honoured, rather than the loop
     * merely returning null for some other reason.
     */
    @Test(timeout = 10_000)
    fun readHeader_headerNeverCompletesPastTheCap_stopsGrowingAtMaxHeaderBytesAndSkips() {
        // MAX_HEADER_BYTES is private to PhotoCompressionAnalyzer; mirrored
        // here as in the termination test above.
        val maxHeaderBytes = 1024 * 1024
        val filler = app(JpegMarkers.APP1, "Exif", ByteArray(1000))
        val fillerCount = 1200 // 1200 * 1009 bytes > maxHeaderBytes, with room to spare as padding
        val bytes = concat(soi(), *Array(fillerCount) { filler })
        check(bytes.size > maxHeaderBytes) { "fixture must exceed MAX_HEADER_BYTES" }

        val input = ByteArrayInputStream(bytes)
        val analyzer = PhotoCompressionAnalyzer(mockk<Context>())

        val result = analyzer.readHeader(input)

        assertNull(result)
        // Exactly MAX_HEADER_BYTES must have been consumed from the stream:
        // the loop gave up at the cap rather than reading further into the
        // padding that follows it.
        assertEquals(bytes.size - maxHeaderBytes, input.available())
    }
}
