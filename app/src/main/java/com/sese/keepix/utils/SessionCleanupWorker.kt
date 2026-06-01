package com.sese.keepix.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sese.keepix.data.KeepixPreferences
import com.sese.keepix.db.AppDatabase

private const val TAG = "SessionCleanupWorker"

class SessionCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting session cleanup")
            
            val prefs = KeepixPreferences(applicationContext)
            val binItemDao = AppDatabase.getDatabase(applicationContext).binItemDao()
            
            val previousSessionId = prefs.generateNewSession()
            
            if (previousSessionId.isNotEmpty()) {
                val expiredSessionItems = binItemDao.getExpiredSessionItems(prefs.currentSessionId)
                if (expiredSessionItems.isNotEmpty()) {
                    val uris = expiredSessionItems.map { Uri.parse(it.mediaUri) }
                    MediaDeletionHandler.deleteMediaDirectly(applicationContext, uris)
                    binItemDao.deleteByIds(expiredSessionItems.map { it.id })
                    Log.d(TAG, "Deleted ${expiredSessionItems.size} session items")
                }
            }
            
            val expiredTimedItems = binItemDao.getExpiredTimedItems(System.currentTimeMillis())
            if (expiredTimedItems.isNotEmpty()) {
                val uris = expiredTimedItems.map { Uri.parse(it.mediaUri) }
                MediaDeletionHandler.deleteMediaDirectly(applicationContext, uris)
                binItemDao.deleteByIds(expiredTimedItems.map { it.id })
                Log.d(TAG, "Deleted ${expiredTimedItems.size} timed items")
            }
            
            Log.d(TAG, "Session cleanup completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Session cleanup failed", e)
            Result.retry()
        }
    }
}
