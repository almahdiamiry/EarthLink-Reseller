package com.example.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.EarthlinkApp

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? EarthlinkApp
            ?: return Result.failure()

        val token = app.preferenceManager.getAuthToken()
        if (token.isNullOrEmpty()) {
            android.util.Log.w("SyncWorker", "No auth token in active session. Aborting background sync execution.")
            return Result.failure()
        }

        try {
            ExpiryNotificationManager.checkAndNotifyExpiringSubscriptions(
                applicationContext,
                app.database
            )
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            android.util.Log.e("SyncWorker", "Expiry alert monitor failed", e)
        }

        try {
            app.localLedgerRepository.sweepAndResolvePendingOperations(app.earthlinkGateway)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            android.util.Log.e("SyncWorker", "Pending operation recovery sweep failed", e)
        }

        return try {
            val syncRepo = app.syncRepository
            if (runAttemptCount >= 3) {
                android.util.Log.w("SyncWorker", "Max sync attempts reached ($runAttemptCount). Gracefully completing work.")
                return Result.failure()
            }
            val success = syncRepo.triggerSyncOneShot()
            if (success) {
                Result.success()
            } else {
                if (syncRepo.syncState.value == com.example.domain.repository.SyncStatusState.AUTH_REQUIRED) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            android.util.Log.e("SyncWorker", "Background sync execution exception", e)
            if (app.syncRepository.syncState.value == com.example.domain.repository.SyncStatusState.AUTH_REQUIRED || runAttemptCount >= 3) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}
