package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.SyncOutbox
import com.example.core.sync.*
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 1 Two-Device Convergence Integration Proof Test Suite (P1-G2-REQ-07 / INV-01 / INV-06 / INV-11 / INV-13).
 *
 * Simulates two distinct physical client devices (Device A and Device B) with independent local SQLite
 * Room databases communicating through a shared cloud Firestore backend.
 *
 * Verifies:
 * 1. Scenario 1 (Independent offline creation & cross-sync):
 *    - Device A creates Account 1 + Payment tx-A1 offline.
 *    - Device B creates Account 2 + Debt tx-B1 offline.
 *    - Cross-sync converges both devices to identical ledger sets and matching balances without duplicates or lost updates.
 *
 * 2. Scenario 2 (Concurrent mutations on same account):
 *    - Device A adds Debt (+50,000 IQD) to Account X offline.
 *    - Device B adds Payment (+30,000 IQD) to Account X offline.
 *    - Cross-sync converges Account X balance on both devices to exactly 20,000 IQD debt with zero lost updates.
 *
 * 3. Scenario 3 (Tombstone cross-device deletion & balance adjustment):
 *    - Deleting a transaction on Device A propagates a tombstone to Firestore.
 *    - Device B syncs and removes the transaction locally, reverting its account balance correctly.
 *    - Stale replays are rejected by tombstone protection.
 *
 * 4. Scenario 4 (Remote version progression & metadata integrity):
 *    - Server version timestamps advance monotonically across operations.
 *    - Both devices record authoritative remote_version metadata matching server versions.
 *    - Local Outbox items are marked succeeded upon sync.
 *
 * 5. Scenario 5 (Section 4.13 Baseline T1 + Offline T2/T3 Convergence):
 *    - Pre-existing baseline T1 on Cloud and Devices A/B.
 *    - Device A adds T2 offline, Device B adds T3 offline.
 *    - Cross-sync converges Cloud, Device A, and Device B to exact set {T1, T2, T3} with zero duplicate T1 and equal balance derivation.
 *
 * 6. Scenario 6 (Idempotent Convergence & Zero-Write Invariant):
 *    - Subsequent sync passes on already-converged state generate zero cloud mutations and zero local database changes (INV-10).
 *
 * 7. Scenario 7 (Outbox Durability Under Network Failure & Retry):
 *    - Failed push leaves Outbox obligation durable and retryable without dead-letter drops (INV-13).
 *    - Reconnect retries push and converges multi-device state seamlessly.
 *
 * 8. Scenario 8 (Parent Account Auto-Creation via Pre-Fetched Hierarchy):
 *    - Inbound ledger entry for a new account auto-creates the parent account without orphan failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1TwoDeviceConvergenceTest {

    private lateinit var context: Context
    private lateinit var cloudBackend: SimulatedCloudBackend
    private lateinit var deviceA: SimulatedDevice
    private lateinit var deviceB: SimulatedDevice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true

        cloudBackend = SimulatedCloudBackend()
        deviceA = SimulatedDevice("Device_A", context, cloudBackend)
        deviceB = SimulatedDevice("Device_B", context, cloudBackend)
    }

    @After
    fun tearDown() = runBlocking {
        deviceA.close()
        deviceB.close()
    }

    // =============================================================================================
    // Scenario 1: Independent Offline Creation and Cross-Device Synchronization
    // =============================================================================================

    @Test
    fun testScenario1_independentOfflineCreation_crossDeviceSync_convergesDeterministically() = runBlocking {
        // Device A creates Account 1 + Payment tx-A1 offline
        val acc1 = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_1",
                displayName = "Subscriber One",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 35000.0,
                updatedAt = System.currentTimeMillis()
            )
        )
        val txA1 = deviceA.ledgerRepository.addPayment(
            accountId = acc1.id,
            amount = 25000.0,
            note = "Cash payment on Device A",
            idempotencyKey = "tx-A1"
        )
        assertEquals("tx-A1", txA1.id)

        // Device B creates Account 2 + Debt tx-B1 offline
        val acc2 = deviceB.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_2",
                displayName = "Subscriber Two",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 40000.0,
                updatedAt = System.currentTimeMillis()
            )
        )
        val txB1 = deviceB.ledgerRepository.addDebt(
            accountId = acc2.id,
            amount = 40000.0,
            note = "Monthly fee on Device B",
            idempotencyKey = "tx-B1"
        )
        assertEquals("tx-B1", txB1.id)

        // Verify initial isolated offline states
        assertEquals(1, deviceA.accountDao.getAllOneShot().size)
        assertEquals(1, deviceA.ledgerDao.getAllOneShot().size)
        assertEquals(1, deviceB.accountDao.getAllOneShot().size)
        assertEquals(1, deviceB.ledgerDao.getAllOneShot().size)

        // Device A pushes its outbox to Cloud
        val pushedFromA = deviceA.pushToCloud()
        assertTrue("Device A must push its outbox records", pushedFromA > 0)

        // Device B pulls from Cloud, then pushes its outbox to Cloud
        val pulledToB = deviceB.pullFromCloud()
        assertTrue("Device B must pull Device A records from Cloud", pulledToB > 0)
        val pushedFromB = deviceB.pushToCloud()
        assertTrue("Device B must push its outbox records", pushedFromB > 0)

        // Device A pulls from Cloud
        val pulledToA = deviceA.pullFromCloud()
        assertTrue("Device A must pull Device B records from Cloud", pulledToA > 0)

        // Verify Full Convergence across both devices
        val accountsOnA = deviceA.accountDao.getAllOneShot().sortedBy { it.id }
        val accountsOnB = deviceB.accountDao.getAllOneShot().sortedBy { it.id }
        val ledgersOnA = deviceA.ledgerDao.getAllOneShot().sortedBy { it.id }
        val ledgersOnB = deviceB.ledgerDao.getAllOneShot().sortedBy { it.id }

        // 1. Both devices have both accounts
        assertEquals(2, accountsOnA.size)
        assertEquals(2, accountsOnB.size)
        assertEquals(listOf("acc_1", "acc_2"), accountsOnA.map { it.id })
        assertEquals(listOf("acc_1", "acc_2"), accountsOnB.map { it.id })

        // 2. Both devices have both ledger entries
        assertEquals(2, ledgersOnA.size)
        assertEquals(2, ledgersOnB.size)
        assertEquals(listOf("tx-A1", "tx-B1"), ledgersOnA.map { it.id })
        assertEquals(listOf("tx-A1", "tx-B1"), ledgersOnB.map { it.id })

        // 3. Exact matching balances on both devices
        val acc1OnA = deviceA.accountDao.getByIdOneShot("acc_1")!!
        val acc1OnB = deviceB.accountDao.getByIdOneShot("acc_1")!!
        assertEquals(0.0, acc1OnA.debtIqd, 0.001)
        assertEquals(25000.0, acc1OnA.advanceIqd, 0.001)
        assertEquals(acc1OnA.debtIqd, acc1OnB.debtIqd, 0.001)
        assertEquals(acc1OnA.advanceIqd, acc1OnB.advanceIqd, 0.001)

        val acc2OnA = deviceA.accountDao.getByIdOneShot("acc_2")!!
        val acc2OnB = deviceB.accountDao.getByIdOneShot("acc_2")!!
        assertEquals(40000.0, acc2OnA.debtIqd, 0.001)
        assertEquals(0.0, acc2OnA.advanceIqd, 0.001)
        assertEquals(acc2OnA.debtIqd, acc2OnB.debtIqd, 0.001)
        assertEquals(acc2OnA.advanceIqd, acc2OnB.advanceIqd, 0.001)

        // 4. Outboxes are fully drained (0 pending)
        assertEquals(0, deviceA.outboxDao.getPending().size)
        assertEquals(0, deviceB.outboxDao.getPending().size)
    }

    // =============================================================================================
    // Scenario 2: Concurrent Operations on the Same Account
    // =============================================================================================

    @Test
    fun testScenario2_concurrentOperationsOnSameAccount_convergesToExactDerivedBalance() = runBlocking {
        // Initial state: Shared Account X exists on Cloud and both devices with balance = 0 IQD
        val accountX = LocalAccount(
            id = "acc_shared_X",
            displayName = "Shared Subscriber X",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            currentPriceIqd = 45000.0,
            updatedAt = 1700000000000L
        )
        cloudBackend.recordDirectAccount(accountX, 1700000000000L)
        deviceA.pullFromCloud()
        deviceB.pullFromCloud()

        assertNotNull(deviceA.accountDao.getByIdOneShot("acc_shared_X"))
        assertNotNull(deviceB.accountDao.getByIdOneShot("acc_shared_X"))

        // Device A offline: adds Debt (+50,000 IQD)
        val txADebt = deviceA.ledgerRepository.addDebt(
            accountId = "acc_shared_X",
            amount = 50000.0,
            note = "Renewal debt from Device A",
            idempotencyKey = "tx-A_debt"
        )
        assertEquals("tx-A_debt", txADebt.id)
        val accOnAAfterDebt = deviceA.accountDao.getByIdOneShot("acc_shared_X")!!
        assertEquals(50000.0, accOnAAfterDebt.debtIqd, 0.001)
        assertEquals(0.0, accOnAAfterDebt.advanceIqd, 0.001)

        // Device B offline: adds Payment (+30,000 IQD)
        val txBPay = deviceB.ledgerRepository.addPayment(
            accountId = "acc_shared_X",
            amount = 30000.0,
            note = "Cash payment from Device B",
            idempotencyKey = "tx-B_pay"
        )
        assertEquals("tx-B_pay", txBPay.id)
        val accOnBAfterPay = deviceB.accountDao.getByIdOneShot("acc_shared_X")!!
        assertEquals(0.0, accOnBAfterPay.debtIqd, 0.001)
        assertEquals(30000.0, accOnBAfterPay.advanceIqd, 0.001)

        // Both devices push mutations to Cloud
        deviceA.pushToCloud()
        deviceB.pushToCloud()

        // Both devices pull remote changes
        deviceA.pullFromCloud()
        deviceB.pullFromCloud()

        // Verification: Both devices contain both transactions and Account X balance converges to exactly 20,000 IQD debt
        val ledgersA = deviceA.ledgerDao.getByAccountIdOneShot("acc_shared_X").sortedBy { it.id }
        val ledgersB = deviceB.ledgerDao.getByAccountIdOneShot("acc_shared_X").sortedBy { it.id }

        assertEquals(2, ledgersA.size)
        assertEquals(2, ledgersB.size)
        assertEquals(listOf("tx-A_debt", "tx-B_pay"), ledgersA.map { it.id })
        assertEquals(listOf("tx-A_debt", "tx-B_pay"), ledgersB.map { it.id })

        val finalAccA = deviceA.accountDao.getByIdOneShot("acc_shared_X")!!
        val finalAccB = deviceB.accountDao.getByIdOneShot("acc_shared_X")!!

        // 50,000 debt - 30,000 payment = exactly 20,000 debt on both devices
        assertEquals(20000.0, finalAccA.debtIqd, 0.001)
        assertEquals(0.0, finalAccA.advanceIqd, 0.001)
        assertEquals(20000.0, finalAccB.debtIqd, 0.001)
        assertEquals(0.0, finalAccB.advanceIqd, 0.001)
    }

    // =============================================================================================
    // Scenario 3: Tombstone Cross-Device Deletion and Balance Adjustment
    // =============================================================================================

    @Test
    fun testScenario3_tombstoneCrossDeviceDeletion_revertsBalanceAcrossDevices() = runBlocking {
        // Setup initial account on Device A
        val account = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_tombstone_test",
                displayName = "Tombstone Test Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 40000.0,
                updatedAt = System.currentTimeMillis()
            )
        )
        // Add initial Debt of 40,000 IQD (tx-debt_init)
        val initialDebt = deviceA.ledgerRepository.addDebt(
            accountId = account.id,
            amount = 40000.0,
            note = "Initial fee",
            idempotencyKey = "tx-debt_init"
        )
        assertEquals(40000.0, initialDebt.amountIqd, 0.001)

        // Add payment of 40,000 IQD (tx-pay_del) -> debt becomes 0 IQD
        val payment = deviceA.ledgerRepository.addPayment(
            accountId = account.id,
            amount = 40000.0,
            note = "Payment to be deleted",
            idempotencyKey = "tx-pay_del"
        )
        assertEquals(40000.0, payment.amountIqd, 0.001)

        // Sync initial state from Device A to Cloud, and pull to Device B
        deviceA.pushToCloud()
        deviceB.pullFromCloud()

        // Verify both devices have debt = 0 IQD and both transactions exist
        assertEquals(0.0, deviceA.accountDao.getByIdOneShot("acc_tombstone_test")!!.debtIqd, 0.001)
        assertEquals(0.0, deviceB.accountDao.getByIdOneShot("acc_tombstone_test")!!.debtIqd, 0.001)
        assertNotNull(deviceA.ledgerDao.getByIdOneShot("tx-debt_init"))
        assertNotNull(deviceA.ledgerDao.getByIdOneShot("tx-pay_del"))
        assertNotNull(deviceB.ledgerDao.getByIdOneShot("tx-debt_init"))
        assertNotNull(deviceB.ledgerDao.getByIdOneShot("tx-pay_del"))

        // Device A deletes the payment transaction
        deviceA.ledgerRepository.deleteTransaction("tx-pay_del")

        // Device A balance immediately reverts to 40,000 IQD debt
        assertNull(deviceA.ledgerDao.getByIdOneShot("tx-pay_del"))
        assertEquals(40000.0, deviceA.accountDao.getByIdOneShot("acc_tombstone_test")!!.debtIqd, 0.001)

        // Device A pushes tombstone to Cloud
        val pushedTombstone = deviceA.pushToCloud()
        assertTrue("Device A must push deletion tombstone", pushedTombstone > 0)

        // Device B pulls tombstone from Cloud
        val pulledTombstone = deviceB.pullFromCloud()
        assertTrue("Device B must pull deletion tombstone", pulledTombstone > 0)

        // Verify Device B local ledger entry is deleted and account debt reverts to 40,000 IQD
        assertNull("tx-pay_del must be deleted on Device B", deviceB.ledgerDao.getByIdOneShot("tx-pay_del"))
        assertNotNull("tx-debt_init must remain intact on Device B", deviceB.ledgerDao.getByIdOneShot("tx-debt_init"))
        val accBAfterTombstone = deviceB.accountDao.getByIdOneShot("acc_tombstone_test")!!
        assertEquals(40000.0, accBAfterTombstone.debtIqd, 0.001)
        assertEquals(0.0, accBAfterTombstone.advanceIqd, 0.001)

        // Verify tombstone metadata is recorded on Device B
        val tombstoneOnB = deviceB.metadataDao.get("tombstone:ledger:tx-pay_del")
        assertNotNull("Tombstone metadata must exist on Device B", tombstoneOnB)
        assertTrue(tombstoneOnB!!.toLong() > 0L)

        // Adversarial check: stale re-push of deleted transaction with older timestamp is ignored
        val staleEvent = RemoteEvent.LedgerUpsert(
            entityId = "tx-pay_del",
            remoteVersion = tombstoneOnB.toLong() - 500L, // Older than tombstone
            source = RemoteEventSource.PULL,
            entry = payment,
            syncMutationId = "stale_resurrect_mutation"
        )
        val staleResult = deviceB.coordinator.processEvent(staleEvent)
        assertEquals(EventSyncResult.SKIPPED_DUPLICATE, staleResult)
        assertNull("Stale event must not resurrect deleted ledger entry", deviceB.ledgerDao.getByIdOneShot("tx-pay_del"))
    }

    // =============================================================================================
    // Scenario 4: Remote Version Progression & Metadata Integrity
    // =============================================================================================

    @Test
    fun testScenario4_serverVersionProgressionAndMetadataIntegrity() = runBlocking {
        val account = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_meta_test",
                displayName = "Metadata Test Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 30000.0,
                updatedAt = System.currentTimeMillis()
            )
        )
        deviceA.pushToCloud()
        deviceB.pullFromCloud()

        // Perform sequential transactions across both devices
        deviceA.ledgerRepository.addDebt("acc_meta_test", 10000.0, "Step 1 Debt", "tx-step-1")
        deviceA.pushToCloud()

        deviceB.pullFromCloud()
        deviceB.ledgerRepository.addPayment("acc_meta_test", 5000.0, "Step 2 Payment", "tx-step-2")
        deviceB.pushToCloud()

        deviceA.pullFromCloud()
        deviceA.ledgerRepository.addDebt("acc_meta_test", 20000.0, "Step 3 Debt", "tx-step-3")
        deviceA.pushToCloud()

        deviceB.pullFromCloud()

        // Check monotonic server version ordering in cloud documents
        val docStep1 = cloudBackend.getDoc("local_ledger_entries", "tx-step-1")!!
        val docStep2 = cloudBackend.getDoc("local_ledger_entries", "tx-step-2")!!
        val docStep3 = cloudBackend.getDoc("local_ledger_entries", "tx-step-3")!!

        assertTrue("Server timestamps must advance monotonically: step1 < step2", docStep1.serverVersion < docStep2.serverVersion)
        assertTrue("Server timestamps must advance monotonically: step2 < step3", docStep2.serverVersion < docStep3.serverVersion)

        // Check local remote_version metadata tracking on both devices
        for (stepId in listOf("tx-step-1", "tx-step-2", "tx-step-3")) {
            val verA = deviceA.metadataDao.get("remote_version:ledger:$stepId")
            val verB = deviceB.metadataDao.get("remote_version:ledger:$stepId")
            assertNotNull("Device A must have remote_version for $stepId", verA)
            assertNotNull("Device B must have remote_version for $stepId", verB)
            assertEquals("Both devices must record identical remote_version for $stepId", verA, verB)

            // resolveLocalVersion must return ServerTracked
            val stateA = deviceA.coordinator.resolveLocalVersion("ledger", stepId)
            val stateB = deviceB.coordinator.resolveLocalVersion("ledger", stepId)
            assertTrue("State on Device A must be ServerTracked", stateA is LocalVersionState.ServerTracked)
            assertTrue("State on Device B must be ServerTracked", stateB is LocalVersionState.ServerTracked)
            assertEquals((stateA as LocalVersionState.ServerTracked).version, (stateB as LocalVersionState.ServerTracked).version)
        }

        // Outbox obligations on both devices must be cleared
        assertEquals(0, deviceA.outboxDao.getPending().size)
        assertEquals(0, deviceB.outboxDao.getPending().size)
    }

    // =============================================================================================
    // Scenario 5: Section 4.13 Baseline T1 + Offline Branches T2/T3 Lossless Convergence
    // =============================================================================================

    @Test
    fun testScenario5_section413_baselineT1_offlineBranchesT2T3_losslessConvergence() = runBlocking {
        /**
         * Scenario per Section 4.13:
         * Cloud:    T1
         * Device A: T1 + T2
         * Device B: T1 + T3
         *
         * A reconnects and uploads T2
         * B reconnects and uploads T3
         * A/B pull updates
         *
         * Required final business set:
         * Cloud    = T1 + T2 + T3
         * Device A = T1 + T2 + T3
         * Device B = T1 + T2 + T3
         */
        val baseAccount = LocalAccount(
            id = "acc_sec413",
            displayName = "Section 4.13 Baseline Account",
            debtIqd = 100000.0,
            advanceIqd = 0.0,
            currentPriceIqd = 40000.0,
            updatedAt = 1700000000000L
        )
        val txT1 = LocalLedgerEntry(
            id = "tx_T1",
            accountId = "acc_sec413",
            typeRaw = "took",
            amountIqd = 100000.0,
            debtAfterIqd = 100000.0,
            occurredAt = 1700000000000L,
            note = "Baseline Opening Debt T1"
        )
        // Record T1 on Cloud
        cloudBackend.recordDirectAccount(baseAccount, 1700000000000L)
        cloudBackend.recordDirectLedger(txT1, 1700000000000L)

        // Both Device A and Device B start with baseline T1
        deviceA.pullFromCloud()
        deviceB.pullFromCloud()

        assertEquals(1, deviceA.ledgerDao.getAllOneShot().size)
        assertEquals(1, deviceB.ledgerDao.getAllOneShot().size)

        // Device A creates T2 offline (Payment of 30,000 IQD)
        val txT2 = deviceA.ledgerRepository.addPayment(
            accountId = "acc_sec413",
            amount = 30000.0,
            note = "Payment T2 from Device A",
            idempotencyKey = "tx_T2"
        )
        assertEquals("tx_T2", txT2.id)

        // Device B creates T3 offline (Payment of 20,000 IQD)
        val txT3 = deviceB.ledgerRepository.addPayment(
            accountId = "acc_sec413",
            amount = 20000.0,
            note = "Payment T3 from Device B",
            idempotencyKey = "tx_T3"
        )
        assertEquals("tx_T3", txT3.id)

        // Device A reconnects and uploads T2
        deviceA.pushToCloud()

        // Device B reconnects and uploads T3
        deviceB.pushToCloud()

        // Both devices pull changes and converge
        deviceA.pullFromCloud()
        deviceB.pullFromCloud()

        // Verify Final Business Set: Cloud, Device A, and Device B all contain exact set {T1, T2, T3}
        val cloudDocs = cloudBackend.getAllDocuments().filter { it.entityType == "local_ledger_entries" }
        val ledgerListA = deviceA.ledgerDao.getAllOneShot().sortedBy { it.id }
        val ledgerListB = deviceB.ledgerDao.getAllOneShot().sortedBy { it.id }

        assertEquals(3, cloudDocs.size)
        assertEquals(3, ledgerListA.size)
        assertEquals(3, ledgerListB.size)

        val expectedIds = listOf("tx_T1", "tx_T2", "tx_T3")
        assertEquals(expectedIds, cloudDocs.map { it.entityId }.sorted())
        assertEquals(expectedIds, ledgerListA.map { it.id })
        assertEquals(expectedIds, ledgerListB.map { it.id })

        // Verify No Duplicate T1, No Loss of T2/T3, and equal balance derivation on both devices
        val finalAccA = deviceA.accountDao.getByIdOneShot("acc_sec413")!!
        val finalAccB = deviceB.accountDao.getByIdOneShot("acc_sec413")!!

        // 100,000 debt - 30,000 (T2) - 20,000 (T3) = 50,000 debt
        assertEquals(50000.0, finalAccA.debtIqd, 0.001)
        assertEquals(0.0, finalAccA.advanceIqd, 0.001)
        assertEquals(50000.0, finalAccB.debtIqd, 0.001)
        assertEquals(0.0, finalAccB.advanceIqd, 0.001)
    }

    // =============================================================================================
    // Scenario 6: Idempotent Convergence & Zero-Write Invariant (INV-10)
    // =============================================================================================

    @Test
    fun testScenario6_idempotentConvergence_zeroAdditionalWritesOnSubsequentSync() = runBlocking {
        // Setup shared synchronized state
        val account = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_idempotent",
                displayName = "Idempotent Sync Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 35000.0,
                updatedAt = System.currentTimeMillis()
            )
        )
        deviceA.pushToCloud()
        deviceB.pullFromCloud()

        deviceA.ledgerRepository.addPayment("acc_idempotent", 35000.0, "Pay 1", "tx-idem-1")
        deviceA.pushToCloud()
        deviceB.pullFromCloud()
        deviceA.pullFromCloud()

        val cloudMutationCountBefore = cloudBackend.getAllDocuments().size
        val ledgerCountABefore = deviceA.ledgerDao.getAllOneShot().size
        val ledgerCountBBefore = deviceB.ledgerDao.getAllOneShot().size

        // Perform 3 successive sync cycles across both devices
        for (i in 1..3) {
            val pushedA = deviceA.pushToCloud()
            val pushedB = deviceB.pushToCloud()
            val pulledA = deviceA.pullFromCloud()
            val pulledB = deviceB.pullFromCloud()

            assertEquals("Subsequent push from A must produce 0 mutations", 0, pushedA)
            assertEquals("Subsequent push from B must produce 0 mutations", 0, pushedB)
            assertEquals("Subsequent pull to A must apply 0 new events", 0, pulledA)
            assertEquals("Subsequent pull to B must apply 0 new events", 0, pulledB)
        }

        // Verify cloud and local stores have zero changes or phantom writes
        assertEquals(cloudMutationCountBefore, cloudBackend.getAllDocuments().size)
        assertEquals(ledgerCountABefore, deviceA.ledgerDao.getAllOneShot().size)
        assertEquals(ledgerCountBBefore, deviceB.ledgerDao.getAllOneShot().size)
    }

    // =============================================================================================
    // Scenario 7: Outbox Durability Under Network Failure & Retry (INV-13)
    // =============================================================================================

    @Test
    fun testScenario7_outboxDurabilityUnderNetworkFailure_retriesSuccessfully() = runBlocking {
        val account = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_failover_test",
                displayName = "Failover Test Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 35000.0,
                updatedAt = System.currentTimeMillis()
            )
        )
        deviceA.pushToCloud()
        deviceB.pullFromCloud()

        val entry = deviceA.ledgerRepository.addPayment("acc_failover_test", 15000.0, "Flaky Payment", "tx-flaky-1")
        assertEquals("tx-flaky-1", entry.id)

        // Simulate network failure during push
        deviceA.simulateNetworkFailureOnNextPush("Simulated HTTP 503 Service Unavailable")
        val pushedCountFailed = deviceA.pushToCloud()
        assertEquals(0, pushedCountFailed)

        // Verify outbox obligation is preserved in failed/retryable status (INV-13: no DEAD_LETTER drop)
        val pendingOrFailed = deviceA.outboxDao.getPending()
        assertTrue("Outbox must retain obligation during network failure", pendingOrFailed.isNotEmpty())
        val failedItem = deviceA.outboxDao.getFailedItems().firstOrNull { it.entityId == "tx-flaky-1" }
        assertNotNull("Failed outbox item for tx-flaky-1 must exist", failedItem)
        assertEquals("failed", failedItem!!.status)
        assertEquals("Simulated HTTP 503 Service Unavailable", failedItem.lastError)

        // Network restored -> subsequent push succeeds
        deviceA.clearNetworkFailureSimulation()
        val pushedCountRecovered = deviceA.pushToCloud()
        assertTrue("Retry push must succeed", pushedCountRecovered > 0)

        // Device B pulls and converges
        deviceB.pullFromCloud()

        assertNotNull("Device A must have tx-flaky-1", deviceA.ledgerDao.getByIdOneShot("tx-flaky-1"))
        assertNotNull("Device B must have tx-flaky-1", deviceB.ledgerDao.getByIdOneShot("tx-flaky-1"))
        assertEquals(
            deviceA.accountDao.getByIdOneShot("acc_failover_test")!!.advanceIqd,
            deviceB.accountDao.getByIdOneShot("acc_failover_test")!!.advanceIqd,
            0.001
        )
    }

    // =============================================================================================
    // Scenario 8: Parent Account Auto-Creation via Pre-Fetched Hierarchy
    // =============================================================================================

    @Test
    fun testScenario8_parentAccountAutocreation_andPrefetchedHierarchyResolution() = runBlocking {
        // Device A creates new account and new ledger entry
        val freshAccount = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_hier_parent",
                displayName = "Parent Account Hierarchy",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 50000.0,
                updatedAt = System.currentTimeMillis()
            )
        )
        val freshLedger = deviceA.ledgerRepository.addDebt("acc_hier_parent", 50000.0, "Hierarchy Debt", "tx-hier-1")

        // Device A pushes both
        deviceA.pushToCloud()

        // Device B does NOT have acc_hier_parent yet
        assertNull(deviceB.accountDao.getByIdOneShot("acc_hier_parent"))

        // Device B pulls with pre-fetched parent account resolution
        val applied = deviceB.pullFromCloud()
        assertTrue("Device B must apply pulled events", applied > 0)

        // Device B now has both parent account and child ledger
        val accOnB = deviceB.accountDao.getByIdOneShot("acc_hier_parent")
        val ledgerOnB = deviceB.ledgerDao.getByIdOneShot("tx-hier-1")

        assertNotNull("Parent account must be created on Device B", accOnB)
        assertNotNull("Ledger entry must be created on Device B", ledgerOnB)
        assertEquals(50000.0, accOnB!!.debtIqd, 0.001)
        assertEquals(50000.0, ledgerOnB!!.amountIqd, 0.001)
    }
}

// =================================================================================================
// Two-Device Simulation Test Fixture Helper Components
// =================================================================================================

class SimulatedDevice(
    val name: String,
    val context: Context,
    val cloud: SimulatedCloudBackend
) {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    val accountDao = db.localAccountDao()
    val ledgerDao = db.localLedgerEntryDao()
    val outboxDao = db.syncOutboxDao()
    val pendingDao = db.pendingExternalOperationDao()
    val batchDao = db.importBatchDao()
    val metadataDao = db.syncMetadataDao()
    val auditDao = db.auditLogDao()

    val accountRepository: LocalAccountRepository = LocalAccountRepositoryImpl(
        database = db,
        accountDao = accountDao,
        outboxDao = outboxDao
    )

    val ledgerRepository: LocalLedgerRepository = LocalLedgerRepositoryImpl(
        database = db,
        ledgerDao = ledgerDao,
        accountDao = accountDao,
        outboxDao = outboxDao,
        pendingDao = pendingDao
    )

    val coordinator: RemoteSyncCoordinator = RemoteSyncCoordinator(
        appDatabase = db,
        accountDao = accountDao,
        ledgerDao = ledgerDao,
        batchDao = batchDao,
        outboxDao = outboxDao,
        metadataDao = metadataDao,
        auditDao = auditDao
    )

    private var injectedFailureReason: String? = null

    fun simulateNetworkFailureOnNextPush(reason: String) {
        injectedFailureReason = reason
    }

    fun clearNetworkFailureSimulation() {
        injectedFailureReason = null
    }

    suspend fun pushToCloud(): Int {
        val failure = injectedFailureReason
        if (failure != null) {
            val pendingItems = outboxDao.getPending()
            OutboxManager.markRetryableFailure(outboxDao, pendingItems, failure)
            return 0
        }

        val pending = outboxDao.getPending()
        if (pending.isEmpty()) return 0

        var pushedCount = 0
        for (item in pending) {
            val serverVersion = cloud.recordMutation(
                entityType = item.entityType,
                entityId = item.entityId,
                operation = item.operation,
                payloadJson = item.payloadJson,
                sourceDevice = name
            )
            OutboxManager.markSucceeded(outboxDao, listOf(item.id))

            val entityTypeKey = when (item.entityType) {
                "local_accounts" -> "account"
                "local_ledger_entries" -> "ledger"
                "import_batches" -> "batch"
                else -> item.entityType
            }
            if (item.operation == "delete") {
                metadataDao.put("tombstone:$entityTypeKey:${item.entityId}", serverVersion.toString())
            }
            metadataDao.put("remote_version:$entityTypeKey:${item.entityId}", serverVersion.toString())
            pushedCount++
        }
        return pushedCount
    }

    suspend fun pullFromCloud(): Int {
        val lastSyncTs = metadataDao.get("last_sync_timestamp")?.toLongOrNull() ?: 0L
        val updates = cloud.getUpdatesSince(lastSyncTs)
        var appliedCount = 0
        var maxVersion = lastSyncTs

        for (cloudDoc in updates) {
            val preFetchedAccount = if (cloudDoc.entityType == "local_ledger_entries") {
                val accountId = cloud.extractAccountId(cloudDoc.payloadJson)
                if (accountId != null) cloud.getAccount(accountId) else null
            } else null

            val event = cloud.toRemoteEvent(cloudDoc, RemoteEventSource.PULL, preFetchedAccount)
            if (event != null) {
                val result = coordinator.processEvent(event)
                if (result.canAdvanceCursor()) {
                    appliedCount++
                }
                if (cloudDoc.serverVersion > maxVersion) {
                    maxVersion = cloudDoc.serverVersion
                }
            }
        }
        if (maxVersion > lastSyncTs) {
            metadataDao.put("last_sync_timestamp", maxVersion.toString())
        }
        return appliedCount
    }

    fun close() {
        db.close()
    }
}

class SimulatedCloudBackend {
    private val clock = AtomicLong(1700000000000L)

    data class CloudDoc(
        val entityType: String,
        val entityId: String,
        val operation: String,
        val payloadJson: String,
        val serverVersion: Long,
        val deletedAt: Long?,
        val updatedAt: Long,
        val sourceDevice: String,
        val syncMutationId: String?
    )

    private val documents = mutableMapOf<Pair<String, String>, CloudDoc>()

    fun recordMutation(
        entityType: String,
        entityId: String,
        operation: String,
        payloadJson: String,
        sourceDevice: String
    ): Long {
        val serverTs = clock.addAndGet(1000L)
        val syncMutationId = try {
            val json = JSONObject(payloadJson)
            if (json.has("syncMutationId")) json.getString("syncMutationId") else null
        } catch (_: Exception) { null }

        val doc = CloudDoc(
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            payloadJson = payloadJson,
            serverVersion = serverTs,
            deletedAt = if (operation == "delete") serverTs else null,
            updatedAt = serverTs,
            sourceDevice = sourceDevice,
            syncMutationId = syncMutationId
        )
        documents[Pair(entityType, entityId)] = doc
        return serverTs
    }

    fun recordDirectAccount(account: LocalAccount, serverVersion: Long) {
        val json = JSONObject().apply {
            put("id", account.id)
            put("displayName", account.displayName)
            put("debtIqd", account.debtIqd)
            put("advanceIqd", account.advanceIqd)
            put("currentPriceIqd", account.currentPriceIqd)
            put("updatedAt", serverVersion)
            put("isFullSnapshot", true)
        }.toString()

        val doc = CloudDoc(
            entityType = "local_accounts",
            entityId = account.id,
            operation = "upsert",
            payloadJson = json,
            serverVersion = serverVersion,
            deletedAt = null,
            updatedAt = serverVersion,
            sourceDevice = "CLOUD_SEED",
            syncMutationId = "seed_account_${account.id}"
        )
        documents[Pair("local_accounts", account.id)] = doc
        if (serverVersion > clock.get()) clock.set(serverVersion)
    }

    fun recordDirectLedger(entry: LocalLedgerEntry, serverVersion: Long) {
        val json = JSONObject().apply {
            put("id", entry.id)
            put("accountId", entry.accountId)
            put("typeRaw", entry.typeRaw)
            put("amountIqd", entry.amountIqd)
            put("debtAfterIqd", entry.debtAfterIqd)
            put("occurredAt", entry.occurredAt)
            if (entry.note != null) put("note", entry.note)
            put("updatedAt", serverVersion)
        }.toString()

        val doc = CloudDoc(
            entityType = "local_ledger_entries",
            entityId = entry.id,
            operation = "upsert",
            payloadJson = json,
            serverVersion = serverVersion,
            deletedAt = null,
            updatedAt = serverVersion,
            sourceDevice = "CLOUD_SEED",
            syncMutationId = "seed_ledger_${entry.id}"
        )
        documents[Pair("local_ledger_entries", entry.id)] = doc
        if (serverVersion > clock.get()) clock.set(serverVersion)
    }

    fun getUpdatesSince(sinceTs: Long): List<CloudDoc> {
        return documents.values
            .filter { it.serverVersion > sinceTs }
            .sortedBy { it.serverVersion }
    }

    fun getDoc(entityType: String, entityId: String): CloudDoc? {
        return documents[Pair(entityType, entityId)]
    }

    fun getAllDocuments(): List<CloudDoc> {
        return documents.values.sortedBy { it.serverVersion }
    }

    fun extractAccountId(payloadJson: String): String? {
        return try {
            val json = JSONObject(payloadJson)
            if (json.has("accountId")) json.getString("accountId") else null
        } catch (_: Exception) { null }
    }

    fun getAccount(accountId: String): LocalAccount? {
        val doc = documents[Pair("local_accounts", accountId)] ?: return null
        if (doc.operation == "delete" || doc.deletedAt != null) return null
        return try {
            val json = JSONObject(doc.payloadJson)
            val displayName = if (json.has("displayName")) json.getString("displayName") else doc.entityId
            LocalAccount(
                id = doc.entityId,
                displayName = displayName,
                debtIqd = json.optDouble("debtIqd", 0.0),
                advanceIqd = json.optDouble("advanceIqd", 0.0),
                currentPriceIqd = json.optDouble("currentPriceIqd", 40000.0),
                updatedAt = doc.serverVersion
            )
        } catch (_: Exception) { null }
    }

    fun toRemoteEvent(doc: CloudDoc, source: RemoteEventSource, preFetchedParentAccount: LocalAccount? = null): RemoteEvent? {
        val json = try { JSONObject(doc.payloadJson) } catch (_: Exception) { null }
        return when (doc.entityType) {
            "local_accounts" -> {
                if (doc.operation == "delete" || doc.deletedAt != null) {
                    RemoteEvent.AccountDelete(
                        entityId = doc.entityId,
                        remoteVersion = doc.serverVersion,
                        source = source,
                        syncMutationId = doc.syncMutationId
                    )
                } else {
                    val displayName = json?.optString("displayName") ?: doc.entityId
                    val externalId = if (json != null && json.has("sourceExternalId") && !json.isNull("sourceExternalId")) json.getString("sourceExternalId") else null
                    val acc = LocalAccount(
                        id = doc.entityId,
                        displayName = displayName,
                        debtIqd = json?.optDouble("debtIqd", 0.0) ?: 0.0,
                        advanceIqd = json?.optDouble("advanceIqd", 0.0) ?: 0.0,
                        currentPriceIqd = json?.optDouble("currentPriceIqd", 40000.0) ?: 40000.0,
                        updatedAt = doc.serverVersion,
                        sourceExternalId = externalId
                    )
                    RemoteEvent.AccountUpsert(
                        entityId = doc.entityId,
                        remoteVersion = doc.serverVersion,
                        source = source,
                        account = acc,
                        syncMutationId = doc.syncMutationId
                    )
                }
            }
            "local_ledger_entries" -> {
                if (doc.operation == "delete" || doc.deletedAt != null) {
                    RemoteEvent.LedgerDelete(
                        entityId = doc.entityId,
                        remoteVersion = doc.serverVersion,
                        source = source,
                        syncMutationId = doc.syncMutationId
                    )
                } else {
                    val accountId = json?.optString("accountId") ?: ""
                    val typeRaw = json?.optString("typeRaw") ?: "gave"
                    val amountIqd = json?.optDouble("amountIqd", 0.0) ?: 0.0
                    val debtAfterIqd = json?.optDouble("debtAfterIqd", 0.0) ?: 0.0
                    val note = if (json != null && json.has("note") && !json.isNull("note")) json.getString("note") else null
                    val occurredAt = json?.optLong("occurredAt", doc.serverVersion) ?: doc.serverVersion
                    val externalId = if (json != null && json.has("sourceExternalId") && !json.isNull("sourceExternalId")) json.getString("sourceExternalId") else null

                    val entry = LocalLedgerEntry(
                        id = doc.entityId,
                        accountId = accountId,
                        typeRaw = typeRaw,
                        amountIqd = amountIqd,
                        debtAfterIqd = debtAfterIqd,
                        note = note,
                        occurredAt = occurredAt,
                        sourceExternalId = externalId
                    )
                    RemoteEvent.LedgerUpsert(
                        entityId = doc.entityId,
                        remoteVersion = doc.serverVersion,
                        source = source,
                        entry = entry,
                        preFetchedParentAccount = preFetchedParentAccount,
                        syncMutationId = doc.syncMutationId
                    )
                }
            }
            else -> null
        }
    }
}
