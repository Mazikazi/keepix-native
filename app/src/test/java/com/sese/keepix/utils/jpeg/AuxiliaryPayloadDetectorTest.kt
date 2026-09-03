package com.sese.keepix.utils.jpeg

import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuxiliaryPayloadDetectorTest {

    private val scan = ByteArray(2048) { (it % 251).toByte() }

    /** An XMP-shaped APP1 segment carrying [text] in its packet body. */
    private fun xmpApp1(text: String): ByteArray =
        app(JpegMarkers.APP1, "http://ns.adobe.com/xap/1.0/", text.toByteArray(Charsets.US_ASCII))

    /** Builds a file from the given APP1 segments and parses it, keeping the bytes alongside. */
    private fun fileWith(vararg app1Segments: ByteArray): Pair<ByteArray, JpegStructure> {
        val bytes = concat(soi(), *app1Segments, sof0(), sos(scan), eoi())
        return bytes to JpegParser.parseFull(bytes)!!
    }

    @Test
    fun hdrgmNamespace_isDetected() {
        // "hdrgm:" is the namespace PREFIX as used on its own properties (e.g.
        // hdrgm:Version) -- the xmlns declaration itself reads "xmlns:hdrgm="
        // with the colon on the other side, so a real gain-map packet is used
        // here rather than just the namespace URI.
        val (bytes, s) = fileWith(
            xmpApp1("xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\"")
        )
        assertTrue(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }

    @Test
    fun gContainerNamespace_isDetected() {
        val (bytes, s) = fileWith(xmpApp1("http://ns.google.com/photos/1.0/container/"))
        assertTrue(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }

    @Test
    fun itemSemantic_isDetected() {
        val (bytes, s) = fileWith(xmpApp1("Item:Semantic=\"GainMap\""))
        assertTrue(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }

    @Test
    fun gCameraMicroVideo_isDetected() {
        val (bytes, s) = fileWith(
            xmpApp1("xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" GCamera:MicroVideo=\"1\"")
        )
        assertTrue(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }

    @Test
    fun microVideoOffset_isDetected() {
        val (bytes, s) = fileWith(xmpApp1("GCamera:MicroVideoOffset=\"123456\""))
        assertTrue(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }

    @Test
    fun motionPhoto_isDetected() {
        val (bytes, s) = fileWith(xmpApp1("Camera:MotionPhoto=\"1\""))
        assertTrue(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }

    @Test
    fun plainAdobeXmpWithNoAuxiliaryMarkers_isNotDetected() {
        // Do-not-over-block: ordinary edit/rating/crop XMP must not trip this.
        val (bytes, s) = fileWith(xmpApp1("xmp:Rating=\"5\" xmp:CreatorTool=\"Keepix\""))
        assertFalse(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }

    @Test
    fun noApp1SegmentsAtAll_isNotDetected() {
        val (bytes, s) = fileWith()
        assertFalse(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }

    @Test
    fun markerBytesOutsideAnApp1Segment_areNotDetected() {
        // The same marker text sitting in an APP2 (e.g. a vendor blob adjacent
        // to MPF) must not count -- only APP1 payloads are in scope.
        val bytes = concat(
            soi(),
            app(JpegMarkers.APP2, "VENDOR", "MotionPhoto".toByteArray(Charsets.US_ASCII)),
            sof0(), sos(scan), eoi()
        )
        val s = JpegParser.parseFull(bytes)!!
        assertFalse(AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(s.segments, bytes))
    }
}
