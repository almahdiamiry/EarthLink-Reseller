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
 * Final Cross-Device Firestore Simulation & Isolation Test Suite.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - INV-01: Financial Correctness & Zero Balance Drift across multi-generation subscriber reuse.
 *    - INV-02: History Preservation & Monotonic History-Only State across remote sync.
 *    - INV-06: Cross-Device Convergence & Lineage Integrity across 2-device and 3-device topologies.
 *    - INV-09: Identity & Provenance Integrity under Recycled PPPoE: Each generation has a distinct,
 *              immutable account ID; Firestore document paths (users/{uid}/local_accounts/{accountId})
 *              never collide or overwrite each other when PPPoE usernames are recycled.
 *    - INV-11: Idempotence & Replay Safety: Duplicate remote delivery and event retransmission
 *              produce zero duplicate ledger entries and zero financial distortion.
 * 2. Seam / Environment:
 *    - ROBOLECTRIC: Independent SQLite Room databases simulating physically isolated client devices
 *      (Device A, Device B, Device C) communicating via an authoritative in-memory Firestore backend
 *      replicating the users/{uid}/local_accounts and users/{uid}/local_ledger_entries collections.
 * 3. Independent Oracle:
 *    - Explicit primitive arithmetic decomposition for expected balances and strict set equality
 *      without circular production formula invocation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FinalCrossDeviceFirestoreSimulationTest {

    private lateinit var context: Context
    private lateinit var cloudBackend: SimulatedFirestoreCloudBackend
    private lateinit var deviceA: SimulatedIsolatedDevice
    private lateinit var deviceB: SimulatedIsolatedDevice
    private lateinit var deviceC: SimulatedIsolatedDevice

    private val testPppoe = "subscriber_pppoe_test"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true

        cloudBackend = SimulatedFirestoreCloudBackend()
        deviceA = SimulatedIsolatedDevice("Device_A", context, cloudBackend)
        deviceB = SimulatedIsolatedDevice("Device_B", context, cloudBackend)
        deviceC = SimulatedIsolatedDevice("Device_C", context, cloudBackend)
    }

    @After
    fun tearDown() = runBlocking {
        deviceA.close()
        deviceB.close()
        deviceC.close()
    }

    // =============================================================================================
    // SECTION 1: TWO-DEVICE SIMULATION (Device A + Device B)
    // =============================================================================================

    /**
     * Test 1.1: Device A (Gen1 with debt -> history-only) + Device B (Gen2 active, same PPPoE)
     * Bidirectional sync converges both devices to identical datasets without Firestore overwrite.
     */
    @Test
    fun testTwoDevice_gen1HistoryOnly_gen2Active_samePppoe_convergesWithoutOverwrite() = runBlocking {
        // Step 1: Device A creates Gen1 subscriber with PPPoE testPppoe
        val gen1Acc = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_gen1",
                displayName = "Gen1 Customer",
                earthlinkUsername = testPppoe,
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 35000.0,
                isHistoryOnlySubscriber = false,
                updatedAt = System.currentTimeMillis()
            )
        )
        val txGen1 = deviceA.ledgerRepository.addDebt(
            accountId = gen1Acc.id,
            amount = 50000.0,
            note = "Initial fee for Gen1",
            idempotencyKey = "tx_gen1_debt"
        )
        assertEquals("tx_gen1_debt", txGen1.id)

        // Gen1 transitions to history-only on Device A
        deviceA.accountRepository.deleteAccount(gen1Acc.id)
        val gen1OnA = deviceA.accountDao.getByIdOneShot("acc_gen1")
        assertNotNull(gen1OnA)
        assertTrue("Gen1 must be history-only on Device A", gen1OnA!!.isHistoryOnlySubscriber)
        assertEquals(50000.0, gen1OnA.debtIqd, 0.001)

        // Device A pushes Gen1 state and ledger to Cloud
        val pushedFromA = deviceA.pushToCloud()
        assertTrue("Device A must push outbox records", pushedFromA >= 2)

        // Step 2: Device B creates Gen2 subscriber with SAME PPPoE testPppoe
        val gen2Acc = deviceB.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_gen2",
                displayName = "Gen2 Customer",
                earthlinkUsername = testPppoe,
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 40000.0,
                isHistoryOnlySubscriber = false,
                updatedAt = System.currentTimeMillis()
            )
        )
        val txGen2 = deviceB.ledgerRepository.addDebt(
            accountId = gen2Acc.id,
            amount = 35000.0,
            note = "Activation fee for Gen2",
            idempotencyKey = "tx_gen2_debt"
        )
        assertEquals("tx_gen2_debt", txGen2.id)

        // Device B pushes Gen2 state and ledger to Cloud
        val pushedFromB = deviceB.pushToCloud()
        assertTrue("Device B must push outbox records", pushedFromB >= 2)

        // PROOF: Verify Cloud Firestore document isolation
        val cloudAccounts = cloudBackend.getDocumentsForCollection("local_accounts")
        val cloudAccountIds = cloudAccounts.map { it.entityId }.toSet()
        assertEquals("Cloud must contain exactly 2 distinct account documents", 2, cloudAccountIds.size)
        assertTrue("Cloud must contain acc_gen1", cloudAccountIds.contains("acc_gen1"))
        assertTrue("Cloud must contain acc_gen2", cloudAccountIds.contains("acc_gen2"))

        val cloudLedgers = cloudBackend.getDocumentsForCollection("local_ledger_entries")
        val cloudLedgerIds = cloudLedgers.map { it.entityId }.toSet()
        assertEquals("Cloud must contain exactly 2 distinct ledger documents", 2, cloudLedgerIds.size)
        assertTrue("Cloud must contain tx_gen1_debt", cloudLedgerIds.contains("tx_gen1_debt"))
        assertTrue("Cloud must contain tx_gen2_debt", cloudLedgerIds.contains("tx_gen2_debt"))

        // Step 3: Bidirectional Cross-Sync
        // Device B pulls Gen1 from Cloud
        val pulledToB = deviceB.pullFromCloud()
        assertTrue("Device B must pull Gen1 records", pulledToB >= 2)

        // Device A pulls Gen2 from Cloud
        val pulledToA = deviceA.pullFromCloud()
        assertTrue("Device A must pull Gen2 records", pulledToA >= 2)

        // PROOF: Convergence on Device A
        val allPersistedOnA = deviceA.accountDao.getAllPersistedOneShot()
        assertEquals("Device A must have both accounts persisted", 2, allPersistedOnA.size)
        val activeOnA = deviceA.accountDao.getAllOneShot()
        assertEquals("Device A must have exactly 1 active account", 1, activeOnA.size)
        assertEquals("Active account on Device A must be Gen2", "acc_gen2", activeOnA[0].id)
        assertFalse("Active account on Device A must not be history-only", activeOnA[0].isHistoryOnlySubscriber)

        val activeByUsernameOnA = deviceA.accountDao.findActiveAccountByUsernameOrIdOneShot(testPppoe)
        assertNotNull("Active lookup by PPPoE must find active Gen2", activeByUsernameOnA)
        assertEquals("acc_gen2", activeByUsernameOnA!!.id)

        val histByUsernameOnA = deviceA.accountDao.findAccountByUsernameOrIdOneShot(testPppoe)
        assertNotNull("Historical lookup must succeed", histByUsernameOnA)

        // PROOF: Convergence on Device B
        val allPersistedOnB = deviceB.accountDao.getAllPersistedOneShot()
        assertEquals("Device B must have both accounts persisted", 2, allPersistedOnB.size)
        val activeOnB = deviceB.accountDao.getAllOneShot()
        assertEquals("Device B must have exactly 1 active account", 1, activeOnB.size)
        assertEquals("Active account on Device B must be Gen2", "acc_gen2", activeOnB[0].id)
        assertFalse("Active account on Device B must not be history-only", activeOnB[0].isHistoryOnlySubscriber)

        val activeByUsernameOnB = deviceB.accountDao.findActiveAccountByUsernameOrIdOneShot(testPppoe)
        assertNotNull("Active lookup by PPPoE must find active Gen2", activeByUsernameOnB)
        assertEquals("acc_gen2", activeByUsernameOnB!!.id)

        // PROOF: Financial Isolation & Zero Ledger Pollution
        val ledgersGen1OnA = deviceA.ledgerDao.getByAccountIdOneShot("acc_gen1")
        val ledgersGen1OnB = deviceB.ledgerDao.getByAccountIdOneShot("acc_gen1")
        assertEquals(1, ledgersGen1OnA.size)
        assertEquals(1, ledgersGen1OnB.size)
        assertEquals("tx_gen1_debt", ledgersGen1OnA[0].id)
        assertEquals("tx_gen1_debt", ledgersGen1OnB[0].id)
        assertEquals(50000.0, ledgersGen1OnA[0].amountIqd, 0.001)

        val ledgersGen2OnA = deviceA.ledgerDao.getByAccountIdOneShot("acc_gen2")
        val ledgersGen2OnB = deviceB.ledgerDao.getByAccountIdOneShot("acc_gen2")
        assertEquals(1, ledgersGen2OnA.size)
        assertEquals(1, ledgersGen2OnB.size)
        assertEquals("tx_gen2_debt", ledgersGen2OnA[0].id)
        assertEquals("tx_gen2_debt", ledgersGen2OnB[0].id)
        assertEquals(35000.0, ledgersGen2OnA[0].amountIqd, 0.001)

        // Independent balances
        assertEquals(50000.0, deviceA.accountDao.getByIdOneShot("acc_gen1")!!.debtIqd, 0.001)
        assertEquals(50000.0, deviceB.accountDao.getByIdOneShot("acc_gen1")!!.debtIqd, 0.001)
        assertEquals(35000.0, deviceA.accountDao.getByIdOneShot("acc_gen2")!!.debtIqd, 0.001)
        assertEquals(35000.0, deviceB.accountDao.getByIdOneShot("acc_gen2")!!.debtIqd, 0.001)
    }

    /**
     * Test 1.2: Concurrent Offline Mutations with Interleaved Pulls
     * Both devices perform offline mutations before syncing; arrival order is interleaved.
     */
    @Test
    fun testTwoDevice_concurrentOfflineMutations_interleavedSync_convergesDeterministically() = runBlocking {
        // Device A: Gen1 with debt + payment -> history-only
        val acc1 = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_conc_1",
                displayName = "Concurrent Gen1",
                earthlinkUsername = "conc_pppoe",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 35000.0,
                isHistoryOnlySubscriber = false,
                updatedAt = System.currentTimeMillis()
            )
        )
        deviceA.ledgerRepository.addDebt("acc_conc_1", 60000.0, "Gen1 Subscription", "tx_c1_d")
        deviceA.ledgerRepository.addPayment("acc_conc_1", 20000.0, "Gen1 Payment", "tx_c1_p")
        deviceA.accountRepository.deleteAccount("acc_conc_1")

        // Device B: Gen2 with debt + payment -> remains active
        val acc2 = deviceB.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_conc_2",
                displayName = "Concurrent Gen2",
                earthlinkUsername = "conc_pppoe",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                currentPriceIqd = 40000.0,
                isHistoryOnlySubscriber = false,
                updatedAt = System.currentTimeMillis()
            )
        )
        deviceB.ledgerRepository.addDebt("acc_conc_2", 40000.0, "Gen2 Subscription", "tx_c2_d")
        deviceB.ledgerRepository.addPayment("acc_conc_2", 10000.0, "Gen2 Payment", "tx_c2_p")

        // Both push to cloud concurrently
        deviceA.pushToCloud()
        deviceB.pushToCloud()

        // Interleaved sync: Device A pulls first half of updates, Device B pulls all, Device A pulls remainder
        val allUpdates = cloudBackend.getAllDocuments()
        val halfPoint = allUpdates.size / 2

        val firstChunk = allUpdates.take(halfPoint)
        val secondChunk = allUpdates.drop(halfPoint)

        deviceA.applyRawCloudDocs(firstChunk)
        deviceB.pullFromCloud()
        deviceA.applyRawCloudDocs(secondChunk)

        // Assert convergence
        val acc1OnA = deviceA.accountDao.getByIdOneShot("acc_conc_1")!!
        val acc1OnB = deviceB.accountDao.getByIdOneShot("acc_conc_1")!!
        val acc2OnA = deviceA.accountDao.getByIdOneShot("acc_conc_2")!!
        val acc2OnB = deviceB.accountDao.getByIdOneShot("acc_conc_2")!!

        assertTrue("Gen1 must be history-only on A", acc1OnA.isHistoryOnlySubscriber)
        assertTrue("Gen1 must be history-only on B", acc1OnB.isHistoryOnlySubscriber)
        assertFalse("Gen2 must be active on A", acc2OnA.isHistoryOnlySubscriber)
        assertFalse("Gen2 must be active on B", acc2OnB.isHistoryOnlySubscriber)

        // Expected Position decomposition:
        // Gen1 debt = 60,000 - 20,000 = 40,000 IQD
        assertEquals(40000.0, acc1OnA.debtIqd, 0.001)
        assertEquals(40000.0, acc1OnB.debtIqd, 0.001)

        // Gen2 debt = 40,000 - 10,000 = 30,000 IQD
        assertEquals(30000.0, acc2OnA.debtIqd, 0.001)
        assertEquals(30000.0, acc2OnB.debtIqd, 0.001)

        // Zero ledger leakage
        assertEquals(2, deviceA.ledgerDao.getByAccountIdOneShot("acc_conc_1").size)
        assertEquals(2, deviceB.ledgerDao.getByAccountIdOneShot("acc_conc_1").size)
        assertEquals(2, deviceA.ledgerDao.getByAccountIdOneShot("acc_conc_2").size)
        assertEquals(2, deviceB.ledgerDao.getByAccountIdOneShot("acc_conc_2").size)
    }

    /**
     * Test 1.3: Replay and Duplicate Delivery Safety (INV-11)
     * Replaying already-applied cloud events multiple times generates zero duplicates and zero balance drift.
     */
    @Test
    fun testTwoDevice_replayAndDuplicateDelivery_zeroFinancialDuplicates() = runBlocking {
        // Setup initial converged state between A and B
        deviceA.accountRepository.saveAccount(
            LocalAccount(id = "acc_rep_1", displayName = "Replay Gen1", earthlinkUsername = "rep_pppoe")
        )
        deviceA.ledgerRepository.addDebt("acc_rep_1", 25000.0, "Debt 1", "tx_rep_1")
        deviceA.accountRepository.deleteAccount("acc_rep_1")
        deviceA.pushToCloud()

        deviceB.accountRepository.saveAccount(
            LocalAccount(id = "acc_rep_2", displayName = "Replay Gen2", earthlinkUsername = "rep_pppoe")
        )
        deviceB.ledgerRepository.addDebt("acc_rep_2", 35000.0, "Debt 2", "tx_rep_2")
        deviceB.pushToCloud()

        deviceA.pullFromCloud()
        deviceB.pullFromCloud()

        // Capture converged snapshot
        val initialLedgersCountA = deviceA.ledgerDao.getAllOneShot().size
        val initialLedgersCountB = deviceB.ledgerDao.getAllOneShot().size
        assertEquals(2, initialLedgersCountA)
        assertEquals(2, initialLedgersCountB)

        // Execute 3 repeated replays of all cloud documents on both devices
        val allDocs = cloudBackend.getAllDocuments()
        for (pass in 1..3) {
            deviceA.applyRawCloudDocs(allDocs)
            deviceB.applyRawCloudDocs(allDocs)
        }

        // Assert zero duplicate records created
        assertEquals(2, deviceA.accountDao.getAllPersistedOneShot().size)
        assertEquals(2, deviceB.accountDao.getAllPersistedOneShot().size)
        assertEquals(2, deviceA.ledgerDao.getAllOneShot().size)
        assertEquals(2, deviceB.ledgerDao.getAllOneShot().size)

        // Assert balances unchanged
        assertEquals(25000.0, deviceA.accountDao.getByIdOneShot("acc_rep_1")!!.debtIqd, 0.001)
        assertEquals(25000.0, deviceB.accountDao.getByIdOneShot("acc_rep_1")!!.debtIqd, 0.001)
        assertEquals(35000.0, deviceA.accountDao.getByIdOneShot("acc_rep_2")!!.debtIqd, 0.001)
        assertEquals(35000.0, deviceB.accountDao.getByIdOneShot("acc_rep_2")!!.debtIqd, 0.001)
    }

    /**
     * Test 1.4: Monotonic History-Only Safety under Stale Remote Upsert
     * A stale remote upsert with isHistoryOnlySubscriber = false arriving after local deactivation
     * cannot revert the account to active status.
     */
    @Test
    fun testTwoDevice_staleUpsertCannotRevertHistoryOnlyStatus() = runBlocking {
        // Device A creates Gen1 and deactivates it
        deviceA.accountRepository.saveAccount(
            LocalAccount(id = "acc_stale_1", displayName = "Stale Test", earthlinkUsername = "stale_pppoe")
        )
        deviceA.accountRepository.deleteAccount("acc_stale_1")

        val localAcc = deviceA.accountDao.getByIdOneShot("acc_stale_1")!!
        assertTrue(localAcc.isHistoryOnlySubscriber)
        val localVersion = localAcc.updatedAt

        // Construct a stale remote event with older timestamp and isHistoryOnlySubscriber = false
        val stalePayload = JSONObject().apply {
            put("id", "acc_stale_1")
            put("displayName", "Stale Remote Gen1")
            put("earthlinkUsername", "stale_pppoe")
            put("debtIqd", 0.0)
            put("advanceIqd", 0.0)
            put("currentPriceIqd", 35000.0)
            put("isHistoryOnlySubscriber", false)
            put("updatedAt", localVersion - 5000L)
            put("isFullSnapshot", true)
        }.toString()

        val staleEvent = RemoteEvent.AccountUpsert(
            entityId = "acc_stale_1",
            remoteVersion = localVersion - 5000L,
            source = RemoteEventSource.PULL,
            account = LocalAccount(
                id = "acc_stale_1",
                displayName = "Stale Remote Gen1",
                earthlinkUsername = "stale_pppoe",
                isHistoryOnlySubscriber = false,
                updatedAt = localVersion - 5000L
            )
        )

        val result = deviceA.coordinator.processEvent(staleEvent)
        assertEquals(EventSyncResult.SKIPPED_DUPLICATE, result)

        val postEventAcc = deviceA.accountDao.getByIdOneShot("acc_stale_1")!!
        assertTrue("Account must monotonically remain history-only", postEventAcc.isHistoryOnlySubscriber)
    }

    // =============================================================================================
    // SECTION 2: THREE-DEVICE ESCALATION (Device A + Device B + Device C)
    // =============================================================================================

    /**
     * Test 2.1: 3-Device Multi-Generation Lifecycle: Gen1 (A) -> Gen2 (B) -> Gen3 (C)
     * All 3 generations share the same PPPoE username.
     * Gen1 and Gen2 transition to history-only. Gen3 remains active.
     * Cross-sync across all 3 devices converges to identical state with zero pollution.
     */
    @Test
    fun testThreeDevice_gen1ToGen2ToGen3_multiGenerationReuse_convergesAcrossAllDevices() = runBlocking {
        val sharedPppoe = "recycled_broadband_user"

        // Generation 1 on Device A
        val gen1 = deviceA.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_gen1_tri",
                displayName = "Generation 1 Subscriber",
                earthlinkUsername = sharedPppoe,
                currentPriceIqd = 35000.0
            )
        )
        deviceA.ledgerRepository.addDebt(gen1.id, 50000.0, "Gen1 debt", "tx_g1_tri")
        deviceA.accountRepository.deleteAccount(gen1.id)
        deviceA.pushToCloud()

        // Device B pulls Gen1 from Cloud, then creates Generation 2 with SAME PPPoE
        deviceB.pullFromCloud()
        val gen2 = deviceB.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_gen2_tri",
                displayName = "Generation 2 Subscriber",
                earthlinkUsername = sharedPppoe,
                currentPriceIqd = 40000.0
            )
        )
        deviceB.ledgerRepository.addDebt(gen2.id, 35000.0, "Gen2 debt", "tx_g2_tri")
        deviceB.accountRepository.deleteAccount(gen2.id)
        deviceB.pushToCloud()

        // Device C pulls Gen1 and Gen2 from Cloud, then creates Generation 3 with SAME PPPoE
        deviceC.pullFromCloud()
        val gen3 = deviceC.accountRepository.saveAccount(
            LocalAccount(
                id = "acc_gen3_tri",
                displayName = "Generation 3 Subscriber",
                earthlinkUsername = sharedPppoe,
                currentPriceIqd = 45000.0
            )
        )
        deviceC.ledgerRepository.addDebt(gen3.id, 20000.0, "Gen3 debt", "tx_g3_tri")
        // Gen3 remains ACTIVE (not deleted)
        deviceC.pushToCloud()

        // Complete the 3-device cross-sync loop
        deviceA.pullFromCloud()
        deviceB.pullFromCloud()
        deviceC.pullFromCloud()

        // PROOF: Verify Cloud Firestore Documents
        val cloudDocs = cloudBackend.getDocumentsForCollection("local_accounts")
        val cloudIds = cloudDocs.map { it.entityId }.toSet()
        assertEquals("Cloud must contain exactly 3 distinct account documents", 3, cloudIds.size)
        assertTrue(cloudIds.contains("acc_gen1_tri"))
        assertTrue(cloudIds.contains("acc_gen2_tri"))
        assertTrue(cloudIds.contains("acc_gen3_tri"))

        // PROOF: Verify identical convergence on ALL 3 DEVICES
        val devices = listOf(deviceA, deviceB, deviceC)
        for (device in devices) {
            val devName = device.name

            // 1. Persisted Accounts: Exactly 3
            val allPersisted = device.accountDao.getAllPersistedOneShot()
            assertEquals("[$devName] Must have exactly 3 persisted accounts", 3, allPersisted.size)
            val persistedIds = allPersisted.map { it.id }.toSet()
            assertEquals(setOf("acc_gen1_tri", "acc_gen2_tri", "acc_gen3_tri"), persistedIds)

            // 2. Active Accounts: Exactly 1 (Gen3)
            val activeAccounts = device.accountDao.getAllOneShot()
            assertEquals("[$devName] Must have exactly 1 active account", 1, activeAccounts.size)
            assertEquals("acc_gen3_tri", activeAccounts[0].id)
            assertFalse("[$devName] Gen3 must not be history-only", activeAccounts[0].isHistoryOnlySubscriber)

            // 3. Active PPPoE Lookup: Resolves to Gen3
            val activeByUsername = device.accountDao.findActiveAccountByUsernameOrIdOneShot(sharedPppoe)
            assertNotNull("[$devName] Active lookup must resolve", activeByUsername)
            assertEquals("acc_gen3_tri", activeByUsername!!.id)

            // 4. History-Only Verification
            val a1 = device.accountDao.getByIdOneShot("acc_gen1_tri")!!
            val a2 = device.accountDao.getByIdOneShot("acc_gen2_tri")!!
            val a3 = device.accountDao.getByIdOneShot("acc_gen3_tri")!!
            assertTrue("[$devName] Gen1 must be history-only", a1.isHistoryOnlySubscriber)
            assertTrue("[$devName] Gen2 must be history-only", a2.isHistoryOnlySubscriber)
            assertFalse("[$devName] Gen3 must be active", a3.isHistoryOnlySubscriber)

            // 5. Zero Balance Pollution & Exact Debts
            assertEquals("[$devName] Gen1 debt must be 50,000", 50000.0, a1.debtIqd, 0.001)
            assertEquals("[$devName] Gen2 debt must be 35,000", 35000.0, a2.debtIqd, 0.001)
            assertEquals("[$devName] Gen3 debt must be 20,000", 20000.0, a3.debtIqd, 0.001)

            // 6. Zero Ledger Pollution
            val ledgersG1 = device.ledgerDao.getByAccountIdOneShot("acc_gen1_tri")
            val ledgersG2 = device.ledgerDao.getByAccountIdOneShot("acc_gen2_tri")
            val ledgersG3 = device.ledgerDao.getByAccountIdOneShot("acc_gen3_tri")

            assertEquals(1, ledgersG1.size)
            assertEquals("tx_g1_tri", ledgersG1[0].id)
            assertEquals(1, ledgersG2.size)
            assertEquals("tx_g2_tri", ledgersG2[0].id)
            assertEquals(1, ledgersG3.size)
            assertEquals("tx_g3_tri", ledgersG3[0].id)
        }
    }

    /**
     * Test 2.2: Stale Device Catch-Up
     * Device A goes offline after creating Gen1.
     * Devices B and C progress through Gen2 and Gen3 and sync with Cloud.
     * Device A reconnects and executes catch-up sync in a single pass.
     * Device A converges to the exact dataset as B and C without losing its Gen1 history.
     */
    @Test
    fun testThreeDevice_staleDeviceCatchUp_reconstructsFullHistoryWithoutPollution() = runBlocking {
        val pppoe = "stale_catchup_user"

        // Device A creates Gen1 with debt + payment, marks history-only, pushes, then goes OFFLINE
        deviceA.accountRepository.saveAccount(
            LocalAccount(id = "acc_sc_1", displayName = "CatchUp Gen1", earthlinkUsername = pppoe)
        )
        deviceA.ledgerRepository.addDebt("acc_sc_1", 70000.0, "Fee", "tx_sc_1")
        deviceA.accountRepository.deleteAccount("acc_sc_1")
        deviceA.pushToCloud()

        // Device B (online): pulls Gen1, creates Gen2, marks history-only, pushes
        deviceB.pullFromCloud()
        deviceB.accountRepository.saveAccount(
            LocalAccount(id = "acc_sc_2", displayName = "CatchUp Gen2", earthlinkUsername = pppoe)
        )
        deviceB.ledgerRepository.addDebt("acc_sc_2", 45000.0, "Fee", "tx_sc_2")
        deviceB.accountRepository.deleteAccount("acc_sc_2")
        deviceB.pushToCloud()

        // Device C (online): pulls Gen1 & Gen2, creates Gen3 (active), pushes
        deviceC.pullFromCloud()
        deviceC.accountRepository.saveAccount(
            LocalAccount(id = "acc_sc_3", displayName = "CatchUp Gen3", earthlinkUsername = pppoe)
        )
        deviceC.ledgerRepository.addDebt("acc_sc_3", 30000.0, "Fee", "tx_sc_3")
        deviceC.pushToCloud()

        // Device B syncs with C
        deviceB.pullFromCloud()

        // Verify Device A is still stale (only has Gen1)
        assertEquals(1, deviceA.accountDao.getAllPersistedOneShot().size)
        assertEquals(1, deviceA.ledgerDao.getAllOneShot().size)

        // Device A reconnects and executes catch-up sync
        val pulledToA = deviceA.pullFromCloud()
        assertTrue("Device A must pull remote records during catch-up", pulledToA >= 4)

        // Verify Device A has identical state to Devices B and C
        assertEquals(3, deviceA.accountDao.getAllPersistedOneShot().size)
        assertEquals(1, deviceA.accountDao.getAllOneShot().size)
        assertEquals("acc_sc_3", deviceA.accountDao.getAllOneShot()[0].id)

        assertEquals(70000.0, deviceA.accountDao.getByIdOneShot("acc_sc_1")!!.debtIqd, 0.001)
        assertEquals(45000.0, deviceA.accountDao.getByIdOneShot("acc_sc_2")!!.debtIqd, 0.001)
        assertEquals(30000.0, deviceA.accountDao.getByIdOneShot("acc_sc_3")!!.debtIqd, 0.001)

        assertTrue(deviceA.accountDao.getByIdOneShot("acc_sc_1")!!.isHistoryOnlySubscriber)
        assertTrue(deviceA.accountDao.getByIdOneShot("acc_sc_2")!!.isHistoryOnlySubscriber)
        assertFalse(deviceA.accountDao.getByIdOneShot("acc_sc_3")!!.isHistoryOnlySubscriber)
    }

    /**
     * Test 2.3: Arbitrary Event Interleaving and Permuted Arrival Order
     * Events from Gen1, Gen2, and Gen3 are delivered to Device A in reversed order:
     * Gen3 arrives first, then Gen2, then Gen1.
     * Final state converges correctly: Gen1 and Gen2 are history-only, Gen3 is active, zero pollution.
     */
    @Test
    fun testThreeDevice_reversedEventDeliveryOrder_convergesDeterministically() = runBlocking {
        val pppoe = "permuted_order_user"

        // Populate cloud with Gen1, Gen2, Gen3 directly
        val ts1 = cloudBackend.nextTimestamp()
        cloudBackend.recordDirectAccount(
            LocalAccount(id = "acc_rev_1", displayName = "Rev Gen1", earthlinkUsername = pppoe, isHistoryOnlySubscriber = true, debtIqd = 50000.0),
            serverVersion = ts1
        )
        cloudBackend.recordDirectLedger(
            LocalLedgerEntry(id = "tx_rev_1", accountId = "acc_rev_1", typeRaw = "took", amountIqd = 50000.0, debtAfterIqd = 50000.0, occurredAt = ts1),
            serverVersion = ts1 + 100
        )

        val ts2 = cloudBackend.nextTimestamp()
        cloudBackend.recordDirectAccount(
            LocalAccount(id = "acc_rev_2", displayName = "Rev Gen2", earthlinkUsername = pppoe, isHistoryOnlySubscriber = true, debtIqd = 35000.0),
            serverVersion = ts2
        )
        cloudBackend.recordDirectLedger(
            LocalLedgerEntry(id = "tx_rev_2", accountId = "acc_rev_2", typeRaw = "took", amountIqd = 35000.0, debtAfterIqd = 35000.0, occurredAt = ts2),
            serverVersion = ts2 + 100
        )

        val ts3 = cloudBackend.nextTimestamp()
        cloudBackend.recordDirectAccount(
            LocalAccount(id = "acc_rev_3", displayName = "Rev Gen3", earthlinkUsername = pppoe, isHistoryOnlySubscriber = false, debtIqd = 20000.0),
            serverVersion = ts3
        )
        cloudBackend.recordDirectLedger(
            LocalLedgerEntry(id = "tx_rev_3", accountId = "acc_rev_3", typeRaw = "took", amountIqd = 20000.0, debtAfterIqd = 20000.0, occurredAt = ts3),
            serverVersion = ts3 + 100
        )

        // Deliver to Device A in REVERSED order: Gen3 -> Gen2 -> Gen1
        val allDocsReversed = cloudBackend.getAllDocuments().reversed()
        deviceA.applyRawCloudDocs(allDocsReversed)

        // Assert convergence despite reversed arrival order
        val allPersisted = deviceA.accountDao.getAllPersistedOneShot()
        assertEquals(3, allPersisted.size)

        val active = deviceA.accountDao.getAllOneShot()
        assertEquals(1, active.size)
        assertEquals("acc_rev_3", active[0].id)

        val activeByUsername = deviceA.accountDao.findActiveAccountByUsernameOrIdOneShot(pppoe)
        assertNotNull(activeByUsername)
        assertEquals("acc_rev_3", activeByUsername!!.id)

        assertEquals(50000.0, deviceA.accountDao.getByIdOneShot("acc_rev_1")!!.debtIqd, 0.001)
        assertEquals(35000.0, deviceA.accountDao.getByIdOneShot("acc_rev_2")!!.debtIqd, 0.001)
        assertEquals(20000.0, deviceA.accountDao.getByIdOneShot("acc_rev_3")!!.debtIqd, 0.001)

        assertTrue(deviceA.accountDao.getByIdOneShot("acc_rev_1")!!.isHistoryOnlySubscriber)
        assertTrue(deviceA.accountDao.getByIdOneShot("acc_rev_2")!!.isHistoryOnlySubscriber)
        assertFalse(deviceA.accountDao.getByIdOneShot("acc_rev_3")!!.isHistoryOnlySubscriber)
    }

    /**
     * R6 Verification: Authoritative ISP Disappearance Reconciliation & Cross-Device Monotonic Convergence.
     *
     * Core Triad:
     * 1. Claim: INV-02 / INV-06 / INV-12:
     *    - Device A performs a verified complete ISP fetch where subscriber is absent, transitioning it to history-only.
     *    - Device B has an incomplete/failed fetch; it refuses to infer disappearance locally (INV-12).
     *    - When Device A pushes to cloud and Device B pulls, Device B converges monotonically to history-only.
     *    - Financial debt and ledger history remain completely preserved (INV-01 / INV-02).
     * 2. Seam: ROBOLECTRIC multi-device isolated Room + simulated Firestore outbox sync.
     * 3. Independent Oracle:
     *    - Device B local reconciliation returns 0 transitions.
     *    - Device A local reconciliation transitions exactly [acc_shared_disp_1].
     *    - Post-sync Device B has isHistoryOnlySubscriber = true, debt = 30000.0 IQD, active lookup = null.
     */
    @Test
    fun testTwoDevice_ispDisappearanceReconciliation_propagatesMonotonicallyAcrossIncompletePeer() = runBlocking {
        val subscriberUsername = "user_disappearing_1"
        val accountId = "acc_shared_disp_1"
        val initialAccount = LocalAccount(
            id = accountId,
            displayName = "Disappearing User",
            earthlinkUsername = subscriberUsername,
            debtIqd = 30000.0,
            isHistoryOnlySubscriber = false,
            updatedAt = 1000L
        )

        // Pre-populate both isolated devices
        deviceA.accountDao.insert(initialAccount)
        deviceB.accountDao.insert(initialAccount)

        // Device B has an INCOMPLETE or FAILED ISP fetch (e.g. network timeout or partial payload)
        // Device B calls reconcile with isFetchComplete = false -> MUST ABORT WITHOUT TRANSITIONS
        val deviceBTransitions = deviceB.accountRepository.reconcileIspDisappearance(
            authoritativeIspUserIds = emptySet(),
            isFetchComplete = false
        )
        assertTrue("Incomplete fetch on Device B must not infer disappearance", deviceBTransitions.isEmpty())
        val deviceBAccBefore = deviceB.accountDao.getByIdOneShot(accountId)
        assertNotNull(deviceBAccBefore)
        assertFalse("Account on Device B must remain active after incomplete fetch", deviceBAccBefore!!.isHistoryOnlySubscriber)

        // Device A has a COMPLETE authoritative ISP fetch where user_disappearing_1 is absent
        val authoritativeIspAtA = setOf("other_active_user")
        val deviceATransitions = deviceA.accountRepository.reconcileIspDisappearance(
            authoritativeIspUserIds = authoritativeIspAtA,
            isFetchComplete = true
        )
        assertEquals(listOf(accountId), deviceATransitions)
        val deviceAAccAfter = deviceA.accountDao.getByIdOneShot(accountId)
        assertNotNull(deviceAAccAfter)
        assertTrue("Account on Device A must transition to history-only", deviceAAccAfter!!.isHistoryOnlySubscriber)

        // Device A pushes outbox mutation to simulated cloud
        val pushedFromA = deviceA.pushToCloud()
        assertTrue("Device A must push outbox mutation", pushedFromA > 0)

        // Device B pulls updates from simulated cloud
        val pulledByB = deviceB.pullFromCloud()
        assertTrue("Device B must pull updates", pulledByB > 0)

        // Device B converges monotonically upon receiving Device A's remote update
        val deviceBAccAfter = deviceB.accountDao.getByIdOneShot(accountId)
        assertNotNull(deviceBAccAfter)
        assertTrue("Device B must monotonically converge to history-only from cloud sync", deviceBAccAfter!!.isHistoryOnlySubscriber)
        assertEquals(30000.0, deviceBAccAfter.debtIqd, 0.001)

        // Active lookup on Device B now excludes the transitioned account
        val activeB = deviceB.accountDao.findActiveAccountByUsernameOrIdOneShot(subscriberUsername)
        assertNull("Active lookup must exclude history-only account on Device B", activeB)

        // Historical lookup still finds the account intact
        val historicalB = deviceB.accountDao.getByIdOneShot(accountId)
        assertNotNull(historicalB)
        assertEquals("Disappearing User", historicalB!!.displayName)
    }
}

// =================================================================================================
// SIMULATION INFRASTRUCTURE COMPONENTS
// =================================================================================================

/**
 * Models an isolated physical Android device with independent SQLite Room database,
 * outbox queue, sync coordinator, and local repositories.
 */
class SimulatedIsolatedDevice(
    val name: String,
    val context: Context,
    val cloud: SimulatedFirestoreCloudBackend
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

    suspend fun pushToCloud(): Int {
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
        return applyRawCloudDocs(updates)
    }

    suspend fun applyRawCloudDocs(docs: List<SimulatedFirestoreCloudBackend.CloudDoc>): Int {
        var appliedCount = 0
        var maxVersion = metadataDao.get("last_sync_timestamp")?.toLongOrNull() ?: 0L

        for (cloudDoc in docs) {
            val event = cloud.toRemoteEvent(cloudDoc, RemoteEventSource.PULL, accountDao)
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
        if (maxVersion > (metadataDao.get("last_sync_timestamp")?.toLongOrNull() ?: 0L)) {
            metadataDao.put("last_sync_timestamp", maxVersion.toString())
        }
        return appliedCount
    }

    fun close() {
        db.close()
    }
}

/**
 * Models the authoritative Cloud Firestore backend for users/{uid}/local_accounts and
 * users/{uid}/local_ledger_entries. Documents are strictly keyed by (entityType, entityId).
 */
class SimulatedFirestoreCloudBackend {
    private val clock = AtomicLong(1720000000000L)

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

    fun nextTimestamp(): Long = clock.addAndGet(1000L)

    fun recordMutation(
        entityType: String,
        entityId: String,
        operation: String,
        payloadJson: String,
        sourceDevice: String
    ): Long {
        val serverTs = nextTimestamp()
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
        // Keyed strictly by (entityType, entityId) -> Firestore document path identity
        documents[Pair(entityType, entityId)] = doc
        return serverTs
    }

    fun recordDirectAccount(account: LocalAccount, serverVersion: Long) {
        val json = JSONObject().apply {
            put("id", account.id)
            put("displayName", account.displayName)
            put("earthlinkUsername", account.earthlinkUsername)
            put("debtIqd", account.debtIqd)
            put("advanceIqd", account.advanceIqd)
            put("currentPriceIqd", account.currentPriceIqd)
            put("isHistoryOnlySubscriber", account.isHistoryOnlySubscriber)
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

    fun getAllDocuments(): List<CloudDoc> {
        return documents.values.sortedBy { it.serverVersion }
    }

    fun getDocumentsForCollection(collectionName: String): List<CloudDoc> {
        return documents.values.filter { it.entityType == collectionName }
    }

    fun getAccount(accountId: String): LocalAccount? {
        val doc = documents[Pair("local_accounts", accountId)] ?: return null
        if (doc.operation == "delete" || doc.deletedAt != null) return null
        return try {
            val json = JSONObject(doc.payloadJson)
            val displayName = if (json.has("displayName")) json.getString("displayName") else doc.entityId
            val earthlinkUser = if (json.has("earthlinkUsername") && !json.isNull("earthlinkUsername")) json.getString("earthlinkUsername") else null
            val isHistoryOnly = json.optBoolean("isHistoryOnlySubscriber", false)
            LocalAccount(
                id = doc.entityId,
                displayName = displayName,
                earthlinkUsername = earthlinkUser,
                debtIqd = json.optDouble("debtIqd", 0.0),
                advanceIqd = json.optDouble("advanceIqd", 0.0),
                currentPriceIqd = json.optDouble("currentPriceIqd", 35000.0),
                isHistoryOnlySubscriber = isHistoryOnly,
                updatedAt = doc.serverVersion
            )
        } catch (_: Exception) { null }
    }

    fun toRemoteEvent(
        doc: CloudDoc,
        source: RemoteEventSource,
        accountDao: com.example.core.database.LocalAccountDao
    ): RemoteEvent? {
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
                    val earthlinkUser = if (json != null && json.has("earthlinkUsername") && !json.isNull("earthlinkUsername")) json.getString("earthlinkUsername") else null
                    val isHistoryOnly = json?.optBoolean("isHistoryOnlySubscriber", false) ?: false
                    val externalId = if (json != null && json.has("sourceExternalId") && !json.isNull("sourceExternalId")) json.getString("sourceExternalId") else null

                    val acc = LocalAccount(
                        id = doc.entityId,
                        displayName = displayName,
                        earthlinkUsername = earthlinkUser,
                        debtIqd = json?.optDouble("debtIqd", 0.0) ?: 0.0,
                        advanceIqd = json?.optDouble("advanceIqd", 0.0) ?: 0.0,
                        currentPriceIqd = json?.optDouble("currentPriceIqd", 35000.0) ?: 35000.0,
                        isHistoryOnlySubscriber = isHistoryOnly,
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
                    val preFetchedParent = if (accountId.isNotBlank()) getAccount(accountId) else null

                    val entry = LocalLedgerEntry(
                        id = doc.entityId,
                        accountId = accountId,
                        typeRaw = typeRaw,
                        amountIqd = amountIqd,
                        debtAfterIqd = debtAfterIqd,
                        occurredAt = occurredAt,
                        note = note,
                        sourceExternalId = externalId,
                        createdAt = doc.serverVersion
                    )
                    RemoteEvent.LedgerUpsert(
                        entityId = doc.entityId,
                        remoteVersion = doc.serverVersion,
                        source = source,
                        entry = entry,
                        preFetchedParentAccount = preFetchedParent,
                        syncMutationId = doc.syncMutationId
                    )
                }
            }
            else -> null
        }
    }
}
