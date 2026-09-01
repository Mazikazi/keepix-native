package com.sese.keepix.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.sese.keepix.data.KeepixPreferences
import com.sese.keepix.data.MediaItem
import com.sese.keepix.data.MediaPageKey
import com.sese.keepix.data.MediaRepository
import com.sese.keepix.data.MediaAccessException
import com.sese.keepix.db.AppDatabase
import com.sese.keepix.db.BinItemEntity
import com.sese.keepix.db.KeptItemEntity
import com.sese.keepix.utils.SessionCleanupWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val TAG = "KeepixViewModel"

class KeepixViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val binItemDao = AppDatabase.getDatabase(application).binItemDao()
    private val keptItemDao = AppDatabase.getDatabase(application).keptItemDao()
    val prefs = KeepixPreferences(application)

    // Media state
    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Stats
    private val _deletedCount = MutableStateFlow(0)
    val deletedCount: StateFlow<Int> = _deletedCount.asStateFlow()

    // Session-scoped kept counter. keptItemCount (below) is the all-time DB
    // total and stays correct for the kept-button badge; this one is for the
    // "You kept X and deleted Y" empty-state line, which needs both halves in
    // the same (session) unit.
    private val _sessionKeptCount = MutableStateFlow(0)
    val sessionKeptCount: StateFlow<Int> = _sessionKeptCount.asStateFlow()

    // Bin state
    val binItems: StateFlow<List<BinItemEntity>> = binItemDao.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val binCount: StateFlow<Int> = binItemDao.getBinCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Bin rows that cleanup has marked for permanent removal. The Activity turns
     * these into a single `MediaStore.createDeleteRequest` and calls back into
     * [confirmDeletion] with the same ids on `RESULT_OK`, or [deferDeletion] with
     * the same ids on anything else.
     */
    val pendingDeletionUris: StateFlow<List<BinItemEntity>> = binItemDao.getPendingDeletion()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _promptedThisSession = MutableStateFlow(false)

    /**
     * True once the user has been shown — and has dismissed or cancelled — the
     * system delete dialog for the current [pendingDeletionUris] set in this
     * process. Prevents re-prompting in a loop. In-memory only: it resets on the
     * next launch.
     *
     * Exposed as a [StateFlow], not a plain `var`: a Compose `LaunchedEffect`
     * observing [pendingDeletionUris] needs this value in its key list to notice
     * when [deleteBinItems] re-arms the prompt for a set of ids whose *content*
     * hasn't changed (e.g. Empty Bin -> Cancel -> Empty Bin again on the same
     * bin). A plain field read from a composable would not trigger recomposition
     * on its own and that re-arm would go unnoticed until the next process launch.
     */
    val promptedThisSession: StateFlow<Boolean> = _promptedThisSession.asStateFlow()

    // Kept state
    val keptItems: StateFlow<List<KeptItemEntity>> = keptItemDao.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val keptItemCount: StateFlow<Int> = keptItemDao.getKeptCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Windowed pagination over the device library (Defect 11), using keyset
    // (not offset) pagination -- see MediaPageKey's doc for why an integer
    // offset is unsafe against a mutating, second-precision-sorted result set.
    private var binMediaIds: Set<Long> = emptySet()
    private var keptMediaIds: Set<Long> = emptySet()

    // (dateAdded, id) of the last row read from the device library, or null
    // before the first page. Passed back into MediaRepository.getMediaPage to
    // resume strictly after it. This is the ONLY pagination cursor; there is
    // no row-count offset anywhere in this class.
    private var pageCursor: MediaPageKey? = null

    // True once a getMediaPage call has returned fewer rows than requested --
    // MediaRepository's contract for "nothing older remains". This, not
    // mediaCount, is what pagination termination relies on: it's read
    // directly off the actual query result on every call, so it can't go
    // stale the way a count snapshotted once at loadMedia() time can (e.g.
    // after the user empties the bin and confirms deletion mid-session).
    private var reachedEnd = false

    // Cached once per loadMedia() purely for a "remaining" style display
    // value if one is ever wanted; never consulted for pagination termination
    // (see reachedEnd above).
    private var mediaCount = 0

    // Every media id ever delivered into _mediaItems this session (whether
    // still present or already swiped away). Grows for the life of the
    // session and is never pruned (~48 bytes/entry for a HashSet<Long> node;
    // fine at realistic per-session swipe volumes). Guards against a
    // duplicate: an id that was excluded at loadMedia() time (bin/kept from a
    // previous session) but whose row hasn't been reached yet by [pageCursor]
    // gets spliced back in immediately by [restoreItem]/[unkeepItem] --
    // without this guard, pagination would later reach that same row, find it
    // no longer excluded, and add it a second time.
    private var seenMediaIds: MutableSet<Long> = mutableSetOf()

    // Re-entrancy guard. Every swipe launches its own coroutine on
    // viewModelScope (Main.immediate), and MediaRepository.getMediaPage
    // suspends on Dispatchers.IO, so two top-ups -- or a top-up racing a
    // fresh loadMedia() -- can trivially interleave: each would read
    // [pageCursor], suspend, and resume to independently overwrite it,
    // silently skipping whatever page sat between the two advances (seenIds
    // only catches duplicates, not this). Main-confined, so a plain Boolean
    // set before the first suspension point and cleared in a finally is
    // sufficient -- no atomics needed. Must be held across both loadNextBatch
    // and loadMedia's cursor reset, since a loadMedia() resetting pageCursor
    // underneath an in-flight top-up would corrupt it the same way.
    private var batchLoadInFlight = false

    init {
        performLaunchCleanup()
        schedulePeriodicCleanup()
    }

    private fun schedulePeriodicCleanup() {
        val workRequest = PeriodicWorkRequestBuilder<SessionCleanupWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
            "session_cleanup",
            // UPDATE, not KEEP: existing installs already have a 15-minute request
            // enqueued under this name and KEEP would leave it running forever.
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /**
     * Runs once per process launch. Rotates the session id — the only place that
     * happens — and *marks* everything the retention policy has expired.
     *
     * Nothing is deleted here. The rows stay in the bin, still restorable, until
     * the user confirms the system delete dialog.
     */
    private fun performLaunchCleanup() {
        viewModelScope.launch {
            val previousSessionId = prefs.generateNewSession()

            if (previousSessionId.isNotEmpty()) {
                // Session-mode items binned before this launch.
                val expiredSessionItems = binItemDao.getExpiredSessionItems(prefs.currentSessionId)
                if (expiredSessionItems.isNotEmpty()) {
                    binItemDao.markPendingDeletion(expiredSessionItems.map { it.id })
                }
            }

            // Timed items whose retention period has elapsed.
            val expiredTimedItems = binItemDao.getExpiredTimedItems(System.currentTimeMillis())
            if (expiredTimedItems.isNotEmpty()) {
                binItemDao.markPendingDeletion(expiredTimedItems.map { it.id })
            }
        }
    }

    fun loadMedia() {
        viewModelScope.launch {
            if (batchLoadInFlight) {
                // An in-flight top-up (or another loadMedia) already owns
                // pageCursor/seenMediaIds; resetting them here would corrupt
                // it (Critical 2). Safe to skip in practice -- loadMedia() is
                // only ever called once at startup/permission-grant, well
                // before any swipe could have a top-up in flight.
                return@launch
            }
            batchLoadInFlight = true
            _isLoading.value = true
            _error.value = null
            try {
                binMediaIds = binItemDao.getAllBinMediaIds().toSet()
                keptMediaIds = keptItemDao.getAllKeptMediaIds().toSet()
                mediaCount = repository.getMediaCount()
                pageCursor = null
                reachedEnd = false
                seenMediaIds = mutableSetOf()
                _mediaItems.value = emptyList()
                fetchBatch()
            } catch (e: MediaAccessException) {
                Log.e(TAG, "Failed to load media", e)
                _error.value = e.message ?: "Failed to load media"
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error loading media", e)
                _error.value = "An unexpected error occurred"
            } finally {
                batchLoadInFlight = false
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Fetches pages from [repository] until either [prefs].batchSize new items
     * have survived the bin/kept filter, or [reachedEnd] becomes true -- i.e.
     * the page is topped up rather than handed back short just because it
     * happened to be mostly excluded items. Always requests a full
     * `prefs.batchSize`-sized page per call (never a shrinking "remaining
     * wanted" amount): shrinking the request once most of a batch is already
     * filled degrades a heavily-excluded library (e.g. 9,000 of 10,000 photos
     * already binned) into one row per blocking IPC.
     *
     * Assumes the caller already holds [batchLoadInFlight] -- this function
     * does not touch that flag itself, since [loadMedia] needs to hold it
     * across both the cursor reset and this call.
     */
    private suspend fun fetchBatch() {
        val batchSize = prefs.batchSize
        if (batchSize <= 0 || reachedEnd) return

        val excludedIds = binMediaIds + keptMediaIds
        val newItems = mutableListOf<MediaItem>()

        try {
            while (newItems.size < batchSize && !reachedEnd) {
                val page = repository.getMediaPage(pageCursor, batchSize)

                if (page.size < batchSize) {
                    // MediaRepository's termination contract: fewer rows than
                    // requested means nothing older is left. Read directly off
                    // this result, not off mediaCount (which a confirmed bin
                    // deletion or external change can make stale mid-session).
                    reachedEnd = true
                }
                if (page.isEmpty()) break

                val last = page.last()
                pageCursor = MediaPageKey(last.dateAdded, last.id)

                for (mediaItem in page) {
                    if (mediaItem.id !in excludedIds && seenMediaIds.add(mediaItem.id)) {
                        newItems.add(mediaItem)
                    }
                }
            }
        } catch (e: MediaAccessException) {
            Log.e(TAG, "Failed to load next batch", e)
            _error.value = e.message ?: "Failed to load media"
        }

        if (newItems.isNotEmpty()) {
            _mediaItems.value = _mediaItems.value + newItems
        }
    }

    /**
     * Top-up entry point used by [removeSwipedItem]. Guarded the same way as
     * [loadMedia] (see [batchLoadInFlight]) -- if a top-up or a fresh
     * loadMedia() is already in flight, this is a deliberate no-op rather
     * than a queued retry: the next swipe re-checks the same threshold in
     * [removeSwipedItem] and will retry, so a dropped call here is at worst a
     * one-swipe delay, never a permanent skip.
     */
    private suspend fun loadNextBatch() {
        if (batchLoadInFlight) return
        batchLoadInFlight = true
        try {
            fetchBatch()
        } finally {
            batchLoadInFlight = false
        }
    }

    private suspend fun removeSwipedItem(item: MediaItem) {
        _mediaItems.value = _mediaItems.value.filter { it.id != item.id }

        val batchSize = prefs.batchSize
        if (_mediaItems.value.size < batchSize / 2 && !reachedEnd) {
            loadNextBatch()
        }
    }

    private fun BinItemEntity.toMediaItem(): MediaItem = MediaItem(
        id = mediaId,
        uri = Uri.parse(mediaUri),
        dateAdded = dateTaken / 1000,
        isVideo = mediaType.equals("VIDEO", ignoreCase = true),
        displayName = displayName,
        width = width,
        height = height,
        durationMs = durationMs
    )

    private fun KeptItemEntity.toMediaItem(): MediaItem = MediaItem(
        id = mediaId,
        uri = Uri.parse(mediaUri),
        dateAdded = dateTaken / 1000,
        isVideo = mediaType.equals("VIDEO", ignoreCase = true),
        displayName = displayName,
        width = width,
        height = height,
        durationMs = durationMs
    )

    /**
     * True if [key] sorts at or after (i.e. pagination has already read past)
     * the current [pageCursor], in DATE_ADDED DESC, _ID DESC order. `null`
     * pageCursor means nothing has been read yet, so nothing is past it.
     */
    private fun isPastCursor(key: MediaPageKey): Boolean {
        val cursor = pageCursor ?: return false
        if (key.dateAdded != cursor.dateAdded) return key.dateAdded > cursor.dateAdded
        return key.id > cursor.id
    }

    /**
     * Defect 10: restoring/unkeeping an item must not reset the swipe queue
     * back to the top. Instead of re-querying, splice [item] back into
     * [_mediaItems] at its correct DATE_ADDED-descending position -- but only
     * if pagination has already read past its row (Important 3):
     *  - If [item] is at or past [pageCursor] (or [reachedEnd] -- the whole
     *    library has been read), pagination will never visit that row again,
     *    so it must be spliced in now, and marked seen so a stray future page
     *    can't re-add it (see [seenMediaIds]). It lands at or near index 0 if
     *    it sorts newer than everything currently queued -- a real, visible
     *    change, but the correct one: the queue always surfaces the newest
     *    not-yet-decided item first.
     *  - If [item] is still ahead of the cursor (its row hasn't been read
     *    yet), do nothing beyond un-excluding it (already done by the
     *    caller): the item is, by definition, older than every row currently
     *    in [_mediaItems], so `indexOfFirst` would find no older row and
     *    append it -- placing it after rows pagination hasn't fetched yet and
     *    breaking the DATE_ADDED-descending order this very function depends
     *    on for later splices. Ordinary pagination will deliver it in its
     *    correct place once the cursor actually reaches it.
     */
    private fun spliceIntoQueue(item: MediaItem) {
        val current = _mediaItems.value
        if (current.any { it.id == item.id }) return

        if (!reachedEnd && !isPastCursor(MediaPageKey(item.dateAdded, item.id))) {
            return
        }
        seenMediaIds.add(item.id)

        val insertIndex = current.indexOfFirst { it.dateAdded < item.dateAdded }
        _mediaItems.value = if (insertIndex == -1) {
            current + item
        } else {
            current.toMutableList().apply { add(insertIndex, item) }
        }
    }

    fun markForDeletion(mediaItem: MediaItem) {
        viewModelScope.launch {
            val retentionMode = if (prefs.isSessionMode) "SESSION" else "TIMED"
            val expiryAt = prefs.getExpiryTimestamp()

            binItemDao.insert(
                BinItemEntity(
                    mediaId = mediaItem.id,
                    mediaUri = mediaItem.uri.toString(),
                    displayName = mediaItem.displayName,
                    mediaType = if (mediaItem.isVideo) "VIDEO" else "IMAGE",
                    dateTaken = mediaItem.dateAdded * 1000,
                    deletedAt = System.currentTimeMillis(),
                    expiryAt = expiryAt,
                    sessionId = prefs.currentSessionId,
                    retentionMode = retentionMode,
                    width = mediaItem.width,
                    height = mediaItem.height,
                    durationMs = mediaItem.durationMs
                )
            )
            binMediaIds = binMediaIds + mediaItem.id
            _deletedCount.value++
            removeSwipedItem(mediaItem)
        }
    }

    fun keepMedia(mediaItem: MediaItem) {
        viewModelScope.launch {
            keptItemDao.insert(
                KeptItemEntity(
                    mediaId = mediaItem.id,
                    mediaUri = mediaItem.uri.toString(),
                    displayName = mediaItem.displayName,
                    mediaType = if (mediaItem.isVideo) "VIDEO" else "IMAGE",
                    dateTaken = mediaItem.dateAdded * 1000,
                    keptAt = System.currentTimeMillis(),
                    width = mediaItem.width,
                    height = mediaItem.height,
                    durationMs = mediaItem.durationMs
                )
            )
            keptMediaIds = keptMediaIds + mediaItem.id
            _sessionKeptCount.value++
            removeSwipedItem(mediaItem)
        }
    }

    fun restoreItem(item: BinItemEntity) {
        viewModelScope.launch {
            binItemDao.delete(item)
            binMediaIds = binMediaIds - item.mediaId
            spliceIntoQueue(item.toMediaItem())
        }
    }

    /**
     * User asked to permanently delete these bin items. This only marks them; the
     * Activity picks the marked rows up from [pendingDeletionUris] and launches
     * the system confirmation dialog.
     */
    fun deleteBinItems(items: List<BinItemEntity>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            binItemDao.markPendingDeletion(items.map { it.id })
            // An explicit user request re-arms the prompt even after an earlier
            // cancel. This must happen AFTER markPendingDeletion returns: a
            // LaunchedEffect keyed on both pendingDeletionUris and
            // promptedThisSession could otherwise observe promptedThisSession
            // flip to false while pendingDeletionUris still reflects the old
            // (pre-mark) set, showing a dialog with a stale item count.
            _promptedThisSession.value = false
        }
    }

    /**
     * Drops the Room rows for items whose file deletion the system dialog actually
     * confirmed. This is the ONLY place a bin row is removed for deletion, and it
     * must only be called on `RESULT_OK`.
     *
     * This runs *after* the files are already gone, so a failure here is the worst
     * case in the whole flow: rows would survive as tombstones pointing at dead
     * URIs. It is wrapped so that failure surfaces through [_error] instead of
     * crashing the coroutine and leaving the bin silently full of broken thumbnails.
     */
    fun confirmDeletion(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                binItemDao.deleteByIds(ids)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove confirmed-deleted items from the bin", e)
                _error.value = "Some deleted items could not be removed from the bin."
            }
        }
    }

    /**
     * The user cancelled the system delete dialog for [ids]. Nothing was deleted,
     * so those rows are un-marked back to a normal bin item rather than left
     * latched as `pendingDeletion` forever — otherwise every future launch would
     * re-show the system delete dialog for them with no way out short of
     * restoring items one by one. [promptedThisSession] is still set so an
     * in-flight recomposition doesn't immediately re-show the dialog before the
     * un-mark is observed.
     */
    fun deferDeletion(ids: List<Long>) {
        _promptedThisSession.value = true
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                binItemDao.unmarkPendingDeletion(ids)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to un-mark deferred bin items", e)
                _error.value = "Some items could not be restored to the bin."
            }
        }
    }

    /**
     * Re-arms the delete confirmation prompt without touching any rows. For
     * the case where a separate, explicit delete request marked additional
     * rows pending while a previous request's system dialog was still open:
     * that dialog resolving (via [deferDeletion]) unconditionally re-arms
     * [promptedThisSession] for its OWN ids, which would otherwise also
     * suppress this newer, never-shown batch until the user acts again.
     */
    fun rearmPrompt() {
        _promptedThisSession.value = false
    }

    /**
     * Surfaces an arbitrary user-visible error, for failures that originate
     * outside this ViewModel (e.g. the Activity failing to build or launch the
     * system delete confirmation).
     */
    fun reportError(message: String) {
        _error.value = message
    }

    fun unkeepItem(item: KeptItemEntity) {
        viewModelScope.launch {
            keptItemDao.delete(item)
            keptMediaIds = keptMediaIds - item.mediaId
            spliceIntoQueue(item.toMediaItem())
        }
    }
}
