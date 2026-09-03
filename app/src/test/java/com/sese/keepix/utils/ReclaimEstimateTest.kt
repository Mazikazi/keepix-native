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
     * least once before EOF is hit, exercising both concerns at once: growing
     * the buffer must not drop the bytes already read (parseHeader is handed
     * `buffer` and `filled` again, not just the newly-read tail), and running out
     * of input must return null (skip) rather than loop forever.
     */
    @Test
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
}
