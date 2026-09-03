package com.sese.keepix.utils

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sese.keepix.db.AppDatabase

private const val TAG = "SessionCleanupWorker"

/**
 * Daily background sweep.
 *
 * It only ever *marks* expired TIMED items as pending deletion. It never touches
 * files and never drops a row: removal needs a user-confirmed system dialog, which
 * a worker cannot show.
 *
 * It deliberately does not rotate the session id. Rotating it here used to expire
 * the *running* app's session and destroy items the user had just binned; session
 * rotation now happens only once, at app launch.
 */
class SessionCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting retention sweep")

            val binItemDao = AppDatabase.getDatabase(applicationContext).binItemDao()

            val expiredTimedItems = binItemDao.getExpiredTimedItems(System.currentTimeMillis())
            if (expiredTimedItems.isNotEmpty()) {
                binItemDao.markPendingDeletion(expiredTimedItems.map { it.id })
                Log.d(TAG, "Marked ${expiredTimedItems.size} timed items for deletion")
            }

            Log.d(TAG, "Retention sweep completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Retention sweep failed", e)
            Result.retry()
        }
    }
}
