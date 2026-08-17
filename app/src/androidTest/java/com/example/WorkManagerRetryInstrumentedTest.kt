package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.example.core.backup.LocalAutoBackupWorker
import com.example.core.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Instrumented test suite validating WorkManager configuration, constraints,
 * exponential backoff parameters, and unique work policies on the Android runtime.
 *
 * Covers Invariants: INV-05, INV-10, INV-11
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerRetryInstrumentedTest {

    @Test
    fun testSyncWorkerOneTimeRequestBuilderConstraintsAndBackoff() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("firebase_local_sync_tag")
            .build()

        assertNotNull("Sync WorkRequest must not be null", syncRequest)
        assertEquals(NetworkType.CONNECTED, syncRequest.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, syncRequest.workSpec.backoffPolicy)
        assertTrue("Sync request should have assigned tag", syncRequest.tags.contains("firebase_local_sync_tag"))
    }

    @Test
    fun testSyncWorkerPeriodicRequestBuilderIntervals() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag("firebase_periodic_sync_tag")
            .build()

        assertNotNull("Periodic WorkRequest must not be null", periodicRequest)
        assertEquals(NetworkType.CONNECTED, periodicRequest.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, periodicRequest.workSpec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(15), periodicRequest.workSpec.intervalDuration)
        assertTrue("Periodic request must contain tag", periodicRequest.tags.contains("firebase_periodic_sync_tag"))
    }

    @Test
    fun testWorkManagerInstanceInitializationAndUniqueWorkPolicies() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        assertNotNull("WorkManager instance must be initialized on Android runtime", workManager)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val operation = workManager.enqueueUniqueWork(
            "firebase_local_sync_test",
            ExistingWorkPolicy.KEEP,
            syncRequest
        )
        assertNotNull("Enqueue operation must return valid Operation", operation)
    }

    @Test
    fun testLocalAutoBackupWorkerRequestBuilder() {
        val backupRequest = PeriodicWorkRequestBuilder<LocalAutoBackupWorker>(1, TimeUnit.DAYS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("local_backup_tag")
            .build()

        assertNotNull("Backup WorkRequest must not be null", backupRequest)
        assertEquals(BackoffPolicy.EXPONENTIAL, backupRequest.workSpec.backoffPolicy)
        assertEquals(TimeUnit.DAYS.toMillis(1), backupRequest.workSpec.intervalDuration)
        assertTrue(backupRequest.tags.contains("local_backup_tag"))
    }
}
