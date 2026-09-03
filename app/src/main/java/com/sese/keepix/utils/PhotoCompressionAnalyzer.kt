package com.sese.keepix.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sese.keepix.db.KeptItemEntity
import com.sese.keepix.utils.jpeg.AuxiliaryPayloadDetector
import com.sese.keepix.utils.jpeg.HeaderResult
import com.sese.keepix.utils.jpeg.JpegHeader
import com.sese.keepix.utils.jpeg.JpegMarkers
import com.sese.keepix.utils.jpeg.JpegParser
import com.sese.keepix.utils.jpeg.JpegRewriter
import com.sese.keepix.utils.jpeg.MpfIndex
import com.sese.keepix.utils.jpeg.StripPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

private const val TAG = "CompressionAnalyzer"

/** How much of a file is read per attempt while looking for the SOS. */
private const val HEADER_CHUNK_BYTES = 64 * 1024

/** Hard cap on the header region. Beyond this the file is treated as ineligible. */
private const val MAX_HEADER_BYTES = 1024 * 1024

/**
 * @param scannedCount files actually opened and parsed.
 * @param eligibleCount files that clear the saving floor.
 * @param estimatedBytes total reclaimable bytes across [eligibleUris]. An
 *   estimate, derived from each file's MPF index rather than a full walk.
 * @param eligibleUris the URIs a compression run would target, in scan order.
 */
data class ReclaimEstimate(
    val scannedCount: Int,
    val eligibleCount: Int,
    val estimatedBytes: Long,
    val eligibleUris: List<String>
)

/**
 * Measures how much a compression run would reclaim, without writing anything
 * and without needing any permission beyond the read access the app already has.
 *
 * Reads only each file's HEADER -- 64 KB at a time, capped at 1 MB -- and takes
 * the primary image's length from the MPF index, which states it directly. A
 * full walk per file would be authoritative but unaffordable: a 2,000-photo
 * library is roughly 10 GB of reads. The figure is therefore an estimate, and
 * the UI says so; [PhotoCompressor] re-derives the real number authoritatively
 * before it writes anything.
 */
class PhotoCompressionAnalyzer(private val context: Context) {

    suspend fun analyze(
        items: List<KeptItemEntity>,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): ReclaimEstimate = withContext(Dispatchers.IO) {
        val photos = items.filter { it.mediaType == "IMAGE" }
        var scanned = 0
        var total = 0L
        val eligible = mutableListOf<String>()

        for (item in photos) {
            // Cooperative cancellation: the user can leave Settings mid-scan.
            coroutineContext.ensureActive()

            val estimate = try {
                estimateForUri(item.mediaUri)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping ${item.mediaUri} during analysis", e)
                null
            }
            scanned++
            onProgress(scanned, photos.size)

            if (estimate != null && estimate.second > 0) {
                val (fileSize, bytesSaved) = estimate
                // JpegRewriter.meetsSavingFloor (Task 3) is Int-typed, and this
                // path only ever needed a fake StripPlan to reach it. Bound-check
                // before the .toInt() conversions rather than trusting a huge
                // fileSize or bytesSaved to fit: a silent Long->Int wrap here is
                // exactly the overflow shape a previous task in this project
                // shipped for real, and it would land on the wrong side of the
                // saving floor rather than failing closed the way an estimate we
                // "could not tell" about is supposed to.
                if (fileSize <= Int.MAX_VALUE && bytesSaved <= Int.MAX_VALUE) {
                    val plan = StripPlan(emptySet(), 0, bytesSaved.toInt(), 0)
                    if (JpegRewriter.meetsSavingFloor(plan, fileSize.toInt())) {
                        total += bytesSaved
                        eligible += item.mediaUri
                    }
                }
            }
        }

        ReclaimEstimate(scanned, eligible.size, total, eligible)
    }

    /** Returns (fileSize, estimatedSavings) or null if the file is not usable. */
    private fun estimateForUri(uriString: String): Pair<Long, Long>? {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver

        val fileSize = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        if (fileSize <= 0) return null

        resolver.openInputStream(uri)?.use { input ->
            val header = readHeader(input) ?: return null
            return fileSize to estimateFromHeader(header.first, header.second, fileSize)
        }
        return null
    }

    /**
     * Reads growing prefixes until the marker chain reaches the SOS. Returns the
     * parsed header and the bytes it was parsed from, or null if the file is not
     * a JPEG or its header exceeds [MAX_HEADER_BYTES].
     *
     * Deliberately `internal`, not `private` (a deviation from the brief): this
     * loop is the only thing standing between a header that never completes and
     * an infinite read, because upstream `JpegParser.parseHeader` can never tell
     * "read more" apart from "this length is impossible" and so never returns
     * Malformed for that case -- see ReclaimEstimateTest's
     * readHeader_headerNeverCompletesEvenAfterTheWholeFileIsRead_terminatesAndSkips,
     * which drives this method directly with a real InputStream to prove EOF
     * terminates it. Testing that through analyze() would mean mocking
     * Uri.parse, ContentResolver and ParcelFileDescriptor -- Android statics and
     * classes this JVM test module has no Robolectric-backed way to exercise
     * (test unitTests.isReturnDefaultValues is on for exactly the "just make
     * these compile" case, not for meaningful stubbing) -- for no benefit over
     * calling the loop itself.
     */
    internal fun readHeader(input: InputStream): Pair<JpegHeader, ByteArray>? {
        var buffer = ByteArray(HEADER_CHUNK_BYTES)
        var filled = 0
        while (true) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read <= 0) return null   // EOF before a SOS
            filled += read

            when (val result = JpegParser.parseHeader(buffer, filled)) {
                is HeaderResult.Ok -> return result.header to buffer
                HeaderResult.Malformed -> return null
                HeaderResult.NeedMoreBytes -> {
                    if (filled == buffer.size) {
                        if (buffer.size >= MAX_HEADER_BYTES) return null
                        buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_HEADER_BYTES))
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Reclaimable bytes for one file, from its header alone: everything after
         * the primary image (the MPF secondary), plus the index segment that
         * describes it.
         *
         * Returns 0 whenever the index is absent, unreadable, or nonsensical, or
         * whenever [AuxiliaryPayloadDetector] finds evidence that the trailing
         * payload is something other than a discardable duplicate -- an Ultra
         * HDR gain map or a motion-photo video are both stored the same way an
         * MPF secondary is, and offering a saving for either would only get
         * rejected later by [com.sese.keepix.utils.PhotoCompressor], after the
         * user has already been shown a reclaimable-space figure that included
         * it. Under-reporting makes the feature look less useful than it is;
         * over-reporting promises space that is not there. Zero is the honest
         * answer to "I could not tell".
         */
        fun estimateFromHeader(header: JpegHeader, bytes: ByteArray, fileSize: Long): Long {
            if (AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(header.segments, bytes)) return 0L

            val mpf = header.segments.firstOrNull {
                it.marker == JpegMarkers.APP2 && it.identifier == "MPF"
            } ?: return 0L

            val declaredPrimarySize = MpfIndex.readPrimaryImageSize(bytes, mpf) ?: return 0L
            if (declaredPrimarySize <= 0 || declaredPrimarySize > fileSize) return 0L

            val trailing = fileSize - declaredPrimarySize
            return trailing + mpf.length
        }
    }
}
