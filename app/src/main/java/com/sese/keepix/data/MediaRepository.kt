package com.sese.keepix.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MediaRepository"

class MediaAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val dateAdded: Long,
    val isVideo: Boolean,
    val displayName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L
)

/**
 * Keyset pagination cursor: the (dateAdded, id) of the last row already
 * delivered to the caller, in DATE_ADDED DESC, _ID DESC order. Pass it back
 * into [MediaRepository.getMediaPage] to fetch the next strictly-older rows.
 *
 * Deliberately not a row-count offset. DATE_ADDED is second-precision, so
 * bulk imports routinely tie -- an offset can't break that tie consistently
 * across separate LIMIT/OFFSET calls, silently skipping or duplicating a row.
 * Worse, the result set mutates under the app (the user empties the bin and
 * confirms the system delete dialog, dropping N rows that sat at the
 * *lowest* offsets since they were the oldest/first-swiped): every offset
 * after that point now points at the wrong row, permanently skipping a whole
 * range. A composite (dateAdded, id) key is immune to both -- ties are broken
 * by _ID, and a page always resumes exactly after the last row it actually
 * delivered, regardless of what happened to other rows.
 */
data class MediaPageKey(val dateAdded: Long, val id: Long)

class MediaRepository(private val context: Context) {

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.WIDTH,
        MediaStore.Files.FileColumns.HEIGHT,
        MediaStore.Files.FileColumns.DURATION
    )

    private val baseSelection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"

    private val baseSelectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )

    private val sortOrder =
        "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"

    // minSdk is 30, so Build.VERSION_CODES.Q is always satisfied here.
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * Total number of images + videos in the device library, for DISPLAY
     * PURPOSES ONLY (e.g. a "X remaining of Y" indicator). It is a snapshot
     * that goes stale the moment the set mutates -- a confirmed bin deletion
     * drops rows immediately, an external app can add/remove photos at any
     * time -- so pagination termination in [getMediaPage] never depends on
     * this value; see that function's doc for the actual termination signal.
     */
    suspend fun getMediaCount(): Int = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Files.FileColumns._ID),
                baseSelection,
                baseSelectionArgs,
                null
            )?.use { cursor -> cursor.count } ?: 0
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied accessing media", e)
            throw MediaAccessException("Permission denied. Please grant media access.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count media items", e)
            throw MediaAccessException("Failed to load media: ${e.message}", e)
        }
    }

    /**
     * Reads up to [limit] rows strictly older, in DATE_ADDED DESC, _ID DESC
     * order, than [after] -- or the newest [limit] rows if [after] is null.
     *
     * Termination contract for callers: a page whose size is less than
     * [limit] (including empty) means there is nothing older left in the
     * device library. This is read directly off the actual query result, not
     * inferred from [getMediaCount], and holds even if rows were deleted or
     * added anywhere in the ordering between calls -- keyset pagination only
     * ever looks strictly after the last key it saw.
     *
     * Uses the Bundle-based `ContentResolver.query(Uri, Array<String>?,
     * Bundle, CancellationSignal?)` overload: `QUERY_ARG_LIMIT` requires API
     * 30 (this app's minSdk, so always available). The sort order also has to
     * travel in that same Bundle (`QUERY_ARG_SQL_SORT_ORDER`) rather than as
     * the legacy positional `sortOrder` string -- a provider given both
     * silently ignores the Bundle's query args entirely, which would silently
     * turn this back into an unpaginated full-table read.
     */
    suspend fun getMediaPage(after: MediaPageKey?, limit: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaItem>()

        try {
            val selection: String
            val selectionArgs: Array<String>
            if (after == null) {
                selection = baseSelection
                selectionArgs = baseSelectionArgs
            } else {
                selection = "($baseSelection) AND (" +
                    "${MediaStore.Files.FileColumns.DATE_ADDED} < ? OR (" +
                    "${MediaStore.Files.FileColumns.DATE_ADDED} = ? AND ${MediaStore.Files.FileColumns._ID} < ?))"
                selectionArgs = baseSelectionArgs + arrayOf(
                    after.dateAdded.toString(),
                    after.dateAdded.toString(),
                    after.id.toString()
                )
            }

            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }

            context.contentResolver.query(
                collection,
                projection,
                queryArgs,
                null
            )?.use { cursor ->
                val honoredArgs = cursor.extras
                    ?.getStringArray(ContentResolver.EXTRA_HONORED_ARGS)
                    ?.toSet()
                    ?: emptySet()
                if (ContentResolver.QUERY_ARG_LIMIT !in honoredArgs) {
                    // Defensive only, should not happen at minSdk 30 against
                    // the platform MediaProvider. The size cap on the loop
                    // below plus the final .take(limit) keep pagination
                    // correct even if some OEM provider ignores this; this is
                    // just visibility into that (hopefully never hit) case.
                    Log.w(TAG, "Provider did not report honoring QUERY_ARG_LIMIT; clamping client-side")
                }

                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)

                // Bounded by `limit` on our own side too: if a provider
                // ignores QUERY_ARG_LIMIT this stops us from reading (and
                // materialising) the rest of the library into memory.
                while (mediaList.size < limit && cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val type = cursor.getInt(typeColumn)
                    val dateAdded = cursor.getLong(dateColumn)
                    val displayName = cursor.getString(nameColumn) ?: ""
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val duration = cursor.getLong(durationColumn)

                    val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val contentUri = if (!isVideo) {
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    } else {
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    }

                    mediaList.add(
                        MediaItem(
                            id = id,
                            uri = contentUri,
                            dateAdded = dateAdded,
                            isVideo = isVideo,
                            displayName = displayName,
                            width = width,
                            height = height,
                            durationMs = duration
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied accessing media", e)
            throw MediaAccessException("Permission denied. Please grant media access.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load media page", e)
            throw MediaAccessException("Failed to load media: ${e.message}", e)
        }

        return@withContext mediaList.take(limit)
    }
}
