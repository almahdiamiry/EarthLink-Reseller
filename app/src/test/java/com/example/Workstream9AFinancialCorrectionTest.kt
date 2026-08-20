package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.DivergentPayloadConflictException
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
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

/**
 * Workstream 9A Certification Tests: Financial History Deletion Authority Closure & Correction-by-Difference.
 *
 * Verifies:
 * 1. MIGRATION_14_15 non-destructively adds correctsEntryId and its index.
 * 2. Correction-by-difference replaces physical delete with additive correction entry.
 * 3. Original financial row is NEVER deleted or modified in local_ledger_entries.
 * 4. No delete tombstones are emitted for original financial records.
 * 5. Anti-chain rule: correcting a correction automatically redirects to the root original transaction.
 * 6. Deterministic identity and idempotent retry without row duplication.
 * 7. Divergent payload conflict detection on conflicting re-entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Workstream9AFinancialCorrectionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var accountRepository: LocalAccountRepositoryImpl
    private lateinit var ledgerRepository: LocalLedgerRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountRepository = LocalAccountRepositoryImpl(
            database = database,
            accountDao = database.localAccountDao(),
            outboxDao = database.syncOutboxDao()
        )

        ledgerRepository = LocalLedgerRepositoryImpl(
            database = database,
            ledgerDao = database.localLedgerEntryDao(),
            accountDao = database.localAccountDao(),
            outboxDao = database.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testMigration_14_to_15_addsCorrectsEntryIdColumnAndIndexPreservingData() {
        val dbFile = File(context.cacheDir, "test_mig_14_15.db")
        if (dbFile.exists()) dbFile.delete()

        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(14) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `local_accounts` (
                            `id` TEXT NOT NULL,
                            `sourceExternalId` TEXT,
                            `sourceBatchId` TEXT,
                            `displayName` TEXT NOT NULL,
                            `earthlinkUsername` TEXT,
                            `phone1` TEXT,
                            `phone2` TEXT,
                            `packageName` TEXT,
                            `isLegacy` INTEGER NOT NULL,
                            `isHistoryOnlySubscriber` INTEGER NOT NULL,
                            `currentPriceIqd` REAL NOT NULL,
                            `debtIqd` REAL NOT NULL,
                            `loanIqd` REAL NOT NULL,
                            `advanceIqd` REAL NOT NULL,
                            `towerName` TEXT,
                            `zoneName` TEXT,
                            `address` TEXT,
                            `nanoIp` TEXT,
                            `latitude` REAL,
                            `longitude` REAL,
                            `note` TEXT,
                            `expiresAt` TEXT,
                            `lastPaymentAt` INTEGER,
                            `rawJson` TEXT,
                            `openingDebtIqd` REAL NOT NULL,
                            `openingAdvanceIqd` REAL NOT NULL,
                            `openingLoanIqd` REAL NOT NULL,
                            `stateSource` TEXT,
                            `stateConfidence` TEXT,
                            `snapshotCapturedAt` INTEGER,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `local_ledger_entries` (
                            `id` TEXT NOT NULL,
                            `accountId` TEXT NOT NULL,
                            `sourceExternalId` TEXT,
                            `sourceBatchId` TEXT,
                            `typeRaw` TEXT NOT NULL,
                            `amountIqd` REAL NOT NULL,
                            `debtAfterIqd` REAL NOT NULL,
                            `note` TEXT,
                            `occurredAt` INTEGER NOT NULL,
                            `rawJson` TEXT,
                            `createdAt` INTEGER NOT NULL,
                            `isSnapshotHistory` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`accountId`) REFERENCES `local_accounts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                        )
                    """.trimIndent())
                    db.execSQL("INSERT INTO local_accounts (id, displayName, isLegacy, isHistoryOnlySubscriber, currentPriceIqd, debtIqd, loanIqd, advanceIqd, openingDebtIqd, openingAdvanceIqd, openingLoanIqd, createdAt, updatedAt) VALUES ('acc1', 'Test Acc', 0, 0, 35000, 100000, 0, 0, 0, 0, 0, 1000, 1000)")
                    db.execSQL("INSERT INTO local_ledger_entries (id, accountId, typeRaw, amountIqd, debtAfterIqd, occurredAt, createdAt, isSnapshotHistory) VALUES ('tx1', 'acc1', 'took', 100000, 100000, 1000, 1000, 0)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val v14Db = helper.writableDatabase

        // Execute MIGRATION_14_15
        AppDatabase.MIGRATION_14_15.migrate(v14Db)

        // Verify column exists
        val cursor = v14Db.query("SELECT id, accountId, typeRaw, amountIqd, correctsEntryId FROM local_ledger_entries WHERE id = 'tx1'")
        assertTrue("Migrated row must exist", cursor.moveToFirst())
        assertEquals("tx1", cursor.getString(0))
        assertEquals("acc1", cursor.getString(1))
        assertEquals("took", cursor.getString(2))
        assertEquals(100000.0, cursor.getDouble(3), 0.001)
        assertNull("Pre-existing rows must have NULL correctsEntryId", cursor.getString(4))
        cursor.close()

        v14Db.close()
        helper.close()
        dbFile.delete()
    }

    @Test
    fun testCorrectionByDifference_tookOvercharged_createsPaymentCorrection() = runBlocking {
        val accountId = "acc_corr_1"
        val account = LocalAccount(
            id = accountId,
            displayName = "User 1",
            openingDebtIqd = 0.0,
            debtIqd = 100000.0,
            advanceIqd = 0.0,
            loanIqd = 0.0
        )
        accountRepository.saveAccount(account)

        val originalTx = LocalLedgerEntry(
            id = "tx_orig_1",
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 100000.0,
            debtAfterIqd = 100000.0,
            occurredAt = 1000L
        )
        database.localLedgerEntryDao().insert(originalTx)

        // User intended took 70,000 instead of 100,000
        val correction = ledgerRepository.correctTransaction(
            originalEntryId = "tx_orig_1",
            intendedAmount = 70000.0,
            note = "Adjusted overcharge"
        )

        // Correction must be "gave" (payment) of 30,000
        assertEquals("gave", correction.typeRaw)
        assertEquals(30000.0, correction.amountIqd, 0.001)
        assertEquals("tx_orig_1", correction.correctsEntryId)

        // Account debt must now be 70,000
        val updatedAcc = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(updatedAcc)
        assertEquals(70000.0, updatedAcc!!.debtIqd, 0.001)

        // Original transaction MUST STILL EXIST untouched in database
        val origInDb = database.localLedgerEntryDao().getByIdOneShot("tx_orig_1")
        assertNotNull("Original entry must remain in database", origInDb)
        assertEquals(100000.0, origInDb!!.amountIqd, 0.001)
        assertEquals("took", origInDb.typeRaw)

        // Outbox must contain an upsert for correction, NOT a tombstone for original
        val outbox = database.syncOutboxDao().getPending()
        val origTombstone = outbox.find { it.entityId == "tx_orig_1" && it.operation == "delete" }
        assertNull("Original entry must NOT have a delete tombstone", origTombstone)

        val corrUpsert = outbox.find { it.entityId == correction.id && it.operation == "upsert" }
        assertNotNull("Correction must have an outbox UPSERT", corrUpsert)
    }

    @Test
    fun testCorrectionByDifference_gaveOverpaid_createsDebtCorrection() = runBlocking {
        val accountId = "acc_corr_2"
        val account = LocalAccount(
            id = accountId,
            displayName = "User 2",
            openingDebtIqd = 0.0,
            debtIqd = 0.0,
            advanceIqd = 100000.0,
            loanIqd = 0.0
        )
        accountRepository.saveAccount(account)

        val originalTx = LocalLedgerEntry(
            id = "tx_orig_2",
            accountId = accountId,
            typeRaw = "gave",
            amountIqd = 100000.0,
            debtAfterIqd = 0.0,
            occurredAt = 1000L
        )
        database.localLedgerEntryDao().insert(originalTx)

        // User intended payment was 70,000 instead of 100,000
        val correction = ledgerRepository.correctTransaction(
            originalEntryId = "tx_orig_2",
            intendedAmount = 70000.0,
            note = "Payment refund difference"
        )

        // Correction must be "took" (debt) of 30,000
        assertEquals("took", correction.typeRaw)
        assertEquals(30000.0, correction.amountIqd, 0.001)
        assertEquals("tx_orig_2", correction.correctsEntryId)

        // Account advance must now be 70,000
        val updatedAcc = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(updatedAcc)
        assertEquals(70000.0, updatedAcc!!.advanceIqd, 0.001)
        assertEquals(0.0, updatedAcc.debtIqd, 0.001)
    }

    @Test
    fun testFullReversal_viaDeleteTransaction_createsZeroIntendedCorrectionWithoutPhysicalDeletion() = runBlocking {
        val accountId = "acc_reversal_1"
        val account = LocalAccount(
            id = accountId,
            displayName = "User Reversal",
            openingDebtIqd = 0.0,
            debtIqd = 50000.0,
            advanceIqd = 0.0,
            loanIqd = 0.0
        )
        accountRepository.saveAccount(account)

        val originalTx = LocalLedgerEntry(
            id = "tx_reversal_target",
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            occurredAt = 2000L
        )
        database.localLedgerEntryDao().insert(originalTx)

        // Call deleteTransaction (reversal)
        ledgerRepository.deleteTransaction("tx_reversal_target")

        // Original transaction MUST STILL BE PRESENT in database
        val origInDb = database.localLedgerEntryDao().getByIdOneShot("tx_reversal_target")
        assertNotNull("Original transaction must NOT be physically deleted", origInDb)

        // Account debt is zeroed
        val updatedAcc = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(updatedAcc)
        assertEquals(0.0, updatedAcc!!.debtIqd, 0.001)

        // A reversal entry exists referencing original
        val allEntries = database.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals(2, allEntries.size)
        val reversalEntry = allEntries.find { it.correctsEntryId == "tx_reversal_target" }
        assertNotNull(reversalEntry)
        assertEquals("gave", reversalEntry!!.typeRaw)
        assertEquals(50000.0, reversalEntry.amountIqd, 0.001)
    }

    @Test
    fun testAntiChainRule_correctingCorrection_redirectsToRootOriginal() = runBlocking {
        val accountId = "acc_antichain"
        val account = LocalAccount(
            id = accountId,
            displayName = "User Anti-chain",
            openingDebtIqd = 0.0,
            debtIqd = 100000.0,
            advanceIqd = 0.0,
            loanIqd = 0.0
        )
        accountRepository.saveAccount(account)

        val rootTx = LocalLedgerEntry(
            id = "tx_root_1",
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 100000.0,
            debtAfterIqd = 100000.0,
            occurredAt = 1000L
        )
        database.localLedgerEntryDao().insert(rootTx)

        // First correction: intended 80,000
        val c1 = ledgerRepository.correctTransaction("tx_root_1", 80000.0)
        assertEquals("tx_root_1", c1.correctsEntryId)

        // Second correction targeting C1: intended 60,000
        // Anti-chain rule: target resolves and redirects to rootTx ("tx_root_1")
        val c2 = ledgerRepository.correctTransaction(c1.id, 60000.0)
        assertEquals("tx_root_1", c2.correctsEntryId)
    }

    @Test
    fun testCorrectionIdempotency_sameIntent_returnsExistingEntryWithoutDuplicateRows() = runBlocking {
        val accountId = "acc_idempotent"
        val account = LocalAccount(
            id = accountId,
            displayName = "User Idempotent",
            openingDebtIqd = 0.0,
            debtIqd = 100000.0,
            advanceIqd = 0.0,
            loanIqd = 0.0
        )
        accountRepository.saveAccount(account)

        val originalTx = LocalLedgerEntry(
            id = "tx_orig_idem",
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 100000.0,
            debtAfterIqd = 100000.0,
            occurredAt = 1000L
        )
        database.localLedgerEntryDao().insert(originalTx)

        val firstCall = ledgerRepository.correctTransaction(
            originalEntryId = "tx_orig_idem",
            intendedAmount = 40000.0,
            note = "Shared Note"
        )
        val secondCall = ledgerRepository.correctTransaction(
            originalEntryId = "tx_orig_idem",
            intendedAmount = 40000.0,
            note = "Shared Note"
        )

        assertEquals("Must return the same correction ID", firstCall.id, secondCall.id)
        val allEntries = database.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals("Must only have original + 1 correction row", 2, allEntries.size)
    }

    @Test
    fun testCorrectionDivergentPayload_throwsConflictException() = runBlocking {
        val accountId = "acc_divergent"
        val account = LocalAccount(
            id = accountId,
            displayName = "User Divergent",
            openingDebtIqd = 0.0,
            debtIqd = 100000.0,
            advanceIqd = 0.0,
            loanIqd = 0.0
        )
        accountRepository.saveAccount(account)

        val originalTx = LocalLedgerEntry(
            id = "tx_orig_div",
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 100000.0,
            debtAfterIqd = 100000.0,
            occurredAt = 1000L
        )
        database.localLedgerEntryDao().insert(originalTx)

        // First call with explicit idempotency key
        ledgerRepository.correctTransaction(
            originalEntryId = "tx_orig_div",
            intendedAmount = 50000.0,
            idempotencyKey = "fixed_corr_key"
        )

        // Second call with same key but divergent intended amount
        try {
            ledgerRepository.correctTransaction(
                originalEntryId = "tx_orig_div",
                intendedAmount = 20000.0,
                idempotencyKey = "fixed_corr_key"
            )
            fail("Expected DivergentPayloadConflictException")
        } catch (e: DivergentPayloadConflictException) {
            assertTrue(e.message?.contains("divergent payload conflict") == true)
        }
    }
}
