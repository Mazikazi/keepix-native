package com.sese.keepix.utils

import android.util.Log
import com.sese.keepix.db.CompressionJournalDao
import com.sese.keepix.db.CompressionJournalEntity
import com.sese.keepix.utils.jpeg.AuxiliaryPayloadDetector
import com.sese.keepix.utils.jpeg.JpegMarkers
import com.sese.keepix.utils.jpeg.JpegParser
import com.sese.keepix.utils.jpeg.JpegRewriter
import com.sese.keepix.utils.jpeg.MpfIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "PhotoCompressor"

sealed interface CompressionOutcome {
    val uriString: String

    data class Compressed(override val uriString: String, val bytesSaved: Int) : CompressionOutcome
    data class Skipped(override val uriString: String, val reason: String) : CompressionOutcome
    data class Failed(
        override val uriString: String,
        val reason: String,
        /**
         * True when the file is known to be in its original state -- either it
         * was never written, or the backup was successfully put back. False is
         * the serious case: a write happened and could not be undone, and the
         * journal row and backup have deliberately been left in place so the
         * next launch tries again.
         */
        val restored: Boolean
    ) : CompressionOutcome

    companion object {
        /**
         * [Failed.reason] used by [PhotoCompressor.recover] for the benign
         * case documented there: a journal row survives with no backup file
         * to restore from, almost always because the write had already
         * succeeded and only `releaseBackup()`'s second delete was lost to a
         * crash. A shared constant rather than a string literal each side
         * retypes (Minor 4): [KeepixViewModel] matches on this exact string to
         * decide whether a recovery failure is reassuring or alarming, so a
         * silent drift between the producer and the matcher would silently
         * reclassify every benign row as a genuine restore failure -- the
         * "Keepix could not restore N photos ... check them in your gallery
         * app" message, shown for photos that are in fact fine -- with every
         * existing test (each retyping its own copy of the literal) still
         * green.
         */
        const val REASON_BACKUP_MISSING: String = "backup missing"
    }
}

/**
 * Rewrites one photo at a time, crash-safely.
 *
 * The ordering is the whole design, and it is deliberately paranoid:
 *
 *  1. Everything that could reject the file happens BEFORE the file is opened
 *     for writing -- parse, plan, floor check, MPF cross-check, and a self-check
 *     that the rewritten bytes re-parse. A file that is going to be skipped is
 *     never touched at all.
 *  2. The backup is written and fsynced, and the journal row inserted, before
 *     the first byte of the real write.
 *  3. After the write, the file is read back and compared to the exact bytes
 *     that were meant to land. Anything short of equality restores.
 *  4. Only then are the backup and the journal row released.
 *
 * The backup is taken per file rather than for the whole batch: copying an
 * entire run up front could exhaust storage, and this ordering still recovers
 * correctly because at most one file is ever mid-write.
 *
 * [backupDir] must be app-private storage -- it holds unmodified copies of the
 * user's photos, briefly.
 */
class PhotoCompressor(
    private val io: MediaFileIo,
    private val journalDao: CompressionJournalDao,
    private val backupDir: File
) {

    suspend fun compress(uriString: String): CompressionOutcome {
        val original = try {
            io.readAll(uriString)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $uriString", e)
            return CompressionOutcome.Failed(uriString, "unreadable", restored = true)
        }

        val structure = JpegParser.parseFull(original)
            ?: return CompressionOutcome.Skipped(uriString, "not a parseable JPEG")

        // Same predicate the analyser consults, checked again here rather than
        // trusted from there: a file must never be rewritten on the strength of
        // a filter that lives in a different file at a different layer. An
        // Ultra HDR gain map or a motion-photo video is stored exactly like an
        // MPF secondary -- concatenated after the primary EOI -- so the MPF
        // structure alone cannot rule either out. This can, from the XMP the
        // primary image carries alongside it.
        if (AuxiliaryPayloadDetector.hasAuxiliaryPayloadMarker(structure.segments, original)) {
            return CompressionOutcome.Skipped(uriString, "trailing payload may not be a discardable duplicate")
        }

        // The check above only ever looks at the PRIMARY image's APP1
        // segments -- by design, since the analyser can only afford to read a
        // header-sized prefix of the file. That leaves a real gap: Apple's
        // "Most Compatible" HDR JPEG and an ISO 21496-1 gain map (Ultra HDR
        // v1.1+) put none of those six markers on the primary at all. The
        // gain map self-describes only in its OWN XMP, inside the bytes an
        // MPF strip would discard -- exactly the region `original` holds in
        // full here but the analyser never reads. This is why this check
        // lives only here and not in PhotoCompressionAnalyzer: it is the
        // authoritative, whole-file check the destructive path performs for
        // itself, on top of (not instead of) the analyser's optimistic,
        // header-only estimate.
        if (AuxiliaryPayloadDetector.hasGainMapInTrailer(original, structure.primaryEndOffset, original.size)) {
            return CompressionOutcome.Skipped(uriString, "trailing payload may not be a discardable duplicate")
        }

        val plan = JpegRewriter.planStrip(structure)
        if (!JpegRewriter.meetsSavingFloor(plan, original.size)) {
            return CompressionOutcome.Skipped(uriString, "below the saving floor")
        }

        // Cross-check against the MPF index, which states the primary image's
        // length independently of the EOI walk. Asymmetric on purpose: padding
        // between images can legitimately put the real EOI BEFORE the declared
        // end, but nothing legitimate puts it after. A declared end that is
        // shorter than where the EOI actually is means the two disagree about
        // where the image ends, and truncating on a disagreement is how a photo
        // gets destroyed. An unreadable index is not a disagreement -- it just
        // skips the check.
        val mpfSegment = structure.segments.firstOrNull {
            it.marker == JpegMarkers.APP2 && it.identifier == "MPF"
        }
        if (mpfSegment != null) {
            val declared = MpfIndex.readPrimaryImageSize(original, mpfSegment)
            if (declared != null && structure.primaryEndOffset > declared) {
                return CompressionOutcome.Skipped(uriString, "MPF index disagrees with the EOI walk")
            }
        }

        val rewritten = try {
            JpegRewriter.rewrite(original, structure, plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Rewrite failed for $uriString", e)
            return CompressionOutcome.Skipped(uriString, "rewrite failed")
        }

        // Self-check before touching the file: if our own output does not parse,
        // the bug is ours and the user's photo stays untouched.
        if (JpegParser.parseFull(rewritten) == null) {
            return CompressionOutcome.Skipped(uriString, "rewritten bytes failed the self-check")
        }

        val backup = File(backupDir, "${UUID.randomUUID()}.bak")
        try {
            writeBackup(backup, original)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not write a backup for $uriString", e)
            withContext(Dispatchers.IO) { backup.delete() }
            return CompressionOutcome.Skipped(uriString, "could not create a backup")
        }

        // Deviation from the brief: the brief calls journalDao.insert() here
        // unguarded. If it throws (a real possibility for a Room insert -- a
        // full disk or a corrupted DB file), the exception would propagate out
        // of compress() uncaught AND leave the just-written backup on disk with
        // no journal row pointing at it, since recover() only ever looks at
        // journal rows. That backup would never be cleaned up. Nothing about
        // the user's actual photo is at risk here (the real file has not been
        // touched yet), but it silently leaks app-private storage forever and
        // breaks the "everything that can be rejected happens before we commit
        // to anything" invariant the rest of this function is built on. Wrapping
        // it mirrors the backup-write failure case immediately above.
        try {
            journalDao.insert(
                CompressionJournalEntity(
                    mediaUri = uriString,
                    backupPath = backup.absolutePath,
                    originalSize = original.size.toLong(),
                    startedAt = System.currentTimeMillis()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not journal $uriString before writing", e)
            withContext(Dispatchers.IO) { backup.delete() }
            return CompressionOutcome.Skipped(uriString, "could not record the journal entry")
        }

        try {
            io.overwrite(uriString, rewritten)

            val readBack = io.readAll(uriString)
            if (!readBack.contentEquals(rewritten)) {
                // Catches a short or torn write. This is why verification
                // compares bytes rather than re-reading EXIF: EXIF sits at the
                // front of the file and survives a truncation that destroys the
                // image.
                return restore(uriString, backup, "read-back did not match what was written")
            }
        } catch (e: CancellationException) {
            // Do not attempt a restore on our way out: the journal row and
            // backup are already in place and untouched, which is exactly the
            // state the next launch's recover() needs to find to retry this
            // file. Calling restore() here would itself immediately hit
            // cancellation on its own suspend calls and accomplish nothing.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Write failed for $uriString", e)
            return restore(uriString, backup, e.message ?: "write failed")
        }

        // Committed. Release the backup last -- while it exists, the file is
        // recoverable no matter what happens.
        releaseBackup(uriString, backup)
        return CompressionOutcome.Compressed(uriString, plan.bytesSaved)
    }

    /**
     * Repairs anything left mid-write by a previous run.
     *
     * A row can only survive if the process died before the read-back passed, so
     * restoring is always the right move -- worst case it undoes a rewrite that
     * had in fact just succeeded, which costs the user nothing but a repeat run.
     *
     * Requires write access to the URIs. The caller is responsible for having
     * obtained it (see MainActivity's write-request effect); this method assumes
     * it and reports failure otherwise.
     */
    suspend fun recover(): List<CompressionOutcome> {
        val rows = journalDao.getAll()
        if (rows.isEmpty()) return emptyList()

        return rows.map { row ->
            val backup = File(row.backupPath)
            if (!backup.exists()) {
                // Nothing to restore from. Drop the row anyway: keeping it would
                // re-request write access on every launch forever with no way to
                // ever succeed.
                Log.w(TAG, "Journal row for ${row.mediaUri} has no backup file; dropping it")
                journalDao.deleteByUri(row.mediaUri)
                CompressionOutcome.Failed(row.mediaUri, CompressionOutcome.REASON_BACKUP_MISSING, restored = false)
            } else {
                restore(row.mediaUri, backup, "recovered an interrupted write")
            }
        }
    }

    /** Puts the original back, then releases the backup and the journal row. */
    private suspend fun restore(uriString: String, backup: File, reason: String): CompressionOutcome {
        return try {
            val bytes = withContext(Dispatchers.IO) { backup.readBytes() }
            io.overwrite(uriString, bytes)
            releaseBackup(uriString, backup)
            CompressionOutcome.Failed(uriString, reason, restored = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The backup stays on disk and the journal row stays put, so the next
            // launch tries again. This is the one path that must NOT clean up.
            Log.e(TAG, "Could not restore $uriString from ${backup.absolutePath}", e)
            CompressionOutcome.Failed(uriString, "restore failed: ${e.message}", restored = false)
        }
    }

    /**
     * One-shot cleanup of [backupDir]: deletes any `*.bak` file not referenced
     * by a row in [liveRows]. Nothing else ever enumerates this directory, so
     * without this a backup can leak permanently -- e.g. a `backup.delete()`
     * call whose boolean result is ignored, or a re-compression of the same
     * URI whose `OnConflictStrategy.REPLACE` journal insert overwrites the row
     * that used to point at an older backup.
     *
     * [liveRows] must come from the SAME `journalDao.getAll()` call the caller
     * uses to decide what needs recovering -- sweeping against a stale
     * snapshot could delete a backup a just-inserted row now depends on. This
     * is why the sweep takes the already-read rows as a parameter rather than
     * querying again itself: "after the rows are read" is a caller-enforced
     * ordering, not something this method can guarantee on its own.
     *
     * Deliberately safe to call unconditionally, whether or not there is
     * anything to recover: unlike [recover] itself, this only ever touches
     * this app's own private storage, never a MediaStore URI, so it needs no
     * write grant and nothing here can destroy a user's photo.
     */
    suspend fun sweepOrphanedBackups(liveRows: List<CompressionJournalEntity>) {
        withContext(Dispatchers.IO) {
            val livePaths = liveRows.map { File(it.backupPath).absolutePath }.toSet()
            val files = backupDir.listFiles() ?: return@withContext
            for (file in files) {
                if (!file.isFile || !file.name.endsWith(".bak")) continue
                if (file.absolutePath in livePaths) continue
                Log.w(TAG, "Deleting orphaned compression backup: ${file.name}")
                file.delete()
            }
        }
    }

    private suspend fun releaseBackup(uriString: String, backup: File) {
        withContext(Dispatchers.IO) { backup.delete() }
        journalDao.deleteByUri(uriString)
    }

    private suspend fun writeBackup(backup: File, bytes: ByteArray) = withContext(Dispatchers.IO) {
        backup.parentFile?.mkdirs()
        FileOutputStream(backup).use { out ->
            out.write(bytes)
            out.flush()
            // An unsynced backup is not a backup: the very crash it exists to
            // survive is the one that would lose it.
            out.fd.sync()
        }
    }
}
