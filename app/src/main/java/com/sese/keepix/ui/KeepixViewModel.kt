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
import com.sese.keepix.utils.MediaDeletionHandler
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
        performSessionCleanup()
        schedulePeriodicCleanup()
    }

    private fun schedulePeriodicCleanup() {
        val workRequest = PeriodicWorkRequestBuilder<SessionCleanupWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
            "session_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun performSessionCleanup() {
        viewModelScope.launch {
            val previousSessionId = prefs.generateNewSession()

            if (previousSessionId.isNotEmpty()) {
                // Delete session-mode items from previous session
                val expiredSessionItems = binItemDao.getExpiredSessionItems(prefs.currentSessionId)
                if (expiredSessionItems.isNotEmpty()) {
                    val uris = expiredSessionItems.map { Uri.parse(it.mediaUri) }
                    MediaDeletionHandler.deleteMediaDirectly(getApplication(), uris)
                    binItemDao.deleteByIds(expiredSessionItems.map { it.id })
                }
            }

            // Delete timed items that have expired
            val expiredTimedItems = binItemDao.getExpiredTimedItems(System.currentTimeMillis())
            if (expiredTimedItems.isNotEmpty()) {
                val uris = expiredTimedItems.map { Uri.parse(it.mediaUri) }
                MediaDeletionHandler.deleteMediaDirectly(getApplication(), uris)
                binItemDao.deleteByIds(expiredTimedItems.map { it.id })
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

    fun getUrisForDeletion(): List<Uri> {
        return binItems.value.map { Uri.parse(it.mediaUri) }
    }

    fun clearBin() {
        viewModelScope.launch {
            binItemDao.clearAll()
        }
    }

    fun deleteBinItems(items: List<BinItemEntity>) {
        viewModelScope.launch {
            val uris = items.map { Uri.parse(it.mediaUri) }
            MediaDeletionHandler.deleteMediaDirectly(getApplication(), uris)
            binItemDao.deleteByIds(items.map { it.id })
        }
    }

    fun unkeepItem(item: KeptItemEntity) {
        viewModelScope.launch {
            keptItemDao.delete(item)
            loadMedia()
        }
    }

    fun clearKept() {
        viewModelScope.launch {
            keptItemDao.clearAll()
        }
    }

    val remainingCount: Int
        get() = _mediaItems.value.size
}
