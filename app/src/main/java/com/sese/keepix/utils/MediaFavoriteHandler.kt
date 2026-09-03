package com.sese.keepix.utils

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * The only way this app writes Android's system favorite flag.
 *
 * The app does not own the MediaStore rows it stars, so the flag can only be set
 * through [MediaStore.createFavoriteRequest] and the system confirmation dialog it
 * produces. Nothing is written until that dialog returns `RESULT_OK`.
 *
 * This writes MediaStore *metadata* only — no file contents are read, copied or
 * modified.
 */
object MediaFavoriteHandler {

    /**
     * Returns a [PendingIntent] prompting the user to confirm setting or clearing
     * the favorite flag on [uris]. Requires minSdk 30.
     *
     * [MediaStore.createFavoriteRequest] takes ONE boolean for the whole batch, so
     * favoriting and un-favoriting cannot share a request. Callers must partition
     * by target state and launch the two requests sequentially — never together,
     * or the second `IntentSender` is dropped.
     *
     * @param uris must be non-empty and already filtered by
     *   [MediaUriFilter.filterExistingUris]; a URI whose row no longer exists makes
     *   the whole request fail.
     * @param favorite the target state for every URI in this batch.
     */
    fun getFavoriteIntent(
        context: Context,
        uris: List<Uri>,
        favorite: Boolean
    ): PendingIntent {
        require(uris.isNotEmpty()) { "getFavoriteIntent requires a non-empty URI list" }
        return MediaStore.createFavoriteRequest(context.contentResolver, uris, favorite)
    }
}
