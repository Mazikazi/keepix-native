package com.sese.keepix.utils

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Every system-consented write this app makes against media it does not own:
 * trashing, untrashing, and permanent removal.
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

    /**
     * Returns a [PendingIntent] that prompts the user to move the given URIs
     * into the system trash ([trashed] true), or back out of it (false).
     *
     * Trashing is not deleting. The file disappears from the device gallery and
     * from Google Photos immediately, which is what "binned" is supposed to
     * mean, but the bytes stay on disk until a permanent delete or until the
     * system's own ~30-day expiry. Reclaiming the space still goes through
     * [getDeletionIntent].
     *
     * Same batching rule as deletion: one dialog per call, so callers should
     * accumulate rather than ask per photo.
     */
    fun getTrashIntent(context: Context, uris: List<Uri>, trashed: Boolean): PendingIntent {
        require(uris.isNotEmpty()) { "getTrashIntent requires a non-empty URI list" }
        return MediaStore.createTrashRequest(context.contentResolver, uris, trashed)
    }
}
