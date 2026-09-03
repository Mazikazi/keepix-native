package com.sese.keepix.utils

import android.util.Log
import com.sese.keepix.db.CompressionJournalDao
import com.sese.keepix.db.CompressionJournalEntity
import com.sese.keepix.utils.jpeg.JpegMarkers
import com.sese.keepix.utils.jpeg.JpegParser
import com.sese.keepix.utils.jpeg.JpegRewriter
import com.sese.keepix.utils.jpeg.MpfIndex
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
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $uriString", e)
            return CompressionOutcome.Failed(uriString, "unreadable", restored = true)
        }

        val structure = JpegParser.parseFull(original)
            ?: return CompressionOutcome.Skipped(uriString, "not a parseable JPEG")

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
        } catch (e: Exception) {
            Log.w(TAG, "Could not write a backup for $uriString", e)
            backup.delete()
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
                CompressionOutcome.Failed(row.mediaUri, "backup missing", restored = false)
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
        } catch (e: Exception) {
            // The backup stays on disk and the journal row stays put, so the next
            // launch tries again. This is the one path that must NOT clean up.
            Log.e(TAG, "Could not restore $uriString from ${backup.absolutePath}", e)
            CompressionOutcome.Failed(uriString, "restore failed: ${e.message}", restored = false)
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
