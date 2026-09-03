package com.sese.keepix.utils

import com.sese.keepix.db.CompressionJournalDao
import com.sese.keepix.db.CompressionJournalEntity
import com.sese.keepix.utils.jpeg.JpegFixtures
import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import com.sese.keepix.utils.jpeg.JpegMarkers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * In-memory [MediaFileIo] with fault injection. Each fault fires once, so a test
 * can inject a failure and then observe the restore path succeeding.
 */
private class FakeMediaFileIo(initial: Map<String, ByteArray>) : MediaFileIo {
    val files = initial.mapValues { it.value.copyOf() }.toMutableMap()
    var writeCount = 0
    /** Throw on the next overwrite, after leaving the file truncated. */
    var failNextWriteAfterTruncating = false
    /** Silently write only the first half of the bytes on the next overwrite. */
    var truncateNextWrite = false

    override suspend fun readAll(uriString: String): ByteArray =
        files[uriString]?.copyOf() ?: throw IOException("no such file: $uriString")

    override suspend fun overwrite(uriString: String, bytes: ByteArray) {
        writeCount++
        if (failNextWriteAfterTruncating) {
            failNextWriteAfterTruncating = false
            files[uriString] = ByteArray(0)
            throw IOException("simulated write failure")
        }
        if (truncateNextWrite) {
            truncateNextWrite = false
            files[uriString] = bytes.copyOf(bytes.size / 2)
            return
        }
        files[uriString] = bytes.copyOf()
    }
}

/** Minimal in-memory journal. */
private class FakeJournalDao : CompressionJournalDao {
    val rows = LinkedHashMap<String, CompressionJournalEntity>()
    /** Throw on the next insert, once. */
    var failNextInsert = false

    override suspend fun insert(entry: CompressionJournalEntity) {
        if (failNextInsert) {
            failNextInsert = false
            throw IOException("simulated insert failure")
        }
        rows[entry.mediaUri] = entry
    }

    override suspend fun getAll(): List<CompressionJournalEntity> = rows.values.sortedBy { it.startedAt }
    override suspend fun count(): Int = rows.size
    override suspend fun deleteByUri(mediaUri: String) { rows.remove(mediaUri) }
}

class PhotoCompressorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val uri = "content://media/external/images/media/42"
    private lateinit var original: ByteArray

    @Before
    fun setUp() {
        val primary = concat(
            soi(),
            app(JpegMarkers.APP1, "Exif", ByteArray(4000)),
            app(JpegMarkers.APP2, "MPF", ByteArray(80)),
            sof0(), sos(ByteArray(60_000) { (it % 251).toByte() }), eoi()
        )
        val secondary = concat(soi(), sof0(), sos(ByteArray(300_000) { 9 }), eoi())
        original = concat(primary, secondary)
    }

    private fun compressor(io: FakeMediaFileIo, dao: FakeJournalDao) =
        PhotoCompressor(io, dao, temp.newFolder("backups"))

    @Test
    fun compress_happyPath_shrinksTheFileAndLeavesNoJournalRowOrBackup() = runTest {
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        val backupDir = temp.newFolder("backups2")
        val outcome = PhotoCompressor(io, dao, backupDir).compress(uri)

        assertTrue("expected Compressed but got $outcome", outcome is CompressionOutcome.Compressed)
        val saved = (outcome as CompressionOutcome.Compressed).bytesSaved
        assertTrue(saved > 300_000)
        assertEquals(original.size - saved, io.files[uri]!!.size)
        assertTrue("journal must be empty after success", dao.rows.isEmpty())
        assertEquals("backup must be deleted after success", 0, backupDir.listFiles()!!.size)
    }

    @Test
    fun compress_preservesTheExifSegmentAndTheScanExactly() = runTest {
        val io = FakeMediaFileIo(mapOf(uri to original))
        compressor(io, FakeJournalDao()).compress(uri)

        val out = io.files[uri]!!
        val inStruct = com.sese.keepix.utils.jpeg.JpegParser.parseFull(original)!!
        val outStruct = com.sese.keepix.utils.jpeg.JpegParser.parseFull(out)!!

        val a = inStruct.segments.single { it.identifier == "Exif" }
        val b = outStruct.segments.single { it.identifier == "Exif" }
        assertArrayEquals(
            original.copyOfRange(a.offset, a.offset + a.length),
            out.copyOfRange(b.offset, b.offset + b.length)
        )

        val aSos = inStruct.segments.last { it.marker == JpegMarkers.SOS }
        val bSos = outStruct.segments.last { it.marker == JpegMarkers.SOS }
        assertArrayEquals(
            original.copyOfRange(aSos.offset + aSos.length, inStruct.primaryEndOffset),
            out.copyOfRange(bSos.offset + bSos.length, outStruct.primaryEndOffset)
        )
    }

    @Test
    fun compress_writeThrows_restoresTheOriginalAndClearsTheJournal() = runTest {
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        val backupDir = temp.newFolder("backups3")
        io.failNextWriteAfterTruncating = true

        val outcome = PhotoCompressor(io, dao, backupDir).compress(uri)

        assertTrue(outcome is CompressionOutcome.Failed)
        assertTrue((outcome as CompressionOutcome.Failed).restored)
        assertArrayEquals("the original must come back byte-for-byte", original, io.files[uri])
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, backupDir.listFiles()!!.size)
    }

    @Test
    fun compress_shortWrite_isCaughtByReadBackAndRestored() = runTest {
        // The failure ExifInterface verification would miss: the front of the
        // file, where EXIF lives, is intact while the image is destroyed.
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        io.truncateNextWrite = true

        val outcome = compressor(io, dao).compress(uri)

        assertTrue("a short write must not be accepted", outcome is CompressionOutcome.Failed)
        assertArrayEquals(original, io.files[uri])
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun compress_belowTheSavingFloor_skipsWithoutWritingAnything() = runTest {
        val small = concat(
            soi(), app(JpegMarkers.APP2, "MPF", ByteArray(40)),
            sof0(), sos(ByteArray(500_000) { 3 }), eoi()
        )
        val io = FakeMediaFileIo(mapOf(uri to small))
        val dao = FakeJournalDao()

        val outcome = compressor(io, dao).compress(uri)

        assertTrue(outcome is CompressionOutcome.Skipped)
        assertEquals("nothing may be written for a skip", 0, io.writeCount)
        assertArrayEquals(small, io.files[uri])
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun compress_nonJpeg_isSkippedAndNeverOpenedForWrite() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(1_000_000)
        val io = FakeMediaFileIo(mapOf(uri to png))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue(outcome is CompressionOutcome.Skipped)
        assertEquals(0, io.writeCount)
    }

    /** An XMP-shaped APP1 segment carrying [text] in its packet body. */
    private fun xmpApp1(text: String): ByteArray =
        app(JpegMarkers.APP1, "http://ns.adobe.com/xap/1.0/", text.toByteArray(Charsets.US_ASCII))

    @Test
    fun compress_ultraHdrGainMapMarker_skipsRatherThanStrippingTheGainMap() = runTest {
        // C1: an Ultra HDR file's MPF index is readable and agrees with the EOI
        // walk exactly like a dual-camera secondary would -- only the hdrgm:
        // XMP marker on the primary reveals that truncating here would destroy
        // the HDR rendering, not a duplicate. Every other check in compress()
        // would pass; this must still refuse to write.
        val payload = JpegFixtures.mpfPayload(listOf(400_000L))
        val primary = concat(
            soi(),
            xmpApp1("xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\""),
            JpegFixtures.segment(JpegMarkers.APP2, payload),
            sof0(), sos(ByteArray(400_000 - 200) { (it % 251).toByte() }), eoi()
        )
        val gainMap = concat(soi(), sof0(), sos(ByteArray(300_000) { 9 }), eoi())
        val bytes = concat(primary, gainMap)
        check(bytes.size >= 400_000) { "fixture must actually declare a plausible primary size" }

        val io = FakeMediaFileIo(mapOf(uri to bytes))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue("expected Skipped but got $outcome", outcome is CompressionOutcome.Skipped)
        assertEquals("must never open the file for write", 0, io.writeCount)
        assertArrayEquals("the HDR file must be left byte-for-byte untouched", bytes, io.files[uri])
    }

    @Test
    fun compress_gContainerItemSemanticMarker_skips() = runTest {
        val payload = JpegFixtures.mpfPayload(listOf(400_000L))
        val primary = concat(
            soi(),
            xmpApp1("http://ns.google.com/photos/1.0/container/ Item:Semantic=\"GainMap\""),
            JpegFixtures.segment(JpegMarkers.APP2, payload),
            sof0(), sos(ByteArray(400_000 - 200) { (it % 251).toByte() }), eoi()
        )
        val secondary = concat(soi(), sof0(), sos(ByteArray(300_000) { 9 }), eoi())
        val bytes = concat(primary, secondary)

        val io = FakeMediaFileIo(mapOf(uri to bytes))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue("expected Skipped but got $outcome", outcome is CompressionOutcome.Skipped)
        assertEquals(0, io.writeCount)
    }

    @Test
    fun compress_motionPhotoMarkerAlongsideAValidMpfIndex_skipsRatherThanDroppingTheVideo() = runTest {
        // I2: a Samsung-shaped frame carrying BOTH an MPF secondary and a
        // motion-photo video is eligible by the MPF index alone. Truncating at
        // the primary EOI would remove the video while the still survives --
        // an easy-to-miss loss. The MotionPhoto XMP marker must stop it.
        val payload = JpegFixtures.mpfPayload(listOf(400_000L))
        val primary = concat(
            soi(),
            xmpApp1("Camera:MotionPhoto=\"1\" Camera:MotionPhotoVersion=\"1\""),
            JpegFixtures.segment(JpegMarkers.APP2, payload),
            sof0(), sos(ByteArray(400_000 - 200) { (it % 251).toByte() }), eoi()
        )
        val motionVideoTrailer = ByteArray(300_000) { 0x42 }
        val bytes = concat(primary, motionVideoTrailer)

        val io = FakeMediaFileIo(mapOf(uri to bytes))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue("expected Skipped but got $outcome", outcome is CompressionOutcome.Skipped)
        assertEquals(0, io.writeCount)
        assertArrayEquals(bytes, io.files[uri])
    }

    @Test
    fun compress_trailingBytesWithNoMpfIndexAtAll_skipsBelowTheSavingFloorRatherThanTruncating() = runTest {
        // I2's other half: nothing but a trailer after EOI, no MPF segment
        // anywhere. planStrip must not treat trailing bytes alone as evidence
        // of a discardable duplicate, so this never even reaches the marker
        // check -- it fails the saving floor with a zero-saving plan.
        val primary = concat(
            soi(), app(JpegMarkers.APP1, "Exif", ByteArray(4000)),
            sof0(), sos(ByteArray(60_000) { (it % 251).toByte() }), eoi()
        )
        val trailer = ByteArray(300_000) { 0x42 }
        val bytes = concat(primary, trailer)

        val io = FakeMediaFileIo(mapOf(uri to bytes))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue("expected Skipped but got $outcome", outcome is CompressionOutcome.Skipped)
        assertEquals("below the saving floor", (outcome as CompressionOutcome.Skipped).reason)
        assertEquals(0, io.writeCount)
        assertArrayEquals(bytes, io.files[uri])
    }

    @Test
    fun compress_plainDualCameraMpfWithNoAuxiliaryMarkers_remainsEligible() = runTest {
        // Do-not-over-block: this is the exact shape from setUp(), with no XMP
        // markers at all, and it must still be compressed. If the guard were
        // too broad it would silently disable the whole feature.
        val io = FakeMediaFileIo(mapOf(uri to original))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue("expected Compressed but got $outcome", outcome is CompressionOutcome.Compressed)
    }

    @Test
    fun compress_mpfIndexDisagreesWithTheEoiWalk_skipsRatherThanTruncating() = runTest {
        // An MPF index claiming the primary is SHORTER than where the EOI walk
        // found it means the two disagree about where the image ends. Skip.
        // mpfPayload() already returns a full "MPF\0" + TIFF blob (see
        // MpfIndexTest, which wraps it with JpegFixtures.segment directly) --
        // wrapping it again with app(..., "MPF", payload) would prepend a
        // second "MPF\0" and corrupt every TIFF-relative offset inside it.
        val payload = JpegFixtures.mpfPayload(listOf(10L))
        val bytes = concat(
            soi(), JpegFixtures.segment(JpegMarkers.APP2, payload),
            sof0(), sos(ByteArray(400_000) { 5 }), eoi(),
            soi(), sof0(), sos(ByteArray(400_000) { 6 }), eoi()
        )
        val io = FakeMediaFileIo(mapOf(uri to bytes))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue("expected Skipped but got $outcome", outcome is CompressionOutcome.Skipped)
        assertEquals(0, io.writeCount)
    }

    @Test
    fun compress_journalInsertThrows_skipsWithoutWritingAndLeavesNoOrphanedBackup() = runTest {
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        val backupDir = temp.newFolder("backups6")
        dao.failNextInsert = true

        val outcome = PhotoCompressor(io, dao, backupDir).compress(uri)

        assertTrue("expected Skipped but got $outcome", outcome is CompressionOutcome.Skipped)
        assertEquals("must not write when journal insert fails", 0, io.writeCount)
        assertArrayEquals("file bytes must be unchanged", original, io.files[uri])
        assertTrue("journal must be empty", dao.rows.isEmpty())
        assertEquals("no orphaned backup allowed", 0, backupDir.listFiles()!!.size)
    }

    @Test
    fun recover_orphanedRow_restoresTheOriginalAndClearsTheRow() = runTest {
        val backupDir = temp.newFolder("backups4")
        val backup = java.io.File(backupDir, "orphan.bak")
        backup.writeBytes(original)

        // A half-written file plus a journal row: exactly the state a process
        // death between the write and the read-back leaves behind.
        val io = FakeMediaFileIo(mapOf(uri to original.copyOf(1000)))
        val dao = FakeJournalDao()
        dao.insert(CompressionJournalEntity(uri, backup.absolutePath, original.size.toLong(), 1L))

        val outcomes = PhotoCompressor(io, dao, backupDir).recover()

        assertEquals(1, outcomes.size)
        assertArrayEquals(original, io.files[uri])
        assertTrue(dao.rows.isEmpty())
        assertFalse("the backup must be released after a successful restore", backup.exists())
    }

    @Test
    fun recover_missingBackupFile_dropsTheRowInsteadOfLoopingForever() = runTest {
        val backupDir = temp.newFolder("backups5")
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        dao.insert(
            CompressionJournalEntity(uri, java.io.File(backupDir, "gone.bak").absolutePath, 1L, 1L)
        )

        val outcomes = PhotoCompressor(io, dao, backupDir).recover()

        assertTrue(outcomes.single() is CompressionOutcome.Failed)
        assertEquals("nothing to restore from, so nothing may be written", 0, io.writeCount)
        assertTrue("an unrecoverable row must not be retried every launch", dao.rows.isEmpty())
    }

}
