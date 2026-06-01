package com.sese.keepix.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
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

    suspend fun getMediaItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaItem>()

        try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.DURATION
            )

            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )

            val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
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
            Log.e(TAG, "Failed to load media items", e)
            throw MediaAccessException("Failed to load media: ${e.message}", e)
        }

        return@withContext mediaList
    }
}
