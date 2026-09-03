package com.sese.keepix.utils

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * The only way this app obtains permission to write a media file's contents.
 *
 * Mirrors [MediaFavoriteHandler] and [MediaDeletionHandler]: the app does not own
 * these MediaStore rows, so writing requires [MediaStore.createWriteRequest] and
 * the system confirmation it produces. Nothing is written until that returns
 * `RESULT_OK`.
 *
 * Unlike `createFavoriteRequest`, this carries no per-batch mode flag, so one
 * request can cover a whole run.
 */
object MediaWriteHandler {

    /**
     * @param uris must be non-empty and already filtered by
     *   [MediaUriFilter.filterExistingUris]; a URI whose row no longer exists
     *   makes the whole request fail.
     */
    fun getWriteIntent(context: Context, uris: List<Uri>): PendingIntent {
        require(uris.isNotEmpty()) { "getWriteIntent requires a non-empty URI list" }
        return MediaStore.createWriteRequest(context.contentResolver, uris)
    }
}
