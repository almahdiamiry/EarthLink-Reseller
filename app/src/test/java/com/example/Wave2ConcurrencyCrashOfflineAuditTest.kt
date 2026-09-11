package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.*
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * WAVE 2 AUDIT: Concurrency + Crash Windows + Offline/Online Conflict Simulation Suite.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - INV-01: Financial Correctness: Balance math is additive and convergent across concurrent and offline mutations.
 *    - INV-03: Atomic & Idempotent Dispatch: Exactly one caller acquires dispatch claim; zero double-dispatch.
 *    - INV-04: Canonical Financial Materialization: Exactly one verified-success materializer.
 *    - INV-05: Uncertain-Operation Recovery: Statements correlate via 4-tuple; un-dispatched operations fail-closed.
 *    - INV-06: Cross-Device Convergence & Versioning: Multi-device mutations converge to identical ledger position.
 *    - INV-11: Idempotence & Replay Safety: Remote event replays produce zero duplicate balance distortions.
 *    - INV-13: Durable Outbox Safety: Local mutation and outbox enqueue commit atomically.
 * 2. Seam / Environment:
 *    - ROBOLECTRIC: In-memory SQLite Room databases, multi-device instances, and simulated Firestore cloud backend.
 * 3. Independent Oracle:
 *    - Explicit primitive arithmetic decomposition: Net Position = (Opening Debt - Opening Advance) + Sum(took) - Sum(gave).
 *    - Deterministic concurrency synchronization via CompletableDeferred latches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class Wave2ConcurrencyCrashOfflineAuditTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var dbFile: File
    private lateinit var mockGateway: EarthlinkGateway

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_wave2_${UUID.randomUUID()}.db")
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        accountRepo = LocalAccountRepositoryImpl(
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            database = db
        )
        mockGateway = mock(EarthlinkGateway::class.java)
    }

    @After
    fun tearDown() {
        db.close()
        if (dbFile.exists()) dbFile.delete()
    }

    // =========================================================================
    // GATE 0 & 2: CONCURRENCY & CLAIM RACES (2 Concurrent Actors)
    // =========================================================================

    @Test
    fun testGate0_concurrentClaimRaces_deterministicMutualExclusion() = runBlocking {
        // Claim: Exactly one concurrent actor can acquire dispatch claim authorization for a transaction.
        // Independent Oracle: Row count updated in SQLite is strictly atomic (1 vs 0).
        val accountId = "acc_claim_race_01"
        accountRepo.saveAccount(LocalAccount(id = accountId, displayName = "Claim Race Account"))

        val txId = "tx_claim_race_${UUID.randomUUID()}"
        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_claim_race",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 0
        )
        ledgerRepo.recordPendingOperation(op)

        val startGate = CompletableDeferred<Unit>()
        val actor1 = async(Dispatchers.IO) {
            startGate.await()
            ledgerRepo.claimDispatchAuthorization(txId)
        }
        val actor2 = async(Dispatchers.IO) {
            startGate.await()
            ledgerRepo.claimDispatchAuthorization(txId)
        }

        // Release both actors simultaneously
        startGate.complete(Unit)

        val res1 = actor1.await()
        val res2 = actor2.await()

        assertTrue("One actor must succeed and the other must fail", (res1 && !res2) || (!res1 && res2))

        val opInDb = db.pendingExternalOperationDao().getByBusinessTransactionId(txId)!!
        assertEquals("DISPATCHING", opInDb.status)
        assertEquals(1, opInDb.dispatchClaimCount)
    }

    @Test
    fun testGate0_doubleInvocation_twoPendingOpsForSameAccount_collapseToOne() = runBlocking {
        // Claim: Two concurrent UI invocations for the same account collapse so only one active operation exists.
        val accountId = "acc_double_invoc_01"
        accountRepo.saveAccount(LocalAccount(id = accountId, displayName = "Double Invoc Account"))

        val tx1 = "tx_invoc_1_${UUID.randomUUID()}"
        val tx2 = "tx_invoc_2_${UUID.randomUUID()}"

        val op1 = PendingExternalOperation(
            businessTransactionId = tx1,
            operationIntentId = "intent_1",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING"
        )
        val op2 = PendingExternalOperation(
            businessTransactionId = tx2,
            operationIntentId = "intent_2",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING"
        )

        val startGate = CompletableDeferred<Unit>()
        val job1 = async(Dispatchers.IO) {
            startGate.await()
            val recorded = ledgerRepo.recordPendingOperation(op1)
            val claimed = ledgerRepo.claimDispatchAuthorization(tx1)
            recorded to claimed
        }
        val job2 = async(Dispatchers.IO) {
            startGate.await()
            val recorded = ledgerRepo.recordPendingOperation(op2)
            val claimed = ledgerRepo.claimDispatchAuthorization(tx2)
            recorded to claimed
        }

        startGate.complete(Unit)
        val result1 = job1.await()
        val result2 = job2.await()

        val claimsGranted = listOf(result1.second, result2.second).count { it }
        assertEquals("Exactly one caller must acquire claim authorization", 1, claimsGranted)

        val allOps = db.pendingExternalOperationDao().getByAccountId(accountId)
        val activeOps = allOps.filter { it.status in listOf("PENDING", "DISPATCHING", "RESOLVING") }
        assertEquals("Exactly one active operation can exist for this account", 1, activeOps.size)
    }

    // =========================================================================
    // GATE 1 & 2: LOCAL WRITE VS REMOTE PULL (Coordinator Serialization)
    // =========================================================================

    @Test
    fun testGate1_localSync_mutuallyExclusiveWithRemoteApply() = runBlocking {
        // Claim: DataOperationCoordinator strictly enforces mutual exclusion between LOCAL_MUTATION (SYNC)
        // and REMOTE_APPLY, preventing interleaving race windows.
        val localEntered = CompletableDeferred<Unit>()
        val releaseLocal = CompletableDeferred<Unit>()
        var remoteApplyBlockedImmediately = false

        val localJob = launch(Dispatchers.IO) {
            DataOperationCoordinator.withOperation(DataOperationMode.SYNC) {
                localEntered.complete(Unit)
                releaseLocal.await()
            }
        }

        localEntered.await()
        assertEquals("Current coordinator mode must be SYNC", DataOperationMode.SYNC, DataOperationCoordinator.currentMode)

        // Remote apply tries to acquire lock while SYNC is held
        val remoteTryResult = DataOperationCoordinator.tryWithOperation(DataOperationMode.REMOTE_APPLY) {
            "SHOULD_NOT_EXECUTE"
        }
        if (remoteTryResult == null) {
            remoteApplyBlockedImmediately = true
        }

        assertTrue("tryWithOperation(REMOTE_APPLY) must return null when SYNC is active", remoteApplyBlockedImmediately)

        // Release local operation
        releaseLocal.complete(Unit)
        localJob.join()

        // Now REMOTE_APPLY can acquire cleanly
        val remoteResult = DataOperationCoordinator.withOperation(DataOperationMode.REMOTE_APPLY) {
            "EXECUTED_CLEANLY"
        }
        assertEquals("EXECUTED_CLEANLY", remoteResult)
    }

    // =========================================================================
    // GATE 3: CRASH-WINDOW ATTACK
    // =========================================================================

    @Test
    fun testGate3_crashWindow_beforeClaim_dispatchCountZero_coldRecoveryFailsClosed() = runBlocking {
        // Point: Crash after intent, but BEFORE claim (dispatchClaimCount = 0).
        // Claim: Cold recovery must detect dispatchClaimCount = 0 and resolve as VERIFIED_FAILURE without redispatching.
        val accountId = "acc_crash_win2"
        accountRepo.saveAccount(LocalAccount(id = accountId, displayName = "Crash Window 2 Account", debtIqd = 0.0))

        val txId = "tx_crash_win2_${UUID.randomUUID()}"
        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_win2",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{\"userId\":\"$accountId\",\"price\":35000.0,\"isWasil\":false}",
            status = "PENDING",
            dispatchClaimCount = 0,
            updatedAt = 1000L // Simulated previous process run timestamp
        )
        db.pendingExternalOperationDao().insert(op)

        // Mock gateway: suppose user exists on ISP
        `when`(mockGateway.searchUsers(anyString(), anyInt(), anyInt())).thenReturn(
            UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 123, userIDLower = accountId)))
        )
        `when`(mockGateway.getUserDetail(123)).thenReturn(
            UserDetail(userIndexLower = 123, userIDLower = accountId, expirationDateLower = "2026-10-15")
        )

        // Execute cold-start recovery with process start time = 2000L
        ledgerRepo.recoverColdStartOrphanedOperations(mockGateway, processStartMs = 2000L)

        val updatedOp = db.pendingExternalOperationDao().getByBusinessTransactionId(txId)!!
        assertEquals("FAILED", updatedOp.status)
        assertTrue("Diagnostic must record failure due to not being dispatched", updatedOp.lastError?.contains("not dispatched") == true)

        // Verify zero debt was materialized
        val accountAfter = db.localAccountDao().getByIdOneShot(accountId)!!
        assertEquals(0.0, accountAfter.debtIqd, 0.001)
        val ledgers = db.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertTrue("Zero ledger entries must exist", ledgers.isEmpty())

        // Verify zero calls were made to execute a refill on gateway (no redispatch)
        verify(mockGateway, never()).refillUserDeposit(anyString(), anyString())
        Unit
    }

    @Test
    fun testGate3_crashWindow_afterGatewaySuccess_coldRecoveryMaterializesDebtOnce() = runBlocking {
        // Point: Crash after external gateway returned 200 OK, but BEFORE resolvePendingOperationVerifiedSuccess ran.
        // Claim: Cold recovery finds statement record on ISP, resolves VERIFIED_SUCCESS, materializes exact debt and outbox once.
        val accountId = "acc_crash_win4"
        accountRepo.saveAccount(LocalAccount(id = accountId, displayName = "Crash Window 4 Account", debtIqd = 0.0, currentPriceIqd = 35000.0))

        val nowMs = System.currentTimeMillis()
        val txId = "charge_crash_win4_${UUID.randomUUID()}"
        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_win4",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{\"userId\":\"$accountId\",\"price\":35000.0,\"isWasil\":false}",
            status = "DISPATCHING",
            dispatchClaimCount = 1,
            updatedAt = nowMs - 10000L,
            createdAt = nowMs - 10000L
        )
        db.pendingExternalOperationDao().insert(op)

        // Mock gateway statement with matching 4-tuple: (userID, operation, amount, timestamp +- 90s)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Baghdad")
        }
        val statementDateStr = sdf.format(java.util.Date(nowMs - 10000L))
        `when`(mockGateway.searchUsers(anyString(), anyInt(), anyInt())).thenReturn(
            UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 456, userIDLower = accountId)))
        )
        `when`(mockGateway.getUserDetail(456)).thenReturn(
            UserDetail(userIndexLower = 456, userIDLower = accountId, expirationDateLower = "2026-10-15")
        )
        `when`(mockGateway.getAccountStatement(anyInt(), anyInt(), anyString())).thenReturn(
            listOf(
                AccountStatementItem(
                    userIDLower = accountId,
                    operation = "RENEW_SUBSCRIBER",
                    depositAmount = 0.0,
                    withdrawalAmount = 35000.0,
                    occurredAt = statementDateStr
                )
            )
        )

        // Cold-start recovery runs
        ledgerRepo.recoverColdStartOrphanedOperations(mockGateway, processStartMs = nowMs)

        val updatedOp = db.pendingExternalOperationDao().getByBusinessTransactionId(txId)!!
        assertEquals("COMPLETED", updatedOp.status)

        // Verify EXACT debt materialized
        val accountAfter = db.localAccountDao().getByIdOneShot(accountId)!!
        assertEquals("Account debt must be exactly 35,000 IQD", 35000.0, accountAfter.debtIqd, 0.001)

        val ledgers = db.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals("Exactly one ledger entry must be materialized", 1, ledgers.size)
        assertEquals(txId, ledgers[0].id)
        assertEquals(35000.0, ledgers[0].amountIqd, 0.001)
        assertEquals("took", ledgers[0].typeRaw)

        // Verify outbox record was enqueued atomically
        val outbox = db.syncOutboxDao().getByEntity(txId, "local_ledger_entries")
        assertEquals("Outbox must contain exactly 1 entry for this ledger row", 1, outbox.size)
        assertEquals("pending", outbox[0].status)
    }

    @Test
    fun testGate3_crashWindow_outboxAtomicWithCommit_rollbackLeavesZeroTraces() = runBlocking {
        // Point: Crash / exception during transaction ensures atomicity across DB tables and outbox.
        val accountId = "acc_atomic_outbox"
        accountRepo.saveAccount(LocalAccount(id = accountId, displayName = "Atomic Outbox Account", debtIqd = 0.0))

        val initialLedgerCount = db.localLedgerEntryDao().getTotalCount()
        val initialOutboxCount = db.syncOutboxDao().getAllUnsyncedCount()

        try {
            db.withTransaction {
                val entry = LocalLedgerEntry(
                    id = "tx_will_rollback",
                    accountId = accountId,
                    typeRaw = "took",
                    amountIqd = 25000.0,
                    debtAfterIqd = 25000.0
                )
                db.localLedgerEntryDao().insert(entry)
                OutboxManager.upsertWithOutbox(db.syncOutboxDao(), "local_ledger_entries", entry.id, "{}")
                // Force deliberate crash / exception
                throw IllegalStateException("SIMULATED_CRASH_BEFORE_COMMIT")
            }
        } catch (_: IllegalStateException) {
            // Expected
        }

        assertEquals("No ledger entry must survive rolled back transaction", initialLedgerCount, db.localLedgerEntryDao().getTotalCount())
        assertEquals("No outbox record must survive rolled back transaction", initialOutboxCount, db.syncOutboxDao().getAllUnsyncedCount())
    }

    // =========================================================================
    // GATE 4: METAMORPHIC OFFLINE / ONLINE CONFLICT SIMULATION
    // =========================================================================

    @Test
    fun testGate4_metamorphic_twoDevices_offlineDebtAndPayment_convergeToZeroNet() = runBlocking {
        // Scenario: Two devices modifying the same subscriber offline.
        // Device A adds Debt 35,000 IQD (took).
        // Device B adds Payment 35,000 IQD (gave).
        // Both reconnect, push to cloud, pull from cloud.
        // Independent Oracle:
        // Net Position = (Opening Debt 0 - Opening Advance 0) + 35,000 - 35,000 = 0.
        // Both devices must converge to: Debt = 0, Advance = 0, exactly 2 ledger entries.

        val cloud = SimulatedFirestoreCloudBackend()
        val devA = SimulatedIsolatedDevice("DevA", context, cloud)
        val devB = SimulatedIsolatedDevice("DevB", context, cloud)

        try {
            val accountId = "acc_shared_converge_01"
            val baseAccount = LocalAccount(
                id = accountId,
                displayName = "Shared Subscriber",
                earthlinkUsername = "shared_user",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 35000.0,
                isHistoryOnlySubscriber = false,
                updatedAt = 1000L
            )
            devA.accountRepository.saveAccount(baseAccount)
            devB.accountRepository.saveAccount(baseAccount)

            // Seed cloud baseline
            cloud.recordDirectAccount(baseAccount, 1000L)

            // Device A: Offline mutation -> took 35,000
            val debtEntryA = devA.ledgerRepository.addDebt(accountId, 35000.0, "Refill on Dev A", "tx_debt_devA")
            assertEquals("tx_debt_devA", debtEntryA.id)
            val accAAfterLocal = devA.accountDao.getByIdOneShot(accountId)!!
            assertEquals(35000.0, accAAfterLocal.debtIqd, 0.001)
            assertEquals(0.0, accAAfterLocal.advanceIqd, 0.001)

            // Device B: Offline mutation -> gave 35,000
            val payEntryB = devB.ledgerRepository.addPayment(accountId, 35000.0, "Cash Payment on Dev B", "tx_pay_devB")
            assertEquals("tx_pay_devB", payEntryB.id)
            val accBAfterLocal = devB.accountDao.getByIdOneShot(accountId)!!
            assertEquals(0.0, accBAfterLocal.debtIqd, 0.001)
            assertEquals(35000.0, accBAfterLocal.advanceIqd, 0.001)

            // Both devices reconnect and push to cloud
            val pushedA = devA.pushToCloud()
            val pushedB = devB.pushToCloud()
            assertTrue(pushedA > 0)
            assertTrue(pushedB > 0)

            // Device A pulls Device B's changes from cloud
            val pulledA = devA.pullFromCloud()
            assertTrue(pulledA > 0)

            // Device B pulls Device A's changes from cloud
            val pulledB = devB.pullFromCloud()
            assertTrue(pulledB > 0)

            // Assert Convergent State on Device A
            val finalAccA = devA.accountDao.getByIdOneShot(accountId)!!
            val finalLedgersA = devA.ledgerDao.getByAccountIdOneShot(accountId)
            assertEquals("Device A must have 2 ledger entries", 2, finalLedgersA.size)
            assertEquals("Device A debt must converge to 0", 0.0, finalAccA.debtIqd, 0.001)
            assertEquals("Device A advance must converge to 0", 0.0, finalAccA.advanceIqd, 0.001)

            // Assert Convergent State on Device B
            val finalAccB = devB.accountDao.getByIdOneShot(accountId)!!
            val finalLedgersB = devB.ledgerDao.getByAccountIdOneShot(accountId)
            assertEquals("Device B must have 2 ledger entries", 2, finalLedgersB.size)
            assertEquals("Device B debt must converge to 0", 0.0, finalAccB.debtIqd, 0.001)
            assertEquals("Device B advance must converge to 0", 0.0, finalAccB.advanceIqd, 0.001)

            // Check exact mathematical set equality between Device A and Device B
            val idsA = finalLedgersA.map { it.id }.toSet()
            val idsB = finalLedgersB.map { it.id }.toSet()
            assertEquals("Ledger IDs on both devices must match perfectly", idsA, idsB)
            assertTrue(idsA.contains("tx_debt_devA"))
            assertTrue(idsA.contains("tx_pay_devB"))
        } finally {
            devA.close()
            devB.close()
        }
    }

    @Test
    fun testGate4_metamorphic_threeDevices_complexTopology_convergesDeterministically() = runBlocking {
        // Topology: 3 Devices (Dev A, Dev B, Dev C)
        // Opening balance: Debt = 0, Advance = 0.
        // Dev A: Adds Debt 50,000 (took).
        // Dev B: Adds Payment 20,000 (gave).
        // Dev C: Adds Payment 30,000 (gave).
        // Independent Oracle:
        // Net Position = (0 - 0) + 50,000 - 20,000 - 30,000 = 0.
        // Final Debt = 0.0, Final Advance = 0.0 across ALL 3 devices.

        val cloud = SimulatedFirestoreCloudBackend()
        val devA = SimulatedIsolatedDevice("DevA", context, cloud)
        val devB = SimulatedIsolatedDevice("DevB", context, cloud)
        val devC = SimulatedIsolatedDevice("DevC", context, cloud)

        try {
            val accountId = "acc_3dev_converge"
            val baseAccount = LocalAccount(
                id = accountId,
                displayName = "3-Device Subscriber",
                earthlinkUsername = "user_3dev",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 50000.0,
                updatedAt = 1000L
            )
            devA.accountRepository.saveAccount(baseAccount)
            devB.accountRepository.saveAccount(baseAccount)
            devC.accountRepository.saveAccount(baseAccount)
            cloud.recordDirectAccount(baseAccount, 1000L)

            // Mutations
            devA.ledgerRepository.addDebt(accountId, 50000.0, "Renewal on Dev A", "tx_3dev_A")
            devB.ledgerRepository.addPayment(accountId, 20000.0, "Partial Pay on Dev B", "tx_3dev_B")
            devC.ledgerRepository.addPayment(accountId, 30000.0, "Remaining Pay on Dev C", "tx_3dev_C")

            // Push in staggered order: B first, then A, then C
            devB.pushToCloud()
            devA.pushToCloud()
            devC.pushToCloud()

            // Pull across all devices
            devA.pullFromCloud()
            devB.pullFromCloud()
            devC.pullFromCloud()

            // Second pull pass to ensure full transitive closure
            devA.pullFromCloud()
            devB.pullFromCloud()
            devC.pullFromCloud()

            for (dev in listOf(devA, devB, devC)) {
                val acc = dev.accountDao.getByIdOneShot(accountId)!!
                val ledgers = dev.ledgerDao.getByAccountIdOneShot(accountId)
                assertEquals("${dev.name} must have all 3 transactions", 3, ledgers.size)
                assertEquals("${dev.name} debt must be 0", 0.0, acc.debtIqd, 0.001)
                assertEquals("${dev.name} advance must be 0", 0.0, acc.advanceIqd, 0.001)
            }
        } finally {
            devA.close()
            devB.close()
            devC.close()
        }
    }

    @Test
    fun testGate4_metamorphic_duplicateDelivery_zeroBalanceDistortion() = runBlocking {
        // Scenario: Metamorphic replay count.
        // Remote event is delivered 5 times in succession.
        // Independent Oracle: Idempotency eliminates repeats; exactly 1 ledger entry, zero duplicate balance math.

        val cloud = SimulatedFirestoreCloudBackend()
        val dev = SimulatedIsolatedDevice("DevReplay", context, cloud)

        try {
            val accountId = "acc_replay_test"
            val account = LocalAccount(id = accountId, displayName = "Replay Test", debtIqd = 0.0, advanceIqd = 0.0)
            dev.accountRepository.saveAccount(account)

            val remoteLedger = LocalLedgerEntry(
                id = "tx_remote_replay_1",
                accountId = accountId,
                typeRaw = "took",
                amountIqd = 25000.0,
                debtAfterIqd = 25000.0,
                occurredAt = 5000L
            )
            val event = RemoteEvent.LedgerUpsert(
                entityId = remoteLedger.id,
                remoteVersion = 5000L,
                source = RemoteEventSource.REALTIME,
                entry = remoteLedger
            )

            // Deliver 5 times
            for (i in 1..5) {
                val result = dev.coordinator.processEvent(event)
                if (i == 1) {
                    assertEquals("First delivery must be APPLIED", EventSyncResult.APPLIED, result)
                } else {
                    assertEquals("Subsequent deliveries must be SKIPPED_DUPLICATE", EventSyncResult.SKIPPED_DUPLICATE, result)
                }
            }

            val finalLedgers = dev.ledgerDao.getByAccountIdOneShot(accountId)
            assertEquals("Exactly 1 ledger entry must exist despite 5 deliveries", 1, finalLedgers.size)
            val finalAcc = dev.accountDao.getByIdOneShot(accountId)!!
            assertEquals("Debt must be exactly 25,000 IQD", 25000.0, finalAcc.debtIqd, 0.001)
        } finally {
            dev.close()
        }
    }

    // =========================================================================
    // GATE 0 & 1: ACCOUNT STATE TRANSITION VS FINANCIAL OPERATION AUDIT
    // =========================================================================

    @Test
    fun testGate0_accountStateTransition_historyOnlyDuringInflight_verifiesBehavior() = runBlocking {
        // Investigation hypothesis: If an account is transitioned to history-only while an operation is in-flight,
        // does resolvePendingOperationVerifiedSuccess find the account and record the charge, or does it throw?
        val accountId = "acc_hist_audit_01"
        val username = "user_hist_audit_01"
        val initialAccount = LocalAccount(
            id = accountId,
            earthlinkUsername = username,
            displayName = "History Audit Subscriber",
            debtIqd = 0.0,
            isHistoryOnlySubscriber = false
        )
        accountRepo.saveAccount(initialAccount)

        val txId = "charge_hist_${UUID.randomUUID()}"
        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_hist",
            accountId = username, // ViewModel records accountId as username
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{\"userId\":\"$username\",\"price\":35000.0,\"isWasil\":false}",
            status = "DISPATCHING",
            dispatchClaimCount = 1
        )
        db.pendingExternalOperationDao().insert(op)

        // During in-flight dispatch, account transitions to history-only (e.g. deletion or ISP reconciliation)
        accountRepo.deleteAccount(accountId)
        val postDelete = db.localAccountDao().getByIdOneShot(accountId)!!
        assertTrue("Account is now history-only", postDelete.isHistoryOnlySubscriber)

        // Now gateway success occurs: resolvePendingOperationVerifiedSuccess is called
        try {
            val ledgerEntry = ledgerRepo.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED RENEW]")
            assertNotNull("Ledger entry must be created", ledgerEntry)
            assertEquals(35000.0, ledgerEntry!!.amountIqd, 0.001)
        } catch (e: IllegalStateException) {
            // If it throws MISSING_LOCAL_FINANCIAL_TARGET, document the exact failure behavior
            assertTrue("Records missing local financial target exception", e.message?.contains("MISSING_LOCAL_FINANCIAL_TARGET") == true)
        }
        Unit
    }
}
