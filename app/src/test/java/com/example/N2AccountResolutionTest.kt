package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Core Triad:
 * Claim: INV-01 / INV-05 / Task 5: Authoritative ISP identity binding and multi-container
 *        resolution safely maps ISP subscriber identity (ispUserIndex, ispSubscriberId)
 *        to physical LocalAccount containers, returning unordered peer sets without winner heuristics,
 *        protecting against username recycling cross-routing, preventing cross-container propagation,
 *        enforcing identity conflict rejection, and maintaining physical operation anchoring and financial immutability.
 * Seam / Environment: ROBOLECTRIC (in-memory Room DB v18 + LocalAccountDao + LocalAccountRepository).
 * Independent Oracle: Literal expected physical container IDs, explicit multi-container list equality,
 *                      conflict exceptions, and financial ledger preservation oracles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class N2AccountResolutionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var accountRepository: LocalAccountRepository
    private lateinit var ledgerRepository: LocalLedgerRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()
        accountRepository = LocalAccountRepositoryImpl(
            database = db,
            accountDao = accountDao,
            outboxDao = outboxDao
        )
        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = outboxDao,
            pendingDao = db.pendingExternalOperationDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // TEST A — Single active container
    // -------------------------------------------------------------------------
    @Test
    fun testA_singleActiveContainerResolution() = runBlocking {
        val accA = LocalAccount(
            id = "UUID_A",
            displayName = "Alice A",
            earthlinkUsername = "alice",
            ispUserIndex = 1001,
            ispSubscriberId = "earthlink:1001"
        )
        accountDao.insert(accA)

        val resolved = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 1001, username = "alice")
        assertEquals("Must resolve exactly 1 container", 1, resolved.size)
        assertEquals("Resolved container must be UUID_A", "UUID_A", resolved[0].id)
    }

    // -------------------------------------------------------------------------
    // TEST B — Multiple active siblings (peer set, no winner heuristic)
    // -------------------------------------------------------------------------
    @Test
    fun testB_multipleActiveSiblingsResolution() = runBlocking {
        val accA = LocalAccount(
            id = "UUID_A",
            displayName = "Alice Container 1",
            earthlinkUsername = "alice",
            ispUserIndex = 1001
        )
        val accB = LocalAccount(
            id = "UUID_B",
            displayName = "Alice Container 2",
            earthlinkUsername = "alice",
            ispUserIndex = 1001
        )
        accountDao.insert(accA)
        accountDao.insert(accB)

        val resolved = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 1001, username = "alice")
        assertEquals("Must resolve all active sibling containers", 2, resolved.size)
        val resolvedIds = resolved.map { it.id }.toSet()
        assertEquals("Peer set must contain both containers without winner ranking", setOf("UUID_A", "UUID_B"), resolvedIds)
    }

    // -------------------------------------------------------------------------
    // TEST C — Same username, different authoritative identities
    // -------------------------------------------------------------------------
    @Test
    fun testC_sameUsernameDifferentAuthoritativeIdentities() = runBlocking {
        val accA = LocalAccount(
            id = "UUID_A",
            displayName = "Alice Old",
            earthlinkUsername = "alice",
            ispUserIndex = 1001
        )
        val accB = LocalAccount(
            id = "UUID_B",
            displayName = "Alice New",
            earthlinkUsername = "alice",
            ispUserIndex = 2002
        )
        accountDao.insert(accA)
        accountDao.insert(accB)

        val resolved1001 = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 1001, username = "alice")
        assertEquals("1001 must resolve only UUID_A", 1, resolved1001.size)
        assertEquals("UUID_A", resolved1001[0].id)

        val resolved2002 = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 2002, username = "alice")
        assertEquals("2002 must resolve only UUID_B", 1, resolved2002.size)
        assertEquals("UUID_B", resolved2002[0].id)
    }

    // -------------------------------------------------------------------------
    // TEST D — Username recycling protection
    // -------------------------------------------------------------------------
    @Test
    fun testD_usernameRecyclingDoesNotCrossRoute() = runBlocking {
        // Subscriber A was previously bound to 1001 with username 'recycled_user'
        val accA = LocalAccount(
            id = "UUID_A",
            displayName = "Subscriber A",
            earthlinkUsername = "recycled_user",
            ispUserIndex = 1001
        )
        accountDao.insert(accA)

        // ISP assigns username 'recycled_user' to new subscriber B with userIndex 2002.
        // Local DB does not yet have an account with ispUserIndex = 2002.
        // Lookup for 2002 with username 'recycled_user' MUST NOT return accA!
        val resolved2002 = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 2002, username = "recycled_user")
        assertTrue("Recycled username must NOT cross-route to container bound to different ISP identity", resolved2002.isEmpty())

        // Lookup for 1001 must still resolve accA
        val resolved1001 = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 1001, username = "recycled_user")
        assertEquals(1, resolved1001.size)
        assertEquals("UUID_A", resolved1001[0].id)
    }

    // -------------------------------------------------------------------------
    // TEST E — Active + history-only boundary
    // -------------------------------------------------------------------------
    @Test
    fun testE_activePlusHistoryOnlyBoundary() = runBlocking {
        val accActive = LocalAccount(
            id = "UUID_ACT",
            displayName = "Active Subscriber",
            earthlinkUsername = "user_x",
            ispUserIndex = 1001,
            isHistoryOnlySubscriber = false
        )
        val accHistory = LocalAccount(
            id = "UUID_HIST",
            displayName = "History Subscriber",
            earthlinkUsername = "user_x",
            ispUserIndex = 1001,
            isHistoryOnlySubscriber = true
        )
        accountDao.insert(accActive)
        accountDao.insert(accHistory)

        val resolved = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 1001, username = "user_x")
        assertEquals("Active operational resolution must return only active containers", 1, resolved.size)
        assertEquals("UUID_ACT", resolved[0].id)
        assertFalse("History-only container must not be returned in active resolution", resolved[0].isHistoryOnlySubscriber)
    }

    // -------------------------------------------------------------------------
    // TEST F — Missing authoritative identity (fallback to username)
    // -------------------------------------------------------------------------
    @Test
    fun testF_missingAuthoritativeIdentity_fallbackToUsername() = runBlocking {
        val accA = LocalAccount(
            id = "UUID_A",
            displayName = "Alice Offline",
            earthlinkUsername = "alice",
            ispUserIndex = null
        )
        accountDao.insert(accA)

        // When userIndex is null, resolution falls back to username safely
        val resolved = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = null, username = "alice")
        assertEquals("Must resolve UUID_A via username fallback", 1, resolved.size)
        assertEquals("UUID_A", resolved[0].id)
    }

    // -------------------------------------------------------------------------
    // TEST G — Conflicting local identity (refuses silent overwrite)
    // -------------------------------------------------------------------------
    @Test
    fun testG_conflictingLocalIdentity_refusesOverwrite() = runBlocking {
        val accA = LocalAccount(
            id = "UUID_A",
            displayName = "Alice",
            earthlinkUsername = "alice",
            ispUserIndex = 1001
        )
        accountDao.insert(accA)

        // Attempting to bind a conflicting userIndex (2002) to an account already bound to 1001
        // must throw IllegalStateException and NOT silently overwrite.
        var conflictCaught = false
        try {
            accountRepository.bindIspIdentity(accountId = "UUID_A", userIndex = 2002)
        } catch (e: IllegalStateException) {
            conflictCaught = true
            assertTrue("Exception message must describe identity conflict", e.message?.contains("Identity conflict") == true)
        }

        assertTrue("Conflicting identity bind must be rejected", conflictCaught)

        // Verify account in DB remains bound to 1001
        val check = accountDao.getByIdOneShot("UUID_A")
        assertNotNull(check)
        assertEquals("Original ispUserIndex must be preserved", 1001, check!!.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST H — Zero match (no synthetic account created)
    // -------------------------------------------------------------------------
    @Test
    fun testH_zeroMatch_noSyntheticAccount() = runBlocking {
        val initialCount = accountDao.getTotalCount()

        val resolved = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 9999, username = "nonexistent")
        assertTrue("Nonexistent subscriber must return empty list", resolved.isEmpty())

        assertEquals("No synthetic account must be created in database", initialCount, accountDao.getTotalCount())
    }

    // -------------------------------------------------------------------------
    // TEST I — Repeated resolution is idempotent
    // -------------------------------------------------------------------------
    @Test
    fun testI_repeatedResolutionIdempotence() = runBlocking {
        val accA = LocalAccount(id = "UUID_A", displayName = "Alice", ispUserIndex = 1001)
        accountDao.insert(accA)

        val res1 = accountRepository.findActiveAccountsBySubscriberIdentity(1001, "alice")
        val res2 = accountRepository.findActiveAccountsBySubscriberIdentity(1001, "alice")
        val res3 = accountRepository.findActiveAccountsBySubscriberIdentity(1001, "alice")

        assertEquals(1, res1.size)
        assertEquals(1, res2.size)
        assertEquals(1, res3.size)
        assertEquals("UUID_A", res1[0].id)
        assertEquals("UUID_A", res2[0].id)
        assertEquals("UUID_A", res3[0].id)
        assertEquals(1, accountDao.getTotalCount())
        assertEquals(0, ledgerDao.getTotalCount())
    }

    // -------------------------------------------------------------------------
    // TEST J — Cross-container propagation trap
    // -------------------------------------------------------------------------
    @Test
    fun testJ_crossContainerPropagationBlocked() = runBlocking {
        val accA = LocalAccount(
            id = "UUID_A",
            displayName = "Alice Container A",
            earthlinkUsername = "alice",
            ispUserIndex = 1001
        )
        val accB = LocalAccount(
            id = "UUID_B",
            displayName = "Alice Container B",
            earthlinkUsername = "alice",
            ispUserIndex = null
        )
        accountDao.insert(accA)
        accountDao.insert(accB)

        // Gateway returns 1001 for alice. Authoritative lookup for 1001 returns [A].
        val resolved = accountRepository.findActiveAccountsBySubscriberIdentity(userIndex = 1001, username = "alice")
        assertEquals(1, resolved.size)
        assertEquals("UUID_A", resolved[0].id)

        // Container B must NOT automatically inherit 1001 without authoritative binding targeted at B
        val checkB = accountDao.getByIdOneShot("UUID_B")
        assertNotNull(checkB)
        assertNull("Container B must not have its ispUserIndex silently mutated", checkB!!.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST K — Null / absent preservation in saveAccount
    // -------------------------------------------------------------------------
    @Test
    fun testK_nullAbsentPreservationInSaveAccount() = runBlocking {
        val accA = LocalAccount(
            id = "UUID_A",
            displayName = "Alice",
            earthlinkUsername = "alice",
            ispUserIndex = 1001,
            ispSubscriberId = "earthlink:1001"
        )
        accountDao.insert(accA)

        // A caller performs an update where ispUserIndex and ispSubscriberId are null
        val updatePayload = accA.copy(
            displayName = "Alice Updated Name",
            ispUserIndex = null,
            ispSubscriberId = null
        )
        val saved = accountRepository.saveAccount(updatePayload)

        assertEquals("DisplayName must be updated", "Alice Updated Name", saved.displayName)
        assertEquals("ispUserIndex must NOT be cleared by null/absent field", 1001, saved.ispUserIndex)
        assertEquals("ispSubscriberId must NOT be cleared by null/absent field", "earthlink:1001", saved.ispSubscriberId)

        val checkDb = accountDao.getByIdOneShot("UUID_A")!!
        assertEquals(1001, checkDb.ispUserIndex)
        assertEquals("earthlink:1001", checkDb.ispSubscriberId)
    }

    // -------------------------------------------------------------------------
    // TEST L — Physical operation anchor preserved
    // -------------------------------------------------------------------------
    @Test
    fun testL_physicalOperationAnchorPreserved() = runBlocking {
        val accA = LocalAccount(id = "UUID_A", displayName = "Container A", ispUserIndex = 1001, debtIqd = 0.0)
        val accB = LocalAccount(id = "UUID_B", displayName = "Container B", ispUserIndex = 1001, debtIqd = 0.0)
        accountDao.insert(accA)
        accountDao.insert(accB)

        // Operation is explicitly anchored to container B
        val txId = "tx_renewal_B"
        ledgerRepository.recordAccountRenewal(
            account = accB,
            newPriceIqd = 35000.0,
            chargeNote = "Renewal for container B",
            payNote = null,
            idempotencyKey = txId
        )

        val ledgersA = ledgerDao.getByAccountIdOneShot("UUID_A")
        val ledgersB = ledgerDao.getByAccountIdOneShot("UUID_B")

        assertTrue("Container A must NOT receive the ledger transaction", ledgersA.isEmpty())
        assertEquals("Container B must receive the transaction", 1, ledgersB.size)
        assertEquals("Ledger accountId must strictly anchor to UUID_B", "UUID_B", ledgersB[0].accountId)
        assertEquals(35000.0, ledgersB[0].amountIqd, 0.001)
    }

    // -------------------------------------------------------------------------
    // TEST M — Stale / out-of-order response cannot overwrite bound identity
    // -------------------------------------------------------------------------
    @Test
    fun testM_staleResponseCannotOverwriteBoundIdentity() = runBlocking {
        val accA = LocalAccount(id = "UUID_A", displayName = "Alice", ispUserIndex = 1001)
        accountDao.insert(accA)

        // A stale response with an older userIndex (999) arrives
        try {
            accountRepository.bindIspIdentity(accountId = "UUID_A", userIndex = 999)
            fail("Should reject conflicting stale binding")
        } catch (e: IllegalStateException) {
            // Expected
        }

        val check = accountDao.getByIdOneShot("UUID_A")!!
        assertEquals("Authoritative bound identity must survive stale response", 1001, check.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST N — Concurrent binding safety
    // -------------------------------------------------------------------------
    @Test
    fun testN_concurrentBindingSafety() = runBlocking(Dispatchers.IO) {
        val accA = LocalAccount(id = "UUID_CONCURRENT", displayName = "Concurrent User", ispUserIndex = null)
        accountDao.insert(accA)

        // Launch 10 concurrent binding attempts with userIndex = 1001
        val jobs = (1..10).map {
            async {
                accountRepository.bindIspIdentity(accountId = "UUID_CONCURRENT", userIndex = 1001)
            }
        }
        val results = jobs.awaitAll()
        assertEquals(10, results.size)
        results.forEach {
            assertEquals("UUID_CONCURRENT", it.id)
            assertEquals(1001, it.ispUserIndex)
        }

        val checkDb = accountDao.getByIdOneShot("UUID_CONCURRENT")!!
        assertEquals(1001, checkDb.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // FINANCIAL PRESERVATION TEST
    // -------------------------------------------------------------------------
    @Test
    fun testFinancialPreservationAcrossIdentityBinding() = runBlocking {
        val acc = LocalAccount(
            id = "UUID_FIN",
            displayName = "Financial User",
            ispUserIndex = null,
            debtIqd = 50000.0,
            openingDebtIqd = 25000.0
        )
        accountDao.insert(acc)

        val tx = LocalLedgerEntry(
            id = "TX_001",
            accountId = "UUID_FIN",
            typeRaw = "renewal",
            amountIqd = 25000.0,
            debtAfterIqd = 50000.0,
            occurredAt = 1700000000000L
        )
        ledgerDao.insert(tx)

        // Bind identity
        val bound = accountRepository.bindIspIdentity(accountId = "UUID_FIN", userIndex = 1001, ispSubscriberId = "earthlink:1001")

        // Financial preservation assertions
        assertEquals("Account ID must not change", "UUID_FIN", bound.id)
        assertEquals("Debt must not change", 50000.0, bound.debtIqd, 0.001)
        assertEquals("Opening debt must not change", 25000.0, bound.openingDebtIqd, 0.001)

        val checkLedger = ledgerDao.getByIdOneShot("TX_001")
        assertNotNull(checkLedger)
        assertEquals("Ledger accountId must remain untouched", "UUID_FIN", checkLedger!!.accountId)
        assertEquals("Ledger amount must remain untouched", 25000.0, checkLedger.amountIqd, 0.001)
        assertEquals("Ledger row count must remain exactly 1", 1, ledgerDao.getTotalCount())
    }
}
