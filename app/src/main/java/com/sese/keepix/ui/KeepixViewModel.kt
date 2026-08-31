package com.sese.keepix.ui

import android.app.Application
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

    // All media from device (full list for pagination)
    private var allMedia: List<MediaItem> = emptyList()
    private var binMediaIds: Set<Long> = emptySet()
    private var keptMediaIds: Set<Long> = emptySet()
    private var loadedCount = 0

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
                val excludedIds = binMediaIds + keptMediaIds
                allMedia = repository.getMediaItems().filter { it.id !in excludedIds }
                loadedCount = 0
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

    private fun loadNextBatch() {
        val batchSize = prefs.batchSize
        val newItems = allMedia.drop(loadedCount).take(batchSize)
        _mediaItems.value = _mediaItems.value + newItems
        loadedCount += newItems.size
    }

    private fun removeSwipedItem(item: MediaItem) {
        allMedia = allMedia.filter { it.id != item.id }
        _mediaItems.value = _mediaItems.value.filter { it.id != item.id }
        loadedCount = (loadedCount - 1).coerceAtLeast(0)
        
        val batchSize = prefs.batchSize
        if (_mediaItems.value.size < batchSize / 2 && loadedCount < allMedia.size) {
            loadNextBatch()
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
            removeSwipedItem(mediaItem)
        }
    }

    fun restoreItem(item: BinItemEntity) {
        viewModelScope.launch {
            binItemDao.delete(item)
            loadMedia()
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
            loadMedia()
        }
    }
}
