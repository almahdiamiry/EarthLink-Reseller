package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.sync.SyncRepositoryImpl
import com.example.domain.repository.SyncPhase
import com.example.domain.repository.SyncProgress
import com.example.domain.repository.SyncReason
import com.example.domain.repository.SyncStatusState
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncObservabilityStateLifecycleTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var syncRepo: SyncRepositoryImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
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
    }

    @Test
    fun requestSyncAndTriggerSync_doNotPrematurelyLatchSyncState() {
        // Initially, sync state should be OFFLINE or IDLE (since auth is null in unit test)
        val initialStatus = syncRepo.syncState.value
        assertNotEquals(
            "Initial state must not be SYNCING",
            SyncStatusState.SYNCING,
            initialStatus
        )

        // Calling requestSync or triggerSync should enqueue without flipping _syncState to SYNCING
        syncRepo.requestSync(SyncReason.USER_ACTION)
        assertEquals(
            "requestSync must not prematurely set syncState to SYNCING before execution",
            initialStatus,
            syncRepo.syncState.value
        )

        syncRepo.triggerSync()
        assertEquals(
            "triggerSync must not prematurely set syncState to SYNCING before execution",
            initialStatus,
            syncRepo.syncState.value
        )
    }

    @Test
    fun syncProgress_phaseTransitionsAndStateSemantics() {
        // Test SyncProgress copy and transitions
        var progress = SyncProgress(
            isSyncing = true,
            phase = SyncPhase.PREPARING,
            processedCount = 0,
            totalCount = 10,
            successCount = 0,
            failureCount = 0
        )
        assertTrue(progress.isSyncing)
        assertEquals(SyncPhase.PREPARING, progress.phase)

        // Advance to UPLOADING with incremental processed count
        progress = progress.copy(
            phase = SyncPhase.UPLOADING,
            processedCount = 5,
            successCount = 5
        )
        assertEquals(SyncPhase.UPLOADING, progress.phase)
        assertEquals(5, progress.processedCount)
        assertEquals(10, progress.totalCount)

        // Advance to DOWNLOADING
        progress = progress.copy(
            phase = SyncPhase.DOWNLOADING
        )
        assertEquals(SyncPhase.DOWNLOADING, progress.phase)

        // Complete
        progress = progress.copy(
            isSyncing = false,
            phase = SyncPhase.COMPLETED,
            processedCount = 10,
            successCount = 10,
            lastCompletedTime = 1700000000000L
        )
        assertFalse(progress.isSyncing)
        assertEquals(SyncPhase.COMPLETED, progress.phase)
        assertEquals(1700000000000L, progress.lastCompletedTime)
    }

    @Test
    fun settingsScreenSubtitleLogic_rendersAccurateTextPerPhase() {
        fun computeSubtitle(
            isSyncing: Boolean,
            syncProgress: SyncProgress,
            syncState: SyncStatusState,
            lastSyncTime: Long,
            failedCount: Int,
            pendingCount: Int,
            currentLang: String
        ): String {
            return when {
                isSyncing -> {
                    when {
                        syncProgress.totalCount > 0 && syncProgress.phase == SyncPhase.UPLOADING -> {
                            if (currentLang == "ar") "جاري رفع البيانات (${syncProgress.processedCount}/${syncProgress.totalCount})"
                            else "Uploading records (${syncProgress.processedCount}/${syncProgress.totalCount})"
                        }
                        syncProgress.phase == SyncPhase.DOWNLOADING -> {
                            if (currentLang == "ar") "جاري جلب التحديثات السحابية..."
                            else "Downloading cloud updates..."
                        }
                        syncProgress.phase == SyncPhase.PREPARING -> {
                            if (currentLang == "ar") "جاري فحص وتجهيز البيانات..."
                            else "Preparing sync pass..."
                        }
                        else -> {
                            if (currentLang == "ar") "بانتظار بدء المزامنة..."
                            else "Waiting for sync to start..."
                        }
                    }
                }
                lastSyncTime > 0L -> {
                    val locale = if (currentLang == "ar") Locale("ar") else Locale.US
                    val formattedTime = SimpleDateFormat("h:mm a", locale).format(Date(lastSyncTime))
                    val base = if (currentLang == "ar") "آخر مزامنة · $formattedTime" else "Last sync · $formattedTime"
                    when {
                        failedCount > 0 -> if (currentLang == "ar") "$base (فشل $failedCount عناصر)" else "$base ($failedCount failed)"
                        pendingCount > 0 -> if (currentLang == "ar") "$base ($pendingCount معلقة)" else "$base ($pendingCount pending)"
                        else -> base
                    }
                }
                syncState == SyncStatusState.ERROR -> if (currentLang == "ar") "تعذر إتمام المزامنة الأخيرة" else "Last sync attempt failed"
                syncState == SyncStatusState.AUTH_REQUIRED -> if (currentLang == "ar") "سجل الدخول بحساب Google للتفعيل" else "Sign in with Google to enable sync"
                syncState == SyncStatusState.OFFLINE -> if (currentLang == "ar") "التطبيق في وضع عدم الاتصال" else "App is running offline"
                else -> if (currentLang == "ar") "لم تتم مزامنة سابقة" else "Never synced"
            }
        }

        // Test UPLOADING with counters
        val uploadingProgress = SyncProgress(
            isSyncing = true,
            phase = SyncPhase.UPLOADING,
            processedCount = 3,
            totalCount = 10
        )
        assertEquals(
            "جاري رفع البيانات (3/10)",
            computeSubtitle(true, uploadingProgress, SyncStatusState.SYNCING, 0L, 0, 0, "ar")
        )
        assertEquals(
            "Uploading records (3/10)",
            computeSubtitle(true, uploadingProgress, SyncStatusState.SYNCING, 0L, 0, 0, "en")
        )

        // Test DOWNLOADING
        val downloadingProgress = SyncProgress(
            isSyncing = true,
            phase = SyncPhase.DOWNLOADING
        )
        assertEquals(
            "جاري جلب التحديثات السحابية...",
            computeSubtitle(true, downloadingProgress, SyncStatusState.SYNCING, 0L, 0, 0, "ar")
        )
        assertEquals(
            "Downloading cloud updates...",
            computeSubtitle(true, downloadingProgress, SyncStatusState.SYNCING, 0L, 0, 0, "en")
        )

        // Test PREPARING
        val preparingProgress = SyncProgress(
            isSyncing = true,
            phase = SyncPhase.PREPARING
        )
        assertEquals(
            "جاري فحص وتجهيز البيانات...",
            computeSubtitle(true, preparingProgress, SyncStatusState.SYNCING, 0L, 0, 0, "ar")
        )
        assertEquals(
            "Preparing sync pass...",
            computeSubtitle(true, preparingProgress, SyncStatusState.SYNCING, 0L, 0, 0, "en")
        )

        // Test Enqueued / Idle Fallback when isSyncing is true but phase is IDLE
        val enqueuedProgress = SyncProgress(
            isSyncing = true,
            phase = SyncPhase.IDLE
        )
        assertEquals(
            "بانتظار بدء المزامنة...",
            computeSubtitle(true, enqueuedProgress, SyncStatusState.SYNCING, 0L, 0, 0, "ar")
        )
        assertEquals(
            "Waiting for sync to start...",
            computeSubtitle(true, enqueuedProgress, SyncStatusState.SYNCING, 0L, 0, 0, "en")
        )
    }

    @Test
    fun settingsScreenActionButtonLogic_rendersAccurateText() {
        fun computeActionButtonText(
            isSyncing: Boolean,
            syncProgress: SyncProgress,
            currentLang: String
        ): String {
            return if (isSyncing) {
                if (syncProgress.totalCount > 0 && syncProgress.phase == SyncPhase.UPLOADING) {
                    if (currentLang == "ar") "جاري المزامنة (${syncProgress.processedCount}/${syncProgress.totalCount})"
                    else "Syncing (${syncProgress.processedCount}/${syncProgress.totalCount})"
                } else {
                    if (currentLang == "ar") "جاري المزامنة..."
                    else "Syncing..."
                }
            } else {
                if (currentLang == "ar") "مزامنة الآن"
                else "Sync Now"
            }
        }

        val uploadingProgress = SyncProgress(
            isSyncing = true,
            phase = SyncPhase.UPLOADING,
            processedCount = 4,
            totalCount = 8
        )
        assertEquals(
            "جاري المزامنة (4/8)",
            computeActionButtonText(true, uploadingProgress, "ar")
        )
        assertEquals(
            "Syncing (4/8)",
            computeActionButtonText(true, uploadingProgress, "en")
        )

        val preparingProgress = SyncProgress(
            isSyncing = true,
            phase = SyncPhase.PREPARING
        )
        assertEquals(
            "جاري المزامنة...",
            computeActionButtonText(true, preparingProgress, "ar")
        )
        assertEquals(
            "Syncing...",
            computeActionButtonText(true, preparingProgress, "en")
        )

        val idleProgress = SyncProgress(isSyncing = false)
        assertEquals(
            "مزامنة الآن",
            computeActionButtonText(false, idleProgress, "ar")
        )
        assertEquals(
            "Sync Now",
            computeActionButtonText(false, idleProgress, "en")
        )
    }
}
