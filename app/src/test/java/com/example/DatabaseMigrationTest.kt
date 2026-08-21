package com.example

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DatabaseMigrationTest {

    @Test
    fun testMigration15To16_addsVerificationEvidenceColumnPreservingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("test_migration_15_16.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(15) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create the version 15 pending_external_operations table schema
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
                            PRIMARY KEY(`businessTransactionId`)
                        )
                    """.trimIndent())

                    // Insert a test row prior to migration
                    db.execSQL("""
                        INSERT INTO pending_external_operations (
                            businessTransactionId, operationIntentId, accountId, operationType, amountIqd, payloadJson, status, createdAt, updatedAt
                        ) VALUES (
                            'tx_pre_migration_01', 'intent_pre_01', 'acc_pre_01', 'ACTIVATION', 45000, '{}', 'PENDING', 123456789, 123456789
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v15Db = helper.writableDatabase

        // Execute migration 15 -> 16
        AppDatabase.MIGRATION_15_16.migrate(v15Db)

        // Verify that the new verificationEvidence column exists and existing data is intact
        val cursor = v15Db.query("SELECT businessTransactionId, operationIntentId, verificationEvidence FROM pending_external_operations WHERE businessTransactionId = 'tx_pre_migration_01'")
        assertTrue("Pre-existing migrated row must exist", cursor.moveToFirst())
        assertEquals("tx_pre_migration_01", cursor.getString(0))
        assertEquals("intent_pre_01", cursor.getString(1))
        assertNull("verificationEvidence should default to NULL for migrated rows", cursor.getString(2))
        cursor.close()

        // Verify that we can write to the new column
        v15Db.execSQL("UPDATE pending_external_operations SET verificationEvidence = 'Sample Manual Ref #1234' WHERE businessTransactionId = 'tx_pre_migration_01'")
        val checkCursor = v15Db.query("SELECT verificationEvidence FROM pending_external_operations WHERE businessTransactionId = 'tx_pre_migration_01'")
        assertTrue(checkCursor.moveToFirst())
        assertEquals("Sample Manual Ref #1234", checkCursor.getString(0))
        checkCursor.close()

        v15Db.close()
        helper.close()
        dbFile.delete()
    }

    @Test
    fun testMigration16To17_addsDispatchClaimCountAndUpgradesExistingUnresolvedRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("test_migration_16_17.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(16) {
                override fun onCreate(db: SupportSQLiteDatabase) {
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
                            PRIMARY KEY(`businessTransactionId`)
                        )
                    """.trimIndent())

                    db.execSQL("INSERT INTO pending_external_operations VALUES ('tx_pending', 'i1', 'acc1', 'ACTIVATION', 45000, '{}', 'PENDING', 100, 100, NULL, NULL)")
                    db.execSQL("INSERT INTO pending_external_operations VALUES ('tx_dispatching', 'i2', 'acc2', 'REFILL', 45000, '{}', 'DISPATCHING', 100, 100, NULL, NULL)")
                    db.execSQL("INSERT INTO pending_external_operations VALUES ('tx_resolving', 'i3', 'acc3', 'REFILL', 45000, '{}', 'RESOLVING', 100, 100, NULL, NULL)")
                    db.execSQL("INSERT INTO pending_external_operations VALUES ('tx_completed', 'i4', 'acc4', 'ACTIVATION', 45000, '{}', 'COMPLETED', 100, 100, NULL, NULL)")
                    db.execSQL("INSERT INTO pending_external_operations VALUES ('tx_failed', 'i5', 'acc5', 'ACTIVATION', 45000, '{}', 'FAILED', 100, 100, NULL, NULL)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v16Db = helper.writableDatabase

        // Execute migration 16 -> 17
        AppDatabase.MIGRATION_16_17.migrate(v16Db)

        val cursorPending = v16Db.query("SELECT dispatchClaimCount FROM pending_external_operations WHERE businessTransactionId = 'tx_pending'")
        assertTrue(cursorPending.moveToFirst())
        assertEquals(1, cursorPending.getInt(0))
        cursorPending.close()

        val cursorDispatching = v16Db.query("SELECT dispatchClaimCount FROM pending_external_operations WHERE businessTransactionId = 'tx_dispatching'")
        assertTrue(cursorDispatching.moveToFirst())
        assertEquals(1, cursorDispatching.getInt(0))
        cursorDispatching.close()

        val cursorResolving = v16Db.query("SELECT dispatchClaimCount FROM pending_external_operations WHERE businessTransactionId = 'tx_resolving'")
        assertTrue(cursorResolving.moveToFirst())
        assertEquals(1, cursorResolving.getInt(0))
        cursorResolving.close()

        val cursorCompleted = v16Db.query("SELECT dispatchClaimCount FROM pending_external_operations WHERE businessTransactionId = 'tx_completed'")
        assertTrue(cursorCompleted.moveToFirst())
        assertEquals(0, cursorCompleted.getInt(0))
        cursorCompleted.close()

        val cursorFailed = v16Db.query("SELECT dispatchClaimCount FROM pending_external_operations WHERE businessTransactionId = 'tx_failed'")
        assertTrue(cursorFailed.moveToFirst())
        assertEquals(0, cursorFailed.getInt(0))
        cursorFailed.close()

        v16Db.close()
        helper.close()
        dbFile.delete()
    }
}
