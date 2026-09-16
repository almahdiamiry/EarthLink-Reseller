package com.example

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.ledger.BalanceCalculator
import com.example.core.model.LocalLedgerEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Core Triad Standard:
 * 1. Claim: Room Migration 17 -> 18 preserves 100% of existing accounts (216), ledgers (2761),
 *    immutable container/ledger IDs, ledger ownership, and financial position derivations;
 *    relaxes index_local_accounts_sourceExternalId from UNIQUE to standard index;
 *    adds nullable columns ispSubscriberId and ispUserIndex; and adds index_local_accounts_ispUserIndex.
 * 2. Seam / Environment: ROBOLECTRIC (FrameworkSQLiteOpenHelperFactory over disposable SQLite database file).
 * 3. Independent Oracle: Real production golden database backup (earthlink_backup_1789281798680.zip)
 *    evaluated independently before and after migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DatabaseMigration17To18Test {

    private fun findGoldenZip(): File {
        val candidates = listOf(
            File("earthlink_backup_1789281798680.zip"),
            File("..", "earthlink_backup_1789281798680.zip"),
            File("../..", "earthlink_backup_1789281798680.zip")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Golden backup zip earthlink_backup_1789281798680.zip not found in candidate paths: ${candidates.map { it.absolutePath }}")
    }

    private fun extractDisposableDatabase(zipFile: File, targetFile: File) {
        val zip = ZipFile(zipFile)
        val entry = zip.getEntry("earthlink_reseller_db")
            ?: error("earthlink_reseller_db not found in golden backup zip")
        zip.getInputStream(entry).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        zip.close()
    }

    private data class AccountSnapshot(
        val id: String,
        val sourceExternalId: String?,
        val displayName: String,
        val debtIqd: Double,
        val advanceIqd: Double,
        val loanIqd: Double,
        val openingDebtIqd: Double,
        val openingAdvanceIqd: Double,
        val openingLoanIqd: Double,
        val stateSource: String?,
        val isHistoryOnlySubscriber: Boolean
    )

    private data class LedgerSnapshot(
        val id: String,
        val accountId: String,
        val sourceExternalId: String?,
        val typeRaw: String,
        val amountIqd: Double,
        val debtAfterIqd: Double,
        val occurredAt: Long,
        val isSnapshotHistory: Boolean
    )

    @Test
    fun testMigration17To18_goldenDatabasePreservedAndMigratedSuccessfully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val zipFile = findGoldenZip()
        assertTrue("Golden backup zip must exist", zipFile.exists())
        val originalZipLastModified = zipFile.lastModified()
        val originalZipLength = zipFile.length()

        val tempDbFile = context.getDatabasePath("disposable_golden_v17_to_18_${System.currentTimeMillis()}.db")
        tempDbFile.parentFile?.mkdirs()
        if (tempDbFile.exists()) tempDbFile.delete()

        try {
            // 1. Extract disposable copy
            extractDisposableDatabase(zipFile, tempDbFile)
            assertTrue("Disposable database must exist", tempDbFile.exists())

            // 2. Open v17 database using FrameworkSQLiteOpenHelperFactory
            val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(tempDbFile.name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()

            val factory = FrameworkSQLiteOpenHelperFactory()
            val helper = factory.create(config)
            val db = helper.writableDatabase

            // Verify pre-migration state
            val v17VersionCursor = db.query("PRAGMA user_version")
            assertTrue(v17VersionCursor.moveToFirst())
            val userVersionBefore = v17VersionCursor.getInt(0)
            v17VersionCursor.close()
            assertEquals("Pre-migration database must be at version 17", 17, userVersionBefore)

            // Collect accounts before migration
            val accountsBefore = mutableMapOf<String, AccountSnapshot>()
            val accCursor = db.query("""
                SELECT id, sourceExternalId, displayName, debtIqd, advanceIqd, loanIqd,
                       openingDebtIqd, openingAdvanceIqd, openingLoanIqd, stateSource, isHistoryOnlySubscriber
                FROM local_accounts
            """)
            while (accCursor.moveToNext()) {
                val acc = AccountSnapshot(
                    id = accCursor.getString(0),
                    sourceExternalId = accCursor.getString(1),
                    displayName = accCursor.getString(2),
                    debtIqd = accCursor.getDouble(3),
                    advanceIqd = accCursor.getDouble(4),
                    loanIqd = accCursor.getDouble(5),
                    openingDebtIqd = accCursor.getDouble(6),
                    openingAdvanceIqd = accCursor.getDouble(7),
                    openingLoanIqd = accCursor.getDouble(8),
                    stateSource = accCursor.getString(9),
                    isHistoryOnlySubscriber = accCursor.getInt(10) == 1
                )
                accountsBefore[acc.id] = acc
            }
            accCursor.close()
            assertEquals("Golden v17 database must contain exactly 216 accounts", 216, accountsBefore.size)

            // Collect ledgers before migration
            val ledgersBefore = mutableMapOf<String, LedgerSnapshot>()
            val ledgersByAccountBefore = mutableMapOf<String, MutableList<LocalLedgerEntry>>()
            val ledCursor = db.query("""
                SELECT id, accountId, sourceExternalId, typeRaw, amountIqd, debtAfterIqd, occurredAt, isSnapshotHistory
                FROM local_ledger_entries
            """)
            while (ledCursor.moveToNext()) {
                val led = LedgerSnapshot(
                    id = ledCursor.getString(0),
                    accountId = ledCursor.getString(1),
                    sourceExternalId = ledCursor.getString(2),
                    typeRaw = ledCursor.getString(3),
                    amountIqd = ledCursor.getDouble(4),
                    debtAfterIqd = ledCursor.getDouble(5),
                    occurredAt = ledCursor.getLong(6),
                    isSnapshotHistory = ledCursor.getInt(7) == 1
                )
                ledgersBefore[led.id] = led
                ledgersByAccountBefore.getOrPut(led.accountId) { mutableListOf() }.add(
                    LocalLedgerEntry(
                        id = led.id,
                        accountId = led.accountId,
                        sourceExternalId = led.sourceExternalId,
                        typeRaw = led.typeRaw,
                        amountIqd = led.amountIqd,
                        debtAfterIqd = led.debtAfterIqd,
                        occurredAt = led.occurredAt,
                        isSnapshotHistory = led.isSnapshotHistory
                    )
                )
            }
            ledCursor.close()
            assertEquals("Golden v17 database must contain exactly 2761 ledgers", 2761, ledgersBefore.size)

            // Compute financial positions before migration using BalanceCalculator
            val financialPositionsBefore = mutableMapOf<String, Double>()
            for ((accId, acc) in accountsBefore) {
                val txs = ledgersByAccountBefore[accId] ?: emptyList()
                val isSnapshotBaseline = acc.stateSource != null
                val (bal, _) = BalanceCalculator.reconstructCurrentPosition(
                    openingDebt = acc.openingDebtIqd,
                    openingAdvance = acc.openingAdvanceIqd,
                    openingLoan = acc.openingLoanIqd,
                    transactions = txs,
                    isSnapshotBaseline = isSnapshotBaseline
                )
                financialPositionsBefore[accId] = bal.debtIqd - bal.advanceIqd + bal.loanIqd
            }

            // Verify index state before migration: sourceExternalId index MUST be UNIQUE
            val idxCursorBefore = db.query("PRAGMA index_list('local_accounts')")
            var sourceExternalIdIndexUniqueBefore: Boolean? = null
            while (idxCursorBefore.moveToNext()) {
                val name = idxCursorBefore.getString(1)
                val unique = idxCursorBefore.getInt(2) == 1
                if (name == "index_local_accounts_sourceExternalId") {
                    sourceExternalIdIndexUniqueBefore = unique
                }
            }
            idxCursorBefore.close()
            assertEquals(
                "index_local_accounts_sourceExternalId must be UNIQUE before migration",
                true,
                sourceExternalIdIndexUniqueBefore
            )

            // 3. EXECUTE MIGRATION 17 -> 18
            AppDatabase.MIGRATION_17_18.migrate(db)
            db.execSQL("PRAGMA user_version = 18")

            // 4. VERIFY AFTER MIGRATION

            // Invariant 1: Foreign key violations = 0
            val fkCursor = db.query("PRAGMA foreign_key_check")
            val fkViolations = mutableListOf<String>()
            while (fkCursor.moveToNext()) {
                fkViolations.add("table=${fkCursor.getString(0)}, rowid=${fkCursor.getLong(1)}, target=${fkCursor.getString(2)}")
            }
            fkCursor.close()
            assertTrue("PRAGMA foreign_key_check must return 0 violations after migration. Found: $fkViolations", fkViolations.isEmpty())

            // Invariant 2: Exactly 216 accounts preserved with unchanged IDs and defaults
            val accountsAfter = mutableMapOf<String, AccountSnapshot>()
            val accAfterCursor = db.query("""
                SELECT id, sourceExternalId, displayName, debtIqd, advanceIqd, loanIqd,
                       openingDebtIqd, openingAdvanceIqd, openingLoanIqd, stateSource, isHistoryOnlySubscriber,
                       ispSubscriberId, ispUserIndex
                FROM local_accounts
            """)
            while (accAfterCursor.moveToNext()) {
                val accId = accAfterCursor.getString(0)
                val acc = AccountSnapshot(
                    id = accId,
                    sourceExternalId = accAfterCursor.getString(1),
                    displayName = accAfterCursor.getString(2),
                    debtIqd = accAfterCursor.getDouble(3),
                    advanceIqd = accAfterCursor.getDouble(4),
                    loanIqd = accAfterCursor.getDouble(5),
                    openingDebtIqd = accAfterCursor.getDouble(6),
                    openingAdvanceIqd = accAfterCursor.getDouble(7),
                    openingLoanIqd = accAfterCursor.getDouble(8),
                    stateSource = accAfterCursor.getString(9),
                    isHistoryOnlySubscriber = accAfterCursor.getInt(10) == 1
                )
                accountsAfter[accId] = acc

                // ispSubscriberId defaults NULL
                assertTrue("ispSubscriberId must default to NULL for existing account $accId", accAfterCursor.isNull(11))
                // ispUserIndex defaults NULL
                assertTrue("ispUserIndex must default to NULL for existing account $accId", accAfterCursor.isNull(12))
            }
            accAfterCursor.close()
            assertEquals("Account count must remain exactly 216 after migration", 216, accountsAfter.size)
            assertEquals("All existing LocalAccount.id values must be preserved unchanged", accountsBefore.keys, accountsAfter.keys)

            // Invariant 3: Exactly 2761 ledgers preserved with unchanged IDs and ownership
            val ledgersAfter = mutableMapOf<String, LedgerSnapshot>()
            val ledgersByAccountAfter = mutableMapOf<String, MutableList<LocalLedgerEntry>>()
            val ledAfterCursor = db.query("""
                SELECT id, accountId, sourceExternalId, typeRaw, amountIqd, debtAfterIqd, occurredAt, isSnapshotHistory
                FROM local_ledger_entries
            """)
            while (ledAfterCursor.moveToNext()) {
                val led = LedgerSnapshot(
                    id = ledAfterCursor.getString(0),
                    accountId = ledAfterCursor.getString(1),
                    sourceExternalId = ledAfterCursor.getString(2),
                    typeRaw = ledAfterCursor.getString(3),
                    amountIqd = ledAfterCursor.getDouble(4),
                    debtAfterIqd = ledAfterCursor.getDouble(5),
                    occurredAt = ledAfterCursor.getLong(6),
                    isSnapshotHistory = ledAfterCursor.getInt(7) == 1
                )
                ledgersAfter[led.id] = led
                ledgersByAccountAfter.getOrPut(led.accountId) { mutableListOf() }.add(
                    LocalLedgerEntry(
                        id = led.id,
                        accountId = led.accountId,
                        sourceExternalId = led.sourceExternalId,
                        typeRaw = led.typeRaw,
                        amountIqd = led.amountIqd,
                        debtAfterIqd = led.debtAfterIqd,
                        occurredAt = led.occurredAt,
                        isSnapshotHistory = led.isSnapshotHistory
                    )
                )
            }
            ledAfterCursor.close()
            assertEquals("Ledger count must remain exactly 2761 after migration", 2761, ledgersAfter.size)
            assertEquals("All existing LocalLedgerEntry.id values must be preserved unchanged", ledgersBefore.keys, ledgersAfter.keys)

            // Invariant 4: Ledger ownership (accountId) preserved unchanged
            for ((ledId, beforeLedger) in ledgersBefore) {
                val afterLedger = requireNotNull(ledgersAfter[ledId]) { "Ledger $ledId missing after migration" }
                assertEquals(
                    "Ledger $ledId ownership (accountId) must not be altered across migration",
                    beforeLedger.accountId,
                    afterLedger.accountId
                )
            }

            // Invariant 5: Indices correctly relaxed and added
            val idxCursorAfter = db.query("PRAGMA index_list('local_accounts')")
            var sourceExternalIdIndexExists = false
            var sourceExternalIdIndexUnique: Boolean? = null
            var ispUserIndexIndexExists = false
            var ispUserIndexIndexUnique: Boolean? = null
            while (idxCursorAfter.moveToNext()) {
                val name = idxCursorAfter.getString(1)
                val unique = idxCursorAfter.getInt(2) == 1
                if (name == "index_local_accounts_sourceExternalId") {
                    sourceExternalIdIndexExists = true
                    sourceExternalIdIndexUnique = unique
                }
                if (name == "index_local_accounts_ispUserIndex") {
                    ispUserIndexIndexExists = true
                    ispUserIndexIndexUnique = unique
                }
            }
            idxCursorAfter.close()

            assertTrue("sourceExternalId index must exist after migration", sourceExternalIdIndexExists)
            assertEquals(
                "sourceExternalId index must NOT be UNIQUE after migration (relaxed index)",
                false,
                sourceExternalIdIndexUnique
            )
            assertTrue("ispUserIndex index must exist after migration", ispUserIndexIndexExists)
            assertEquals("ispUserIndex index must be non-unique", false, ispUserIndexIndexUnique)

            // Invariant 6: Financial reconstruction unchanged
            for ((accId, acc) in accountsAfter) {
                val txs = ledgersByAccountAfter[accId] ?: emptyList()
                val isSnapshotBaseline = acc.stateSource != null
                val (bal, _) = BalanceCalculator.reconstructCurrentPosition(
                    openingDebt = acc.openingDebtIqd,
                    openingAdvance = acc.openingAdvanceIqd,
                    openingLoan = acc.openingLoanIqd,
                    transactions = txs,
                    isSnapshotBaseline = isSnapshotBaseline
                )
                val currentPositionAfter = bal.debtIqd - bal.advanceIqd + bal.loanIqd
                val expectedBefore = requireNotNull(financialPositionsBefore[accId]) {
                    "Missing pre-migration financial baseline for account $accId"
                }
                assertEquals(
                    "Financial derivation for account $accId must remain identical across migration",
                    expectedBefore,
                    currentPositionAfter,
                    0.0001
                )
            }

            // Invariant 7: Ability to update and query new columns
            val sampleAccId = accountsAfter.keys.first()
            db.execSQL(
                "UPDATE local_accounts SET ispSubscriberId = 'earthlink:12345', ispUserIndex = 12345 WHERE id = '$sampleAccId'"
            )
            val updatedCursor = db.query(
                "SELECT ispSubscriberId, ispUserIndex FROM local_accounts WHERE id = '$sampleAccId'"
            )
            assertTrue(updatedCursor.moveToFirst())
            assertEquals("earthlink:12345", updatedCursor.getString(0))
            assertEquals(12345, updatedCursor.getInt(1))
            updatedCursor.close()

            // Invariant 8: Non-unique sourceExternalId insertion succeeds
            val existingExtId = accountsAfter.values.firstOrNull { it.sourceExternalId != null }?.sourceExternalId
                ?: "SAMPLE_EXT_ID"
            db.execSQL("""
                INSERT INTO local_accounts (
                    id, displayName, sourceExternalId, currentPriceIqd, debtIqd, advanceIqd, loanIqd,
                    openingDebtIqd, openingAdvanceIqd, openingLoanIqd, isLegacy,
                    isHistoryOnlySubscriber, createdAt, updatedAt
                ) VALUES (
                    'new_sibling_container_test', 'Sibling Container', '$existingExtId', 35000.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0, 0, 100, 100
                )
            """)
            val siblingCursor = db.query("SELECT COUNT(*) FROM local_accounts WHERE sourceExternalId = '$existingExtId'")
            assertTrue(siblingCursor.moveToFirst())
            assertTrue("Multiple accounts can now share sourceExternalId without constraint failure", siblingCursor.getInt(0) >= 2)
            siblingCursor.close()

            // Close DB
            db.close()
            helper.close()
        } finally {
            if (tempDbFile.exists()) {
                tempDbFile.delete()
            }
        }

        // Golden backup safety check: verify original golden backup file was NEVER modified
        assertEquals("Golden zip file length must not change", originalZipLength, zipFile.length())
        assertEquals("Golden zip file timestamp must not change", originalZipLastModified, zipFile.lastModified())
    }
}
