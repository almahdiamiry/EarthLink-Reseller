package com.example.core.backup

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.EarthlinkApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class LocalAutoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as EarthlinkApp
        val prefs = app.preferenceManager

        if (!prefs.getLocalBackupEnabled()) {
            Log.d("LocalAutoBackupWorker", "Local auto backup is disabled in settings.")
            return@withContext Result.success()
        }

        try {
            val zipBackupFile = BackupManager.createDailyRollingBackup(applicationContext)

            if (zipBackupFile != null && zipBackupFile.exists()) {
                val now = System.currentTimeMillis()
                prefs.saveLocalLastBackupTime(now)
                Log.i("LocalAutoBackupWorker", "Daily local backup completed successfully.")
                Result.success()
            } else {
                Log.e("LocalAutoBackupWorker", "Local backup failed to create file (attempt $runAttemptCount).")
                if (runAttemptCount >= 3) Result.failure() else Result.retry()
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("LocalAutoBackupWorker", "Error during daily local backup execution (attempt $runAttemptCount)", e)
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "local_daily_backup_work"

        fun schedule(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                Log.i("LocalAutoBackupWorker", "Cancelled daily local auto backup schedule.")
                return
            }

            val request = PeriodicWorkRequestBuilder<LocalAutoBackupWorker>(1, TimeUnit.DAYS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i("LocalAutoBackupWorker", "Scheduled daily local auto backup work.")
        }
    }
}
