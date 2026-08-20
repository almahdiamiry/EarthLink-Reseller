package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.ledger.BalanceCalculator
import com.example.core.ledger.TransactionTypeNormalizer
import com.example.core.model.*
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import com.example.data.repository.recalculateAccountHistoryInternal
import com.example.data.repository.RecalcOrigin
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Workstream 11 Certification Test: Unknown Transaction Type Observability & Financial Neutrality.
 *
 * Verifies:
 * 1. State A (null/blank typeRaw): Treated as neutral note, no balance distortion, no warning audit log.
 * 2. State B (recognized neutral type e.g. "NOTE"): Treated as neutral note, no balance distortion, no warning audit log.
 * 3. State C (genuinely unrecognized nonblank type e.g. "UNKNOWN_FEE_123"):
 *    - Financially non-authoritative: does not alter account debt/advance/loan balances.
 *    - Observability: records an AuditLog entry with UNRECOGNIZED_TRANSACTION_TYPE containing entityId, raw type, accountId.
 * 4. Production caller verification (RemoteSyncCoordinator, recalculateAccountHistoryInternal).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Workstream11UnknownTypeObservabilityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        coordinator = RemoteSyncCoordinator(
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            outboxDao = db.syncOutboxDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao(),
            appDatabase = db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testTransactionTypeNormalizer_ThreeInputStates() {
        // State A: Null / Blank
        assertTrue("Null type must be recognized as default neutral note", TransactionTypeNormalizer.isRecognizedType(null))
        assertTrue("Blank type must be recognized as default neutral note", TransactionTypeNormalizer.isRecognizedType("   "))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType(null))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("  "))

        // State B: Recognized neutral / recognized active
        assertTrue("NOTE must be recognized", TransactionTypeNormalizer.isRecognizedType("NOTE"))
        assertTrue("took must be recognized", TransactionTypeNormalizer.isRecognizedType("TOOK"))
        assertTrue("gave must be recognized", TransactionTypeNormalizer.isRecognizedType("GAVE"))
        assertTrue("renewal must be recognized", TransactionTypeNormalizer.isRecognizedType("RENEWAL"))

        // State C: Genuinely unrecognized / malformed nonblank
        assertFalse("UNKNOWN_CUSTOM must be unrecognized", TransactionTypeNormalizer.isRecognizedType("UNKNOWN_CUSTOM"))
        assertFalse("CORRUPT_TYPE_999 must be unrecognized", TransactionTypeNormalizer.isRecognizedType("CORRUPT_TYPE_999"))
    }

    @Test
    fun testBalanceCalculator_ReconstructCurrentPosition_StateHandlingAndObservability() {
        var unrecognizedLoggedTx: LocalLedgerEntry? = null
        var unrecognizedLoggedType: String? = null

        val txStateA = LocalLedgerEntry(id = "tx_a", accountId = "acc_1", typeRaw = "", amountIqd = 50000.0, debtAfterIqd = 0.0, occurredAt = 100L)
        val txStateB = LocalLedgerEntry(id = "tx_b", accountId = "acc_1", typeRaw = "NOTE", amountIqd = 50000.0, debtAfterIqd = 0.0, occurredAt = 200L)
        val txStateC = LocalLedgerEntry(id = "tx_c", accountId = "acc_1", typeRaw = "MALFORMED_SURCHARGE", amountIqd = 50000.0, debtAfterIqd = 0.0, occurredAt = 300L)
        val txValid = LocalLedgerEntry(id = "tx_valid", accountId = "acc_1", typeRaw = "TOOK", amountIqd = 25000.0, debtAfterIqd = 0.0, occurredAt = 400L)

        val (balances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 0.0,
            openingAdvance = 0.0,
            openingLoan = 0.0,
            transactions = listOf(txStateA, txStateB, txStateC, txValid),
            onUnrecognizedType = { tx, rawType ->
                unrecognizedLoggedTx = tx
                unrecognizedLoggedType = rawType
            }
        )

        // Financial non-authoritativeness: Only tx_valid (25,000) adds to debt. States A, B, and C add 0.0 debt.
        assertEquals(25000.0, balances.debtIqd, 0.001)

        // Observability: Only txStateC triggered onUnrecognizedType
        assertNotNull("Unrecognized callback must fire for state C", unrecognizedLoggedTx)
        assertEquals("tx_c", unrecognizedLoggedTx?.id)
        assertEquals("MALFORMED_SURCHARGE", unrecognizedLoggedType)
    }

    @Test
    fun testRemoteSyncCoordinator_UnrecognizedType_AuditedAndNonAuthoritative() = runBlocking {
        val accountId = "acc_remote_unrec_01"
        val account = LocalAccount(
            id = accountId,
            displayName = "Unrecognized Type Subscriber",
            earthlinkUsername = "unrec_sub",
            debtIqd = 0.0,
            updatedAt = 100L
        )
        db.localAccountDao().upsert(account)

        // Remote ledger entry with unrecognized type arrives
        val unrecLedger = LocalLedgerEntry(
            id = "led_remote_unrec_01",
            accountId = accountId,
            typeRaw = "SPECIAL_SYSTEM_PENALTY",
            amountIqd = 75000.0,
            debtAfterIqd = 0.0,
            occurredAt = 200L
        )

        val event = RemoteEvent.LedgerUpsert(
            entityId = unrecLedger.id,
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME,
            entry = unrecLedger
        )

        val syncResult = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, syncResult)

        // 1. Debt balance remains 0.0 (financially non-authoritative)
        val updatedAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertEquals("Debt must not change for unrecognized transaction type", 0.0, updatedAcc?.debtIqd ?: -1.0, 0.001)

        // 2. Audit log entry recorded with entityId, raw type, accountId
        val auditLogs = db.auditLogDao().getAllSync()
        val unrecAudit = auditLogs.find { it.action == "UNRECOGNIZED_TRANSACTION_TYPE" && it.entityId == unrecLedger.id }
        assertNotNull("AuditLog entry must be recorded for unrecognized transaction type", unrecAudit)
        assertTrue("Audit summary must contain raw type", unrecAudit!!.summary.contains("SPECIAL_SYSTEM_PENALTY"))
        assertTrue("Audit summary must contain accountId", unrecAudit.summary.contains(accountId))
    }

    @Test
    fun testRecalculateAccountHistoryInternal_AuditsUnrecognizedType() = runBlocking {
        val accountId = "acc_recalc_unrec_01"
        val account = LocalAccount(
            id = accountId,
            displayName = "Recalc Subscriber",
            earthlinkUsername = "recalc_sub",
            debtIqd = 0.0,
            updatedAt = 100L
        )
        db.localAccountDao().upsert(account)

        val unrecLedger = LocalLedgerEntry(
            id = "led_recalc_unrec_01",
            accountId = accountId,
            typeRaw = "UNKNOWN_CREDIT_ADJUSTMENT",
            amountIqd = 40000.0,
            debtAfterIqd = 0.0,
            occurredAt = 150L
        )
        db.localLedgerEntryDao().insert(unrecLedger)

        val moshi = Moshi.Builder().build()
        val ledgerAdapter = moshi.adapter(LocalLedgerEntry::class.java)

        recalculateAccountHistoryInternal(
            accountId = accountId,
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            outboxDao = db.syncOutboxDao(),
            ledgerAdapter = ledgerAdapter,
            origin = RecalcOrigin.LOCAL_MUTATION,
            auditDao = db.auditLogDao()
        )

        // 1. Balance remains 0.0
        val updatedAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertEquals(0.0, updatedAcc?.debtIqd ?: -1.0, 0.001)

        // 2. AuditLog recorded
        val auditLogs = db.auditLogDao().getAllSync()
        val unrecAudit = auditLogs.find { it.action == "UNRECOGNIZED_TRANSACTION_TYPE" && it.entityId == unrecLedger.id }
        assertNotNull("AuditLog entry must be recorded for unrecognized transaction type during recalculation", unrecAudit)
        assertTrue("Audit summary must contain raw type", unrecAudit!!.summary.contains("UNKNOWN_CREDIT_ADJUSTMENT"))
        assertTrue("Audit summary must contain accountId", unrecAudit.summary.contains(accountId))
    }
}
