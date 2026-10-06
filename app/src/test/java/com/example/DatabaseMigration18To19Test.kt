package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.core.model.SasProviders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Core Triad Standard:
 * 1. Claim:
 *    - Room Migration 18 -> 19 adds the `operationProvider` column with SQLite default 'EARTHLINK'
 *      to both `local_accounts` and `pending_external_operations` tables.
 *    - Pre-migration records in both tables are 100% preserved and assigned the default 'EARTHLINK' provider.
 *    - Subsequent writes support explicit alternative providers ('ALAMIRY') as well as default fallback ('EARTHLINK').
 *    - Room v19 database entities and DAOs read and write `operationProvider` correctly.
 * 2. Seam / Environment: ROBOLECTRIC (FrameworkSQLiteOpenHelperFactory over disposable SQLite database file).
 * 3. Independent Oracle: Explicit SQLite v18 DDL schemas matching Room 18.json, raw SQLite PRAGMA table_info queries,
 *    and direct cursor inspection independent of AppDatabase migration internals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DatabaseMigration18To19Test {

    @Test
    fun testMigration18To19_preservesExistingRowsAndPopulatesEarthlinkDefault() {
        // Core Triad:
        // Claim: MIGRATION_18_19 executes ALTER TABLE adding operationProvider with DEFAULT 'EARTHLINK' to local_accounts and pending_external_operations.
        // Seam: ROBOLECTRIC disposable SQLite database file via FrameworkSQLiteOpenHelperFactory.
        // Independent Oracle: Raw PRAGMA table_info inspection and raw SQL row SELECTs.

        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("test_migration_18_19_${System.currentTimeMillis()}.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(18) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create v18 local_accounts table schema
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
                            `isLegacy` INTEGER NOT NULL,
                            `isHistoryOnlySubscriber` INTEGER NOT NULL,
                            `openingDebtIqd` REAL NOT NULL,
                            `openingAdvanceIqd` REAL NOT NULL,
                            `openingLoanIqd` REAL NOT NULL,
                            `stateSource` TEXT,
                            `stateConfidence` TEXT,
                            `snapshotCapturedAt` INTEGER,
                            `ispSubscriberId` TEXT,
                            `ispUserIndex` INTEGER,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())

                    // Create v18 pending_external_operations table schema
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `pending_external_operations` (
                            `businessTransactionId` TEXT NOT NULL,
                            `operationIntentId` TEXT NOT NULL,
                            `accountId` TEXT NOT NULL,
                            `operationType` TEXT NOT NULL,
                            `amountIqd` INTEGER NOT NULL,
                            `payloadJson` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `lastError` TEXT,
                            `verificationEvidence` TEXT,
                            `dispatchClaimCount` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`businessTransactionId`)
                        )
                    """.trimIndent())

                    // Insert pre-migration rows in local_accounts (no operationProvider column)
                    db.execSQL("""
                        INSERT INTO `local_accounts` (
                            id, displayName, earthlinkUsername, phone1, currentPriceIqd, debtIqd, loanIqd, advanceIqd,
                            isLegacy, isHistoryOnlySubscriber, openingDebtIqd, openingAdvanceIqd, openingLoanIqd,
                            ispSubscriberId, ispUserIndex, createdAt, updatedAt
                        ) VALUES (
                            'acc_pre_1', 'Subscriber One', 'earthlink_user_1', '07700000001', 35000.0, 15000.0, 0.0, 0.0,
                            0, 0, 0.0, 0.0, 0.0,
                            'isp_sub_1', 101, 100000, 100000
                        )
                    """.trimIndent())

                    // Insert pre-migration rows in pending_external_operations (no operationProvider column)
                    db.execSQL("""
                        INSERT INTO `pending_external_operations` (
                            businessTransactionId, operationIntentId, accountId, operationType,
                            amountIqd, payloadJson, status, createdAt, updatedAt, dispatchClaimCount
                        ) VALUES (
                            'tx_pre_1', 'intent_pre_1', 'acc_pre_1', 'ACTIVATION',
                            35000, '{"note":"test"}', 'PENDING', 100000, 100000, 0
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v18Db = helper.writableDatabase

        try {
            // Verify pre-migration state has no operationProvider column
            assertFalse(hasColumn(v18Db, "local_accounts", "operationProvider"))
            assertFalse(hasColumn(v18Db, "pending_external_operations", "operationProvider"))

            // Execute migration 18 -> 19
            AppDatabase.MIGRATION_18_19.migrate(v18Db)

            // 1. Verify schema: column added to both tables
            assertTrue(hasColumn(v18Db, "local_accounts", "operationProvider"))
            assertTrue(hasColumn(v18Db, "pending_external_operations", "operationProvider"))

            // 2. Validate existing rows preserved and populated with 'EARTHLINK' in local_accounts
            val accCursor = v18Db.query("SELECT id, displayName, earthlinkUsername, debtIqd, operationProvider FROM local_accounts WHERE id = 'acc_pre_1'")
            assertTrue("Existing account row must be preserved", accCursor.moveToFirst())
            assertEquals("acc_pre_1", accCursor.getString(0))
            assertEquals("Subscriber One", accCursor.getString(1))
            assertEquals("earthlink_user_1", accCursor.getString(2))
            assertEquals(15000.0, accCursor.getDouble(3), 0.001)
            assertEquals("Pre-existing account must receive default EARTHLINK provider", "EARTHLINK", accCursor.getString(4))
            accCursor.close()

            // 3. Validate existing rows preserved and populated with 'EARTHLINK' in pending_external_operations
            val opCursor = v18Db.query("SELECT businessTransactionId, operationIntentId, accountId, operationType, amountIqd, status, operationProvider FROM pending_external_operations WHERE businessTransactionId = 'tx_pre_1'")
            assertTrue("Existing pending operation row must be preserved", opCursor.moveToFirst())
            assertEquals("tx_pre_1", opCursor.getString(0))
            assertEquals("intent_pre_1", opCursor.getString(1))
            assertEquals("acc_pre_1", opCursor.getString(2))
            assertEquals("ACTIVATION", opCursor.getString(3))
            assertEquals(35000L, opCursor.getLong(4))
            assertEquals("PENDING", opCursor.getString(5))
            assertEquals("Pre-existing pending operation must receive default EARTHLINK provider", "EARTHLINK", opCursor.getString(6))
            opCursor.close()

            // 4. Validate new rows can be inserted with 'ALAMIRY'
            v18Db.execSQL("""
                INSERT INTO `local_accounts` (
                    id, displayName, earthlinkUsername, currentPriceIqd, debtIqd, loanIqd, advanceIqd,
                    isLegacy, isHistoryOnlySubscriber, openingDebtIqd, openingAdvanceIqd, openingLoanIqd,
                    createdAt, updatedAt, operationProvider
                ) VALUES (
                    'acc_alamiry_1', 'Alamiry Subscriber', 'alamiry_user_1', 25000.0, 0.0, 0.0, 0.0,
                    0, 0, 0.0, 0.0, 0.0,
                    200000, 200000, 'ALAMIRY'
                )
            """.trimIndent())

            v18Db.execSQL("""
                INSERT INTO `pending_external_operations` (
                    businessTransactionId, operationIntentId, accountId, operationType,
                    amountIqd, payloadJson, status, createdAt, updatedAt, dispatchClaimCount, operationProvider
                ) VALUES (
                    'tx_alamiry_1', 'intent_alamiry_1', 'acc_alamiry_1', 'REFILL',
                    25000, '{}', 'PENDING', 200000, 200000, 0, 'ALAMIRY'
                )
            """.trimIndent())

            val alamiryAccCursor = v18Db.query("SELECT id, operationProvider FROM local_accounts WHERE id = 'acc_alamiry_1'")
            assertTrue(alamiryAccCursor.moveToFirst())
            assertEquals("ALAMIRY", alamiryAccCursor.getString(1))
            alamiryAccCursor.close()

            val alamiryOpCursor = v18Db.query("SELECT businessTransactionId, operationProvider FROM pending_external_operations WHERE businessTransactionId = 'tx_alamiry_1'")
            assertTrue(alamiryOpCursor.moveToFirst())
            assertEquals("ALAMIRY", alamiryOpCursor.getString(1))
            alamiryOpCursor.close()

            // 5. Validate inserting without specifying operationProvider falls back to SQLite default 'EARTHLINK'
            v18Db.execSQL("""
                INSERT INTO `local_accounts` (
                    id, displayName, currentPriceIqd, debtIqd, loanIqd, advanceIqd,
                    isLegacy, isHistoryOnlySubscriber, openingDebtIqd, openingAdvanceIqd, openingLoanIqd,
                    createdAt, updatedAt
                ) VALUES (
                    'acc_default_fallback', 'Fallback Subscriber', 10000.0, 0.0, 0.0, 0.0,
                    0, 0, 0.0, 0.0, 0.0,
                    300000, 300000
                )
            """.trimIndent())

            v18Db.execSQL("""
                INSERT INTO `pending_external_operations` (
                    businessTransactionId, operationIntentId, accountId, operationType,
                    amountIqd, payloadJson, status, createdAt, updatedAt, dispatchClaimCount
                ) VALUES (
                    'tx_default_fallback', 'intent_fallback_1', 'acc_default_fallback', 'RENEWAL',
                    10000, '{}', 'PENDING', 300000, 300000, 0
                )
            """.trimIndent())

            val fallbackAccCursor = v18Db.query("SELECT id, operationProvider FROM local_accounts WHERE id = 'acc_default_fallback'")
            assertTrue(fallbackAccCursor.moveToFirst())
            assertEquals("EARTHLINK", fallbackAccCursor.getString(1))
            fallbackAccCursor.close()

            val fallbackOpCursor = v18Db.query("SELECT businessTransactionId, operationProvider FROM pending_external_operations WHERE businessTransactionId = 'tx_default_fallback'")
            assertTrue(fallbackOpCursor.moveToFirst())
            assertEquals("EARTHLINK", fallbackOpCursor.getString(1))
            fallbackOpCursor.close()

        } finally {
            v18Db.close()
            helper.close()
            if (dbFile.exists()) dbFile.delete()
        }
    }

    @Test
    fun testSasProviders_constantIntegrityAndValidation() {
        // Core Triad:
        // Claim: SasProviders defines stable provider constants EARTHLINK, ALAMIRY, ALL set, and isValid validator.
        // Seam: Pure Kotlin object assertion.
        // Independent Oracle: Expected constant literals defined in domain contract.

        assertEquals("EARTHLINK", SasProviders.EARTHLINK)
        assertEquals("ALAMIRY", SasProviders.ALAMIRY)
        assertEquals(setOf("EARTHLINK", "ALAMIRY"), SasProviders.ALL)

        assertTrue("EARTHLINK must be valid", SasProviders.isValid("EARTHLINK"))
        assertTrue("ALAMIRY must be valid", SasProviders.isValid("ALAMIRY"))
        assertFalse("UNKNOWN must not be valid", SasProviders.isValid("UNKNOWN"))
        assertFalse("Empty string must not be valid", SasProviders.isValid(""))
        assertFalse("Lowercase earthlink must not match uppercase set", SasProviders.isValid("earthlink"))
    }

    @Test
    fun testRoomV19EntitiesAndDao_inMemoryOperations() = runBlocking {
        // Core Triad:
        // Claim: Room v19 schema matches LocalAccount and PendingExternalOperation entity definitions with operationProvider.
        // Seam: ROBOLECTRIC Room in-memory database builder.
        // Independent Oracle: Room DAO queries verifying round-trip persistence of default and custom operationProvider values.

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            // Test 1: LocalAccount with default provider
            val defaultAcc = LocalAccount(
                id = "acc_room_default",
                displayName = "Default Subscriber"
            )
            assertEquals("Kotlin default must be EARTHLINK", SasProviders.EARTHLINK, defaultAcc.operationProvider)
            db.localAccountDao().insert(defaultAcc)

            val loadedDefaultAcc = db.localAccountDao().getByIdOneShot("acc_room_default")
            assertNotNull(loadedDefaultAcc)
            assertEquals(SasProviders.EARTHLINK, loadedDefaultAcc?.operationProvider)

            // Test 2: LocalAccount with explicit ALAMIRY provider
            val alamiryAcc = LocalAccount(
                id = "acc_room_alamiry",
                displayName = "Alamiry Subscriber",
                operationProvider = SasProviders.ALAMIRY
            )
            db.localAccountDao().insert(alamiryAcc)

            val loadedAlamiryAcc = db.localAccountDao().getByIdOneShot("acc_room_alamiry")
            assertNotNull(loadedAlamiryAcc)
            assertEquals(SasProviders.ALAMIRY, loadedAlamiryAcc?.operationProvider)

            // Test 3: PendingExternalOperation with default provider
            val defaultOp = PendingExternalOperation(
                businessTransactionId = "tx_room_default",
                operationIntentId = "intent_room_default",
                accountId = "acc_room_default",
                operationType = "ACTIVATION",
                amountIqd = 35000L
            )
            assertEquals("Kotlin default must be EARTHLINK", SasProviders.EARTHLINK, defaultOp.operationProvider)
            db.pendingExternalOperationDao().insert(defaultOp)

            val loadedDefaultOp = db.pendingExternalOperationDao().getByBusinessTransactionId("tx_room_default")
            assertNotNull(loadedDefaultOp)
            assertEquals(SasProviders.EARTHLINK, loadedDefaultOp?.operationProvider)

            // Test 4: PendingExternalOperation with explicit ALAMIRY provider
            val alamiryOp = PendingExternalOperation(
                businessTransactionId = "tx_room_alamiry",
                operationIntentId = "intent_room_alamiry",
                accountId = "acc_room_alamiry",
                operationType = "REFILL",
                amountIqd = 25000L,
                operationProvider = SasProviders.ALAMIRY
            )
            db.pendingExternalOperationDao().insert(alamiryOp)

            val loadedAlamiryOp = db.pendingExternalOperationDao().getByBusinessTransactionId("tx_room_alamiry")
            assertNotNull(loadedAlamiryOp)
            assertEquals(SasProviders.ALAMIRY, loadedAlamiryOp?.operationProvider)

        } finally {
            db.close()
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        val cursor = db.query("PRAGMA table_info($tableName)")
        var found = false
        val nameColumnIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameColumnIndex) == columnName) {
                found = true
                break
            }
        }
        cursor.close()
        return found
    }
}
