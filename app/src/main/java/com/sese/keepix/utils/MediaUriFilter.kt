package com.sese.keepix.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.sese.keepix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaUriFilter {

    private const val TAG = "MediaUriFilter"

    /**
     * The outcome of [filterExistingUris].
     *
     * @param existing URIs to pass to [getDeletionIntent]. This includes every URI
     *   that still resolves to a MediaStore row, PLUS any URI this app could not
     *   prove is gone (e.g. a `SecurityException` from scoped/partial media access
     *   on Android 14+, a malformed URI, or -- under a
     *   `READ_MEDIA_VISUAL_USER_SELECTED`-only grant -- EVERY URI, since an empty
     *   cursor there cannot be told apart from "outside the selection"; see
     *   [filterExistingUris]'s `hasOnlyPartialMediaAccess` parameter). Those are
     *   deliberately not classified as missing — the system delete dialog runs
     *   with broader access than this app's `ContentResolver` and can adjudicate
     *   them correctly; worst case a stale entry makes that one request fail,
     *   which is safe.
     * @param missing URIs whose MediaStore row is confirmed absent — a query that
     *   returned a cursor with no rows AND the caller holds full (non-partial)
     *   media access, so an empty cursor can only mean "genuinely gone". These are
     *   stale and safe for the caller to drop without a confirmation dialog.
     */
    data class UriFilterResult(
        val existing: List<Uri>,
        val missing: List<Uri>
    )

    /**
     * Splits [uris] into those safe to pass to [getDeletionIntent] and those whose
     * MediaStore row is confirmed gone. See [UriFilterResult] for exactly what each
     * bucket means and why — the distinction is NOT "query succeeded" vs "query
     * failed"; it is "proven absent" vs "everything else".
     *
     * Two distinct platform behaviours can make a query fail to prove anything,
     * and this function must treat both the same way (route to `existing`):
     *  - A `SecurityException` (e.g. the URI falls outside a full media-permission
     *    grant) proves nothing about whether the file still exists.
     *  - Under a `READ_MEDIA_VISUAL_USER_SELECTED`-ONLY grant (Android 14+
     *    "Select photos…"), MediaProvider enforces the grant by ROW-LEVEL
     *    FILTERING instead: a query for a URI outside the current selection
     *    returns a normal cursor with ZERO rows — the identical shape to "this
     *    file was genuinely deleted". [hasOnlyPartialMediaAccess] exists because
     *    of this second case: with only a partial grant, an empty cursor cannot
     *    be trusted as proof of absence either (the user may simply have
     *    deselected that photo in system Settings, which is a first-class Android
     *    flow, not a deletion), so every URI is routed to `existing` unconditionally
     *    in that mode and the empty-cursor check below never runs.
     *
     * Either failure mode, if wrongly treated as proof of absence, would let a
     * caller drop a bin row for a file that is still on the device — the exact
     * defect this mark-then-confirm design exists to prevent. This fix is correct
     * under either interpretation of how MediaProvider actually enforces a
     * selected-photos grant: whether it throws `SecurityException` (already
     * handled below) or filters rows silently (handled by
     * [hasOnlyPartialMediaAccess]), the outcome is the same — routed to
     * `existing`, adjudicated by the system delete dialog instead.
     *
     * This performs one blocking `ContentResolver.query` IPC per URI; callers must
     * invoke it off the main thread, which this suspend function guarantees.
     *
     * @param hasOnlyPartialMediaAccess true when the caller's only media access is
     *   the API 34+ `READ_MEDIA_VISUAL_USER_SELECTED` partial grant (see
     *   `hasOnlyPartialMediaAccess()` in `MainActivity.kt`), not the full
     *   `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` pair. Defaults to `false` (the
     *   pre-Android-14 / full-grant behaviour: an empty cursor is trusted as
     *   proof of absence).
     */
    suspend fun filterExistingUris(
        context: Context,
        uris: List<Uri>,
        hasOnlyPartialMediaAccess: Boolean = false
    ): UriFilterResult =
        withContext(Dispatchers.IO) {
            val existing = mutableListOf<Uri>()
            val missing = mutableListOf<Uri>()
            val projection = arrayOf(MediaStore.MediaColumns._ID)

            for (uri in uris) {
                val isConfirmedMissing = if (hasOnlyPartialMediaAccess) {
                    // Can't prove absence under a partial grant: an empty
                    // cursor here means either "genuinely deleted" or "outside
                    // the current selection", and this app has no way to tell
                    // which. Never classify as missing in this mode.
                    false
                } else {
                    try {
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
                }
                if (isConfirmedMissing) missing.add(uri) else existing.add(uri)
            }

            UriFilterResult(existing = existing, missing = missing)
        }
}
