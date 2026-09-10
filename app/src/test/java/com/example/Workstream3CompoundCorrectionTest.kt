package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Claim: WS3 / FIN-02 — Successive compound corrections targeting the same root transaction
 * must compute deltas against effective current position (root + prior corrections),
 * not against the root transaction amount alone.
 * Seam: Robolectric in-memory SQLite repository execution.
 * Independent Oracle: Invariant RED-1 (Additive ledger math from baseline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class Workstream3CompoundCorrectionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var dbFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_ws3_${UUID.randomUUID()}.db")
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
    }

    @After
    fun tearDown() {
        db.close()
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun testTwoSequentialCorrectionsYieldExactCumulativeIntendedBalance() = runBlocking {
        val accountId = "acc_corr_test"
        val account = LocalAccount(
            id = accountId,
            displayName = "Correction Test Subscriber",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            loanIqd = 0.0
        )
        accountRepo.saveAccount(account)

        // 1. Initial transaction: Charge 25,000 IQD debt
        val initialEntry = ledgerRepo.addDebt(accountId, 25000.0, note = "Initial charge")
        assertNotNull(initialEntry)

        var currentAcc = accountRepo.getAccountByIdOneShot(accountId)
        assertEquals(25000.0, currentAcc?.debtIqd ?: 0.0, 0.001)

        // 2. First correction: correct from 25,000 to 20,000 IQD
        val corr1 = ledgerRepo.correctTransaction(initialEntry!!.id, intendedAmount = 20000.0, note = "Correction 1")
        assertNotNull(corr1)
        currentAcc = accountRepo.getAccountByIdOneShot(accountId)
        assertEquals("After first correction, debt must be 20,000 IQD", 20000.0, currentAcc?.debtIqd ?: 0.0, 0.001)

        // 3. Second correction: correct from 20,000 to 15,000 IQD
        val corr2 = ledgerRepo.correctTransaction(initialEntry.id, intendedAmount = 15000.0, note = "Correction 2")
        assertNotNull(corr2)

        // With bug: diff was computed as 15,000 - 25,000 = -10,000, subtracting another 10,000 -> debt becomes 10,000!
        // With fix: diff is computed as 15,000 - 20,000 = -5,000 -> debt becomes 15,000!
        currentAcc = accountRepo.getAccountByIdOneShot(accountId)
        assertEquals("After second correction, cumulative debt must be exactly 15,000 IQD", 15000.0, currentAcc?.debtIqd ?: 0.0, 0.001)

        // 4. Third correction: correct back from 15,000 to 20,000 IQD (alternating/repeated intended amount)
        val corr3 = ledgerRepo.correctTransaction(initialEntry.id, intendedAmount = 20000.0, note = "Correction 3 back to 20k")
        assertNotNull(corr3)
        currentAcc = accountRepo.getAccountByIdOneShot(accountId)
        assertEquals("After third correction, debt must be restored to 20,000 IQD", 20000.0, currentAcc?.debtIqd ?: 0.0, 0.001)

        // 5. Idempotent retry: correcting again to 20,000 IQD must return corr3 without extra mutations
        val corr3Retry = ledgerRepo.correctTransaction(initialEntry.id, intendedAmount = 20000.0, note = "Correction 3 back to 20k")
        assertEquals("Retry must return identical correction entry ID", corr3.id, corr3Retry.id)
        currentAcc = accountRepo.getAccountByIdOneShot(accountId)
        assertEquals("Debt remains 20,000 IQD after idempotent retry", 20000.0, currentAcc?.debtIqd ?: 0.0, 0.001)
    }

    @Test
    fun testReversalAndReCorrection() = runBlocking {
        val accountId = "acc_reversal_recorr"
        val account = LocalAccount(
            id = accountId,
            displayName = "Reversal Test Subscriber",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            loanIqd = 0.0
        )
        accountRepo.saveAccount(account)

        val initialEntry = ledgerRepo.addDebt(accountId, 25000.0, note = "Initial charge")
        assertNotNull(initialEntry)

        // Full reversal: intended = 0.0
        val rev = ledgerRepo.correctTransaction(initialEntry!!.id, intendedAmount = 0.0, note = "Full reversal")
        assertNotNull(rev)
        var currentAcc = accountRepo.getAccountByIdOneShot(accountId)
        assertEquals(0.0, currentAcc?.debtIqd ?: 0.0, 0.001)

        // Re-correction: intended = 10000.0
        val reCorr = ledgerRepo.correctTransaction(initialEntry.id, intendedAmount = 10000.0, note = "Partial restatement")
        assertNotNull(reCorr)
        currentAcc = accountRepo.getAccountByIdOneShot(accountId)
        assertEquals(10000.0, currentAcc?.debtIqd ?: 0.0, 0.001)
    }
}
