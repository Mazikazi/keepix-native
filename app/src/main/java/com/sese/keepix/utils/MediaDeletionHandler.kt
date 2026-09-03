package com.sese.keepix.utils

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * The only way this app removes a media file from the device.
 *
 * The app does not own the MediaStore rows it deletes, so `ContentResolver.delete`
 * is not usable — it throws `RecoverableSecurityException`. Every removal goes
 * through [MediaStore.createDeleteRequest] and the system confirmation dialog it
 * produces, and a Room row is only dropped once that dialog returns `RESULT_OK`.
 */
object MediaDeletionHandler {

    /**
     * Returns a [PendingIntent] that prompts the user to confirm deletion of the
     * given URIs. Requires minSdk 30.
     *
     * @param uris must be non-empty and already filtered by
     *   [MediaUriFilter.filterExistingUris]; a URI whose file no longer exists
     *   makes the whole request fail.
     */
    fun getDeletionIntent(context: Context, uris: List<Uri>): PendingIntent {
        require(uris.isNotEmpty()) { "getDeletionIntent requires a non-empty URI list" }
        return MediaStore.createDeleteRequest(context.contentResolver, uris)
    }
}
