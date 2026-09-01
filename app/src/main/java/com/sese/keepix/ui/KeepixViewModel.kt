package com.sese.keepix.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.sese.keepix.data.KeepixPreferences
import com.sese.keepix.data.MediaItem
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

    // Windowed pagination over the device library (Defect 11). Rather than
    // loading the whole library into memory, we track how far into the
    // DATE_ADDED-descending cursor we've read and fetch pages on demand.
    private var binMediaIds: Set<Long> = emptySet()
    private var keptMediaIds: Set<Long> = emptySet()

    // Number of rows READ from the underlying cursor so far -- not the number
    // of items surviving the bin/kept filter. Excluded rows still occupy a
    // cursor offset, so this must advance by rows read, or the next page would
    // re-read (and re-filter) rows we've already seen.
    private var mediaOffset = 0

    // Total rows in the underlying cursor, snapshotted once per loadMedia().
    private var totalMediaCount = 0

    // Every media id ever delivered into _mediaItems this session (whether
    // still present or already swiped away). Guards against a duplicate: an
    // id that was excluded at loadMedia() time (bin/kept from a previous
    // session) but whose cursor row hasn't been reached yet by [mediaOffset]
    // gets spliced back in immediately by [restoreItem]/[unkeepItem] -- without
    // this guard, pagination would later reach that same row, find it no
    // longer excluded, and add it a second time.
    private var seenMediaIds: MutableSet<Long> = mutableSetOf()

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
            _isLoading.value = true
            _error.value = null
            try {
                binMediaIds = binItemDao.getAllBinMediaIds().toSet()
                keptMediaIds = keptItemDao.getAllKeptMediaIds().toSet()
                totalMediaCount = repository.getMediaCount()
                mediaOffset = 0
                seenMediaIds = mutableSetOf()
                _mediaItems.value = emptyList()
                loadNextBatch()
            } catch (e: MediaAccessException) {
                Log.e(TAG, "Failed to load media", e)
                _error.value = e.message ?: "Failed to load media"
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error loading media", e)
                _error.value = "An unexpected error occurred"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Fetches pages from [repository] until either [prefs].batchSize new items
     * have survived the bin/kept filter, or the underlying cursor is exhausted
     * (`mediaOffset >= totalMediaCount`) -- i.e. the page is topped up rather
     * than handed back short just because a page happened to be mostly
     * excluded items.
     *
     * Never throws: a [MediaAccessException] from the repository is caught and
     * surfaced through [_error], same as [loadMedia].
     */
    private suspend fun loadNextBatch() {
        val batchSize = prefs.batchSize
        if (batchSize <= 0 || mediaOffset >= totalMediaCount) return

        val excludedIds = binMediaIds + keptMediaIds
        val newItems = mutableListOf<MediaItem>()

        try {
            while (newItems.size < batchSize && mediaOffset < totalMediaCount) {
                val remainingWanted = batchSize - newItems.size
                val page = repository.getMediaPage(mediaOffset, remainingWanted)

                if (page.isEmpty()) {
                    // We expected more rows (mediaOffset < totalMediaCount) but
                    // got none back: file(s) at this offset were removed from
                    // the device after getMediaCount() snapshotted the total.
                    // Stop chasing a count that no longer exists rather than
                    // looping forever, and tell the user why the queue came up
                    // short.
                    totalMediaCount = mediaOffset
                    _error.value = "Photo no longer on device"
                    break
                }

                mediaOffset += page.size
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

    private suspend fun removeSwipedItem(item: MediaItem) {
        _mediaItems.value = _mediaItems.value.filter { it.id != item.id }

        val batchSize = prefs.batchSize
        if (_mediaItems.value.size < batchSize / 2 && mediaOffset < totalMediaCount) {
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
     * Defect 10: restoring/unkeeping an item must not reset the swipe queue
     * back to the top. Instead of re-querying, splice [item] back into
     * [_mediaItems] at its correct DATE_ADDED-descending position:
     *  - If it sorts newer than (or equal to) the current front of the queue,
     *    it lands at or near index 0 -- the very next card the user sees. This
     *    is a real, visible change, but it's the correct one: the queue is
     *    defined to always surface the newest not-yet-decided item first.
     *  - If it sorts older, it lands further back in the still-untouched part
     *    of the queue and changes nothing the user is about to see.
     * Marks the id as seen so a not-yet-reached pagination page can never
     * re-add it (see [seenMediaIds]).
     */
    private fun spliceIntoQueue(item: MediaItem) {
        val current = _mediaItems.value
        if (current.any { it.id == item.id }) return
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
