package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.security.PreferenceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED regression test for BUG-SRI-1.
 *
 * Claim: the production SyncRepositoryImpl must wire its auditDao into the
 * RemoteSyncCoordinator it constructs. The coordinator defaults auditDao to null, so with
 * production wiring every quarantine / collision / unknown-type record it emits is silently
 * discarded - while the test suite passes the DAO explicitly and still asserts those rows
 * exist, giving false assurance that observability is covered.
 *
 * This asserts the WIRING, using the production construction path, rather than
 * re-constructing a coordinator by hand the way the existing tests do.
 *
 * Seam / Environment: ROBOLECTRIC tier, in-memory Room SQLite.
 *
 * Independent Oracle: a repository that has an auditDao must hand it to the coordinator it
 * builds. Observability that only exists under test wiring is not observability.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugSri01CoordinatorAuditDaoWiringTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun productionWiring_givesTheCoordinatorAWorkingAuditSink() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = SyncRepositoryImpl(
            context = context,
            appDatabase = db,
            outboxDao = db.syncOutboxDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao()
        )

        val field = RemoteSyncCoordinator::class.java.getDeclaredField("auditDao")
        field.isAccessible = true
        val wired = field.get(repo.remoteSyncCoordinator) as Any?

        assertTrue(
            "SyncRepositoryImpl holds a working auditDao but does not pass it to the " +
                "RemoteSyncCoordinator it constructs, so auditDao is null in the shipping app. " +
                "Every quarantine / identity-collision / unrecognized-type record the " +
                "coordinator emits is then silently discarded.",
            wired != null
        )
    }

    @Test
    fun unknownTransactionType_isRecordedByProductionWiredCoordinator() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = SyncRepositoryImpl(
            context = context,
            appDatabase = db,
            outboxDao = db.syncOutboxDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao()
        )
        val coordinator = repo.remoteSyncCoordinator

        db.localAccountDao().upsert(
            LocalAccount(
                id = "acc_obs",
                sourceExternalId = "ext_obs",
                earthlinkUsername = "obs_user",
                displayName = "Obs",
                createdAt = 1_700_000_000_000L
            )
        )

        // Real data contains 2 such rows (typeRaw = "extend" and "edit"), which normalize to
        // an unrecognized canonical type and must remain observable.
        coordinator.processEvent(
            RemoteEvent.LedgerUpsert(
                entityId = "led_obs",
                remoteVersion = 1000L,
                source = RemoteEventSource.PULL,
                entry = com.example.core.model.LocalLedgerEntry(
                    id = "led_obs",
                    accountId = "acc_obs",
                    typeRaw = "extend",
                    amountIqd = 0.0,
                    debtAfterIqd = 0.0,
                    createdAt = 1_700_000_000_000L
                )
            )
        )

        val audits = db.auditLogDao().getAllSync().map { it.action }
        assertTrue(
            "An unrecognized transaction type arriving over the remote-apply path must be " +
                "recorded so it stays observable. Got audit actions: $audits",
            audits.any { it.contains("UNRECOGNIZED_TRANSACTION_TYPE") }
        )
    }
}
