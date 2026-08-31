package com.sese.keepix.utils

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.sese.keepix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only way this app removes a media file from the device.
 *
 * The app does not own the MediaStore rows it deletes, so `ContentResolver.delete`
 * is not usable — it throws `RecoverableSecurityException`. Every removal goes
 * through [MediaStore.createDeleteRequest] and the system confirmation dialog it
 * produces, and a Room row is only dropped once that dialog returns `RESULT_OK`.
 */
object MediaDeletionHandler {

    private const val TAG = "MediaDeletionHandler"

    /**
     * The outcome of [filterExistingUris].
     *
     * @param existing URIs to pass to [getDeletionIntent]. This includes every URI
     *   that still resolves to a MediaStore row, PLUS any URI this app could not
     *   prove is gone (e.g. a `SecurityException` from scoped/partial media access
     *   on Android 14+, or a malformed URI). Those are deliberately not classified
     *   as missing — the system delete dialog runs with broader access than this
     *   app's `ContentResolver` and can adjudicate them correctly; worst case a
     *   stale entry makes that one request fail, which is safe.
     * @param missing URIs whose MediaStore row is confirmed absent — a query that
     *   returned a cursor with no rows. These are stale and safe for the caller to
     *   drop without a confirmation dialog.
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
        require(uris.isNotEmpty()) { "getDeletionIntent requires a non-empty URI list" }
        return MediaStore.createDeleteRequest(context.contentResolver, uris)
    }

    /**
     * Splits [uris] into those safe to pass to [getDeletionIntent] and those whose
     * MediaStore row is confirmed gone. See [UriFilterResult] for exactly what each
     * bucket means and why — the distinction is NOT "query succeeded" vs "query
     * failed"; it is "proven absent" vs "everything else". A `SecurityException`
     * (e.g. the URI falls outside a `READ_MEDIA_VISUAL_USER_SELECTED` grant) proves
     * nothing about whether the file still exists and must never be treated as
     * proof of absence — doing so would let a caller drop a bin row for a file that
     * is still on the device, which is the exact defect this mark-then-confirm
     * design exists to prevent.
     *
     * This performs one blocking `ContentResolver.query` IPC per URI; callers must
     * invoke it off the main thread, which this suspend function guarantees.
     */
    suspend fun filterExistingUris(context: Context, uris: List<Uri>): UriFilterResult =
        withContext(Dispatchers.IO) {
            val existing = mutableListOf<Uri>()
            val missing = mutableListOf<Uri>()
            val projection = arrayOf(MediaStore.MediaColumns._ID)

            for (uri in uris) {
                val isConfirmedMissing = try {
                    // A null cursor is a provider-side failure (dead/restarting
                    // MediaProvider, unmounted volume), not proof the row is gone —
                    // only an actual cursor with zero rows proves that. So `null`
                    // must fall through to `false` (-> existing), same as a failed
                    // query, not be treated as "confirmed missing".
                    context.contentResolver.query(uri, projection, null, null, null)
                        ?.use { !it.moveToFirst() } ?: false
                } catch (e: SecurityException) {
                    // Can't prove absence — scoped/partial access, not "file is gone".
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "URI not queryable (permission scope): $uri", e)
                    }
                    false
                } catch (e: IllegalArgumentException) {
                    // Malformed URI / stale provider — same reasoning: not proof of absence.
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "URI not queryable (invalid): $uri", e)
                    }
                    false
                } catch (e: Exception) {
                    // Any other failure (e.g. a wedged MediaProvider throwing
                    // DeadObjectException/RuntimeException) must not escape this
                    // suspend function and crash the caller's coroutine, and must
                    // not be treated as proof of absence either. Fail safe on both
                    // axes: swallow it and route the URI to `existing`.
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "URI not queryable (unexpected failure): $uri", e)
                    }
                    false
                }
                if (isConfirmedMissing) missing.add(uri) else existing.add(uri)
            }

            UriFilterResult(existing = existing, missing = missing)
        }
}
