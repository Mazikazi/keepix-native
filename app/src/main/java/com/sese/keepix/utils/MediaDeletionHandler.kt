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
     * The outcome of [filterExistingUris].
     *
     * @param existing URIs that still resolve to a MediaStore row.
     * @param missing URIs whose file vanished from the device outside the app.
     */
    data class UriFilterResult(
        val existing: List<Uri>,
        val missing: List<Uri>
    )

    /**
     * Returns a [PendingIntent] that prompts the user to confirm deletion of the
     * given URIs. Requires minSdk 30.
     *
     * @param uris must be non-empty and already filtered by [filterExistingUris];
     *   a URI whose file no longer exists makes the whole request fail.
     */
    fun getDeletionIntent(context: Context, uris: List<Uri>): PendingIntent {
        return MediaStore.createDeleteRequest(context.contentResolver, uris)
    }

    /**
     * Splits [uris] into those that still resolve to a MediaStore row and those
     * whose file has been removed from the device by some other app. Callers pass
     * [UriFilterResult.existing] to [getDeletionIntent] and surface
     * [UriFilterResult.missing] to the user, whose bin rows are then stale and
     * safe to drop without a confirmation dialog.
     */
    fun filterExistingUris(context: Context, uris: List<Uri>): UriFilterResult {
        val existing = mutableListOf<Uri>()
        val missing = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.MediaColumns._ID)

        for (uri in uris) {
            val present = try {
                context.contentResolver.query(uri, projection, null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            } catch (e: Exception) {
                // SecurityException / IllegalArgumentException / stale provider —
                // treat as gone rather than failing the whole delete request.
                if (com.sese.keepix.BuildConfig.DEBUG) {
                    android.util.Log.w("MediaDeletionHandler", "URI not resolvable: $uri", e)
                }
                false
            }
            if (present) existing.add(uri) else missing.add(uri)
        }

        return UriFilterResult(existing = existing, missing = missing)
    }
}
