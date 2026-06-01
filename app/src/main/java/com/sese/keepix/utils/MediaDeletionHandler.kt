package com.sese.keepix.utils

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

object MediaDeletionHandler {

    /**
     * Returns a PendingIntent that prompts the user to grant permission to delete the given URIs.
     * On Android 11+ (API 30+), this uses createDeleteRequest.
     * On older versions, we can just delete them directly if we have WRITE_EXTERNAL_STORAGE.
     */
    fun getDeletionIntent(context: Context, uris: List<Uri>): PendingIntent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return MediaStore.createDeleteRequest(context.contentResolver, uris)
        }
        return null
    }

    /**
     * Directly deletes the media if no PendingIntent is needed (pre-API 30).
     */
    fun deleteMediaDirectly(context: Context, uris: List<Uri>): Int {
        var deletedCount = 0
        for (uri in uris) {
            try {
                val deleted = context.contentResolver.delete(uri, null, null)
                deletedCount += deleted
            } catch (e: Exception) {
                if (com.sese.keepix.BuildConfig.DEBUG) {
                    android.util.Log.e("MediaDeletionHandler", "Failed to delete media: $uri", e)
                }
            }
        }
        return deletedCount
    }
}
