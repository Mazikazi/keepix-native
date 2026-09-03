package com.sese.keepix.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException

/**
 * Reads and writes a MediaStore file's whole contents.
 *
 * Exists so [PhotoCompressor] -- the only code here that can destroy a photo --
 * runs on the host JVM against a fake that can inject a mid-write failure or a
 * short write. Callers pass URI *strings*, because `android.net.Uri` has no
 * usable implementation off-device.
 */
interface MediaFileIo {
    suspend fun readAll(uriString: String): ByteArray
    suspend fun overwrite(uriString: String, bytes: ByteArray)
}

/**
 * The real implementation.
 *
 * `"wt"` truncates before writing, which matters: the output is always smaller
 * than the input, and without truncation the tail of the old file would survive
 * past the new EOI as garbage.
 *
 * `IS_PENDING` is deliberately NOT set around the write. It would hide a torn
 * read during the write window, but if the process died while it was set, the
 * photo would be invisible in every gallery app until recovery ran -- and
 * recovery can be declined. A file truncated mid-write fails to decode cleanly
 * and is repaired on next launch; a file that has silently vanished from the
 * user's gallery looks like data loss. The window is one file write long, and
 * the failure it would prevent is strictly less bad than the one it introduces.
 */
class ContentResolverMediaFileIo(private val context: Context) : MediaFileIo {

    override suspend fun readAll(uriString: String): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
            ?: throw IOException("Cannot open $uriString for reading")
    }

    override suspend fun overwrite(uriString: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "wt")
            ?: throw IOException("Cannot open $uriString for writing")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                out.write(bytes)
                out.flush()
                // fsync before the descriptor closes. Without it a crash can
                // leave the file's blocks unwritten while the journal row has
                // already been deleted, which is the one ordering this design
                // must never produce.
                it.fileDescriptor.sync()
            }
        }
    }
}
