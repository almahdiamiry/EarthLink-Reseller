package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.PendingExternalOperation
import com.example.core.model.SasProviders
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification suite for provider logout lifecycle & account reconciliation.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - On provider logout, zero-debt accounts with no ledger entries and no active pending operations are purged.
 *    - Accounts with debt > 0, historical ledger records, or in-flight pending operations are archived as history-only.
 *    - Accounts belonging to other providers are completely untouched.
 *    - When an archived account reappears in the provider's authoritative roster, it transitions back
 *      to active (isHistoryOnlySubscriber = false).
 * 2. Seam:
 *    - ROBOLECTRIC tier with in-memory Room database.
 * 3. Oracle:
 *    - Exact literal assertions on persisted entities, counts, and history flags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProviderLogoutReconciliationTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun handleLogout_purgesZeroDebtWithoutLedger_andArchivesDebtOrPendingBearing() = runTest {
        val accountDao = database.localAccountDao()
        val ledgerDao = database.localLedgerEntryDao()
        val pendingDao = database.pendingExternalOperationDao()

        // 1. Zero debt, no ledger, no pending -> Purged
        accountDao.insert(
            LocalAccount(
                id = "acc_zero_debt",
                displayName = "Zero Debt User",
                earthlinkUsername = "zero_user",
                operationProvider = SasProviders.EARTHLINK,
                isHistoryOnlySubscriber = false,
                debtIqd = 0.0,
                advanceIqd = 0.0
            )
        )

        // 2. Debt > 0 -> Archived to history-only
        accountDao.insert(
            LocalAccount(
                id = "acc_with_debt",
                displayName = "Indebted User",
                earthlinkUsername = "debt_user",
                operationProvider = SasProviders.EARTHLINK,
                isHistoryOnlySubscriber = false,
                debtIqd = 25000.0,
                advanceIqd = 0.0
            )
        )

        // 3. Debt = 0, but has historical ledger entry -> Preserved as history-only (RED Invariant #2)
        accountDao.insert(
            LocalAccount(
                id = "acc_with_ledger",
                displayName = "Paid User",
                earthlinkUsername = "paid_user",
                operationProvider = SasProviders.EARTHLINK,
                isHistoryOnlySubscriber = false,
                debtIqd = 0.0,
                advanceIqd = 0.0
            )
        )
        ledgerDao.insert(
            LocalLedgerEntry(
                id = "tx_paid",
                accountId = "acc_with_ledger",
                amountIqd = 15000.0,
                debtAfterIqd = 0.0,
                typeRaw = "PAYMENT",
                occurredAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis()
            )
        )

        // 4. Zero debt, no ledger, but HAS in-flight pending operation -> Preserved as history-only (Invariant #3/#4)
        accountDao.insert(
            LocalAccount(
                id = "acc_with_pending",
                displayName = "Pending Op User",
                earthlinkUsername = "pending_user",
                operationProvider = SasProviders.EARTHLINK,
                isHistoryOnlySubscriber = false,
                debtIqd = 0.0,
                advanceIqd = 0.0
            )
        )
        pendingDao.insert(
            PendingExternalOperation(
                accountId = "acc_with_pending",
                operationType = "RENEWAL",
                amountIqd = 35000L,
                status = "PENDING"
            )
        )

        // 5. SAMM account -> Untouched during EarthLink logout
        accountDao.insert(
            LocalAccount(
                id = "acc_samm",
                displayName = "SAMM User",
                earthlinkUsername = "samm_user",
                operationProvider = SasProviders.ALAMIRY,
                isHistoryOnlySubscriber = false,
                debtIqd = 0.0,
                advanceIqd = 0.0
            )
        )

        val (deleted, archived) = IspDisappearanceReconciler.handleLogout(
            database = database,
            accountDao = accountDao,
            auditDao = database.auditLogDao(),
            loggedOutProvider = SasProviders.EARTHLINK
        )

        assertEquals("Expected 1 account deleted", 1, deleted)
        assertEquals("Expected 3 accounts archived", 3, archived)

        // Verify account 1 is purged
        assertNull("Zero debt account without ledger must be deleted", accountDao.getByIdOneShot("acc_zero_debt"))

        // Verify account 2 is archived
        val debtAcc = accountDao.getByIdOneShot("acc_with_debt")
        assertNotNull(debtAcc)
        assertTrue("Indebted account must be history-only", debtAcc!!.isHistoryOnlySubscriber)

        // Verify account 3 is archived
        val ledgerAcc = accountDao.getByIdOneShot("acc_with_ledger")
        assertNotNull(ledgerAcc)
        assertTrue("Account with ledger must be history-only", ledgerAcc!!.isHistoryOnlySubscriber)

        // Verify account 4 is archived due to active in-flight operation
        val pendingAcc = accountDao.getByIdOneShot("acc_with_pending")
        assertNotNull(pendingAcc)
        assertTrue("Account with pending operation must be history-only", pendingAcc!!.isHistoryOnlySubscriber)

        // Verify account 5 is unaffected
        val sammAcc = accountDao.getByIdOneShot("acc_samm")
        assertNotNull(sammAcc)
        assertFalse("SAMM account must remain active", sammAcc!!.isHistoryOnlySubscriber)
    }

    @Test
    fun reconcile_restoresHistoryOnlyAccountWhenReappearingInAuthoritativeIsp() = runTest {
        val accountDao = database.localAccountDao()

        // Seed an account that was previously marked history-only
        accountDao.insert(
            LocalAccount(
                id = "acc_archived",
                displayName = "Returning User",
                earthlinkUsername = "returning_user",
                operationProvider = SasProviders.EARTHLINK,
                isHistoryOnlySubscriber = true,
                debtIqd = 10000.0,
                advanceIqd = 0.0
            )
        )

        // Provider sync returns this username in authoritative list
        IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = accountDao,
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = setOf("returning_user"),
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK
        )

        val restoredAcc = accountDao.getByIdOneShot("acc_archived")
        assertNotNull(restoredAcc)
        assertFalse("Reappearing subscriber must be restored to active", restoredAcc!!.isHistoryOnlySubscriber)
    }
}
