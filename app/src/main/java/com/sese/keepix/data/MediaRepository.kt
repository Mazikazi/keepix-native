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

    private val selection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"

    private val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )

    // minSdk is 30, so Build.VERSION_CODES.Q is always satisfied here.
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * Total number of images + videos in the device library. Used by the caller
     * to know when pagination has reached the end.
     */
    suspend fun getMediaCount(): Int = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(collection, arrayOf(MediaStore.Files.FileColumns._ID), selection, selectionArgs, null)
                ?.use { cursor -> cursor.count } ?: 0
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied accessing media", e)
            throw MediaAccessException("Permission denied. Please grant media access.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count media items", e)
            throw MediaAccessException("Failed to load media: ${e.message}", e)
        }
    }

    /**
     * Reads a single window of [limit] rows starting at [offset] from the full,
     * DATE_ADDED-descending device library. [offset] counts rows in the
     * underlying cursor, not items surviving any caller-side filtering — the
     * caller is responsible for re-requesting more rows if it filters some of
     * this page out.
     *
     * Uses the Bundle-based `ContentResolver.query` overload (API 26+, always
     * available at minSdk 30) so `QUERY_ARG_OFFSET`/`QUERY_ARG_LIMIT` and the
     * sort order can be expressed together — mixing a raw `sortOrder` string
     * argument with Bundle query args is not supported by the platform.
     */
    suspend fun getMediaPage(offset: Int, limit: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaItem>()

        try {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
                )
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }

            context.contentResolver.query(
                collection,
                projection,
                queryArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)

                while (cursor.moveToNext()) {
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

        return@withContext mediaList
    }
}
