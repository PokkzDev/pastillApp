package com.pokkzdev.pastillapp

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based sync service to periodically upload local events to Firebase
 */
class PillEventSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "PillEventSyncWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting sync work")
            
            val auth = FirebaseAuthClient.auth
            val currentUser = auth.currentUser
            
            if (currentUser == null) {
                Log.d(TAG, "No user logged in, skipping sync")
                return Result.success()
            }
            
            val userId = currentUser.uid
            
            // Check if network is available
            if (!NetworkUtils.isNetworkAvailable(applicationContext)) {
                Log.d(TAG, "Network not available, skipping sync")
                return Result.retry() // Retry later when network is available
            }
            
            // Sync local events to Firebase
            val syncedCount = PillEventFirestoreService.syncLocalEventsToFirebase(userId, applicationContext)
            
            if (syncedCount > 0) {
                Log.d(TAG, "Successfully synced $syncedCount events to Firebase")
            } else {
                Log.d(TAG, "No events to sync")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during sync work", e)
            Result.retry() // Retry on failure
        }
    }
}

/**
 * Utility class to manage periodic sync of local events to Firebase
 */
object PillEventSyncService {
    private const val TAG = "PillEventSyncService"
    private const val SYNC_WORK_NAME = "pill_event_sync_work"
    
    /**
     * Start periodic sync (every 15 minutes when conditions are met)
     */
    fun startPeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()
        
        val syncRequest = PeriodicWorkRequestBuilder<PillEventSyncWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES // Flex interval
        )
            .setConstraints(constraints)
            .addTag(SYNC_WORK_NAME)
            .build()
        
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        
        Log.d(TAG, "Periodic sync started")
    }
    
    /**
     * Stop periodic sync
     */
    fun stopPeriodicSync(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(SYNC_WORK_NAME)
        Log.d(TAG, "Periodic sync stopped")
    }
    
    /**
     * Trigger immediate sync (one-time work)
     */
    fun triggerSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val syncRequest = OneTimeWorkRequestBuilder<PillEventSyncWorker>()
            .setConstraints(constraints)
            .addTag(SYNC_WORK_NAME)
            .build()
        
        WorkManager.getInstance(context)
            .enqueue(syncRequest)
        
        Log.d(TAG, "Immediate sync triggered")
    }
}

