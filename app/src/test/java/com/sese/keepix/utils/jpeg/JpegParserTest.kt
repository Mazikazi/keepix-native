package com.sese.keepix.utils.jpeg

import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import com.sese.keepix.utils.jpeg.JpegFixtures.trickyScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegParserTest {

    @Test
    fun parseFull_baselineFile_findsEverySegmentAndTheEoi() {
        val bytes = concat(
            soi(),
            app(JpegMarkers.APP0, "JFIF", ByteArray(9)),
            app(JpegMarkers.APP1, "Exif", ByteArray(20)),
            sof0(),
            sos(trickyScan()),
            eoi()
        )

        val s = JpegParser.parseFull(bytes)!!

        assertEquals(
            listOf(JpegMarkers.APP0, JpegMarkers.APP1, 0xC0, JpegMarkers.SOS),
            s.segments.map { it.marker }
        )
        assertEquals(listOf("JFIF", "Exif", null, null), s.segments.map { it.identifier })
        assertEquals("primary must end at the file end", bytes.size, s.primaryEndOffset)
        assertEquals(0, s.trailingBytes)
    }

    @Test
    fun parseFull_stuffedFfAndRestartMarkers_doNotTerminateTheScan() {
        // trickyScan() contains FF 00 and FF D0. A naive "first FF ends the scan"
        // walker stops at the stuffed byte and reports a truncated file.
        val bytes = concat(soi(), sof0(), sos(trickyScan()), eoi())
        val s = JpegParser.parseFull(bytes)!!
        assertEquals(bytes.size, s.primaryEndOffset)
    }

    @Test
    fun parseFull_progressiveWithTwoScans_walksBackIntoTheMarkerChain() {
        val bytes = concat(
            soi(), sof0(),
            sos(byteArrayOf(0x11, 0x22)),
            JpegFixtures.segment(0xC4, ByteArray(6)),   // DHT between scans
            sos(byteArrayOf(0x33, 0x44)),
            eoi()
        )
        val s = JpegParser.parseFull(bytes)!!
        assertEquals(
            listOf(0xC0, JpegMarkers.SOS, 0xC4, JpegMarkers.SOS),
            s.segments.map { it.marker }
        )
        assertEquals(bytes.size, s.primaryEndOffset)
    }

    @Test
    fun parseFull_mpfSecondaryImage_isReportedAsTrailingBytes() {
        val primary = concat(
            soi(),
            app(JpegMarkers.APP2, "MPF", ByteArray(40)),
            sof0(), sos(byteArrayOf(0x01, 0x02)), eoi()
        )
        val secondary = concat(soi(), sof0(), sos(byteArrayOf(0x03)), eoi())
        val bytes = concat(primary, secondary)

        val s = JpegParser.parseFull(bytes)!!

        assertEquals(primary.size, s.primaryEndOffset)
        assertEquals(secondary.size, s.trailingBytes)
        assertTrue(s.segments.any { it.marker == JpegMarkers.APP2 && it.identifier == "MPF" })
    }

    @Test
    fun parseFull_rejectsNonJpegAndTruncatedInput() {
        assertNull(JpegParser.parseFull(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(JpegParser.parseFull(ByteArray(0)))
        // SOI + a segment claiming more payload than the file holds.
        assertNull(
            JpegParser.parseFull(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0xFF.toByte()))
        )
        // Well-formed prefix but no EOI anywhere.
        assertNull(JpegParser.parseFull(concat(soi(), sof0(), sos(byteArrayOf(1, 2, 3)))))
    }

    @Test
    fun parseHeader_stopsAtSos_andReportsSegmentOffsets() {
        val bytes = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(10)), sof0(), sos(byteArrayOf(1)), eoi())
        val r = JpegParser.parseHeader(bytes)
        assertTrue(r is HeaderResult.Ok)
        val h = (r as HeaderResult.Ok).header
        assertEquals(listOf(JpegMarkers.APP1, 0xC0), h.segments.map { it.marker })
        assertEquals(2, h.segments[0].offset)
        assertEquals(bytes[h.scanStartOffset + 1].toInt() and 0xFF, JpegMarkers.SOS)
    }

    @Test
    fun parseHeader_prefixTooShort_asksForMoreBytesRatherThanFailing() {
        // This distinction is what lets the analyser read 64 KB at a time.
        val full = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(500)), sof0(), sos(byteArrayOf(1)), eoi())
        assertTrue(JpegParser.parseHeader(full, available = 20) is HeaderResult.NeedMoreBytes)
        assertTrue(JpegParser.parseHeader(full) is HeaderResult.Ok)
    }

    @Test
    fun parseHeader_nonJpeg_isMalformedNotNeedMoreBytes() {
        assertTrue(JpegParser.parseHeader(byteArrayOf(0x00, 0x01, 0x02, 0x03)) is HeaderResult.Malformed)
    }
}
