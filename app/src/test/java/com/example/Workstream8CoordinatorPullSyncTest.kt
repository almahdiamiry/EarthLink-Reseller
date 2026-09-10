package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.sync.DataOperationCoordinator
import com.example.core.sync.DataOperationMode
import com.example.core.sync.SyncRepositoryImpl
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Claim: WS8 / CONC-01 — Downward pull sync acquires DataOperationMode.REMOTE_APPLY ownership,
 * enforcing strict mutual exclusion against local transactions and maintenance operations (INV-11 / RED-1 / RED-7).
 * Seam: Robolectric Android runtime with real SyncRepositoryImpl and mock Firebase.
 * Independent Oracle: Invariant INV-11 (DataOperationCoordinator provides deterministic mutual exclusion)
 * and DataOperationMode.REMOTE_APPLY observation during actual pullRemoteChanges execution.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Workstream8CoordinatorPullSyncTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dbFile: File
    private lateinit var syncRepo: SyncRepositoryImpl
    private val testUid = "test_ws8_user"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_ws8_${UUID.randomUUID()}.db")
        db = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        syncRepo = SyncRepositoryImpl(
            context = context,
            appDatabase = db,
            outboxDao = db.syncOutboxDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun pullSyncRemoteApplyMode_blocksConcurrentSyncAndMaintainsExclusion() = runBlocking {
        val remoteApplyActive = CompletableDeferred<Unit>()
        val releaseRemoteApply = CompletableDeferred<Unit>()
        val concurrentSyncBlocked = AtomicBoolean(false)

        val pullJob = launch(Dispatchers.Default) {
            DataOperationCoordinator.withOperation(DataOperationMode.REMOTE_APPLY) {
                assertEquals(DataOperationMode.REMOTE_APPLY, DataOperationCoordinator.currentMode)
                remoteApplyActive.complete(Unit)
                releaseRemoteApply.await()
            }
        }

        remoteApplyActive.await()

        // Verify while REMOTE_APPLY is active, tryWithOperation(SYNC) fails immediately
        val localSyncAttempt = launch(Dispatchers.Default) {
            val result = DataOperationCoordinator.tryWithOperation(DataOperationMode.SYNC) {
                "SHOULD_NOT_EXECUTE"
            }
            if (result == null) {
                concurrentSyncBlocked.set(true)
            }
        }
        localSyncAttempt.join()

        assertTrue("Local SYNC operation must be blocked while REMOTE_APPLY pull is active", concurrentSyncBlocked.get())

        // Release REMOTE_APPLY
        releaseRemoteApply.complete(Unit)
        pullJob.join()

        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
        assertFalse(DataOperationCoordinator.isLocked)
    }

    @Test
    fun maintenanceOperations_blockRemoteApplyPullSync() = runBlocking {
        val maintenanceActive = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val remoteApplyBlocked = AtomicBoolean(false)

        val restoreJob = launch(Dispatchers.Default) {
            DataOperationCoordinator.withOperation(DataOperationMode.RESTORE) {
                assertTrue(DataOperationCoordinator.isMaintenanceActive)
                maintenanceActive.complete(Unit)
                releaseMaintenance.await()
            }
        }

        maintenanceActive.await()

        val pullAttempt = launch(Dispatchers.Default) {
            val result = DataOperationCoordinator.tryWithOperation(DataOperationMode.REMOTE_APPLY) {
                "SHOULD_NOT_RUN_DURING_RESTORE"
            }
            if (result == null) {
                remoteApplyBlocked.set(true)
            }
        }
        pullAttempt.join()

        assertTrue("REMOTE_APPLY pull must be blocked while RESTORE maintenance is active", remoteApplyBlocked.get())

        releaseMaintenance.complete(Unit)
        restoreJob.join()

        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
    }

    @Test
    fun syncAllData_executesPullRemoteChangesUnderRemoteApplyMode() = runBlocking {
        val mockAuth = mock(FirebaseAuth::class.java)
        val mockUser = mock(FirebaseUser::class.java)
        `when`(mockAuth.currentUser).thenReturn(mockUser)
        `when`(mockUser.uid).thenReturn(testUid)
        `when`(mockUser.isAnonymous).thenReturn(false)

        val mockFirestore = mock(FirebaseFirestore::class.java, RETURNS_DEEP_STUBS)
        val emptySnapshot = mock(QuerySnapshot::class.java)
        `when`(emptySnapshot.isEmpty).thenReturn(true)
        `when`(emptySnapshot.documents).thenReturn(emptyList())

        val observedMode = AtomicReference<DataOperationMode>()
        val localSyncBlocked = AtomicBoolean(false)

        // Intercept when pullRemoteChanges executes query.get(Source.SERVER)
        `when`(
            mockFirestore.collection(anyString())
                .document(anyString())
                .collection(anyString())
                .orderBy(anyString())
                .orderBy(any<FieldPath>())
                .limit(anyLong())
                .get(Source.SERVER)
        ).thenAnswer {
            observedMode.set(DataOperationCoordinator.currentMode)
            val blocked = runBlocking {
                DataOperationCoordinator.tryWithOperation(DataOperationMode.SYNC) { "FAIL" }
            }
            if (blocked == null) {
                localSyncBlocked.set(true)
            }
            Tasks.forResult(emptySnapshot)
        }

        // Set last synced timestamp and auth token so auth check passes and settings sync is skipped
        val prefs = com.example.core.security.PreferenceManager(context)
        prefs.saveAuthToken("test_auth_token_for_sync")
        prefs.saveSettingsLastSyncedTimestamp(System.currentTimeMillis())

        syncRepo.setFirebaseInstancesForTest(mockAuth, mockFirestore)

        val syncSuccess = syncRepo.triggerSyncOneShot()
        if (!syncSuccess) {
            val progress = syncRepo.syncProgress.value
            fail("Sync failed: lastError=${progress.lastError}, phase=${progress.phase}")
        }

        assertEquals(
            "pullRemoteChanges must execute under DataOperationMode.REMOTE_APPLY",
            DataOperationMode.REMOTE_APPLY,
            observedMode.get()
        )
        assertTrue(
            "Concurrent SYNC operation must be blocked while pullRemoteChanges is running",
            localSyncBlocked.get()
        )
        assertEquals(
            "Coordinator mode must return to IDLE after sync completes",
            DataOperationMode.IDLE,
            DataOperationCoordinator.currentMode
        )
    }
}
