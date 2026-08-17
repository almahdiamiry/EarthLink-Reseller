package com.example

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.SyncOutbox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented test suite verifying Room SQLite transactional lifecycle,
 * import atomicity, and filesystem backup/restore storage on Android runtime.
 *
 * Covers Invariants: INV-02, INV-03, INV-04, INV-13
 */
@RunWith(AndroidJUnit4::class)
class RestoreImportInstrumentedTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testRoomDatabaseCreationAndTransactionOnRealAndroid() = runBlocking {
        val account = LocalAccount(
            id = "acc-instr-001",
            displayName = "Ahmed Al-Iraqi",
            earthlinkUsername = "ahmed_user",
            debtIqd = 35000.0,
            openingDebtIqd = 35000.0
        )

        val ledgerEntry = LocalLedgerEntry(
            id = "ldg-instr-001",
            accountId = "acc-instr-001",
            typeRaw = "took",
            amountIqd = 35000.0,
            debtAfterIqd = 35000.0,
            note = "Initial Subscription Debt"
        )

        database.withTransaction {
            database.localAccountDao().insert(account)
            database.localLedgerEntryDao().insert(ledgerEntry)
        }

        val fetchedAccount = database.localAccountDao().getByIdOneShot("acc-instr-001")
        assertNotNull("Account must be successfully inserted and retrieved", fetchedAccount)
        assertEquals("Ahmed Al-Iraqi", fetchedAccount?.displayName)
        assertEquals(35000.0, fetchedAccount?.debtIqd ?: 0.0, 0.001)

        val fetchedLedgers = database.localLedgerEntryDao().getByAccountIdOneShot("acc-instr-001")
        assertEquals(1, fetchedLedgers.size)
        assertEquals("ldg-instr-001", fetchedLedgers[0].id)
    }

    @Test
    fun testImportAtomicityAndTransactionRollbackOnAndroidRuntime() = runBlocking {
        val account = LocalAccount(
            id = "acc-rollback-001",
            displayName = "Rollback Target Account",
            debtIqd = 20000.0
        )

        try {
            database.withTransaction {
                database.localAccountDao().insert(account)

                // Simulate catastrophic error mid-batch import
                throw IllegalStateException("Simulated mid-import I/O parser corruption")
            }
            fail("Transaction must throw exception and abort")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated mid-import I/O parser corruption", e.message)
        }

        // Verify total rollback on Android SQLite
        val fetchedAccount = database.localAccountDao().getByIdOneShot("acc-rollback-001")
        assertEquals("Database must contain 0 partial records after rollback", null, fetchedAccount)
        assertEquals(0, database.localAccountDao().getTotalCount())
    }

    @Test
    fun testRestoreDirectoryLifecycleAndFileIsolation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val cacheBackupDir = File(context.cacheDir, "backups").apply {
            if (!exists()) mkdirs()
        }
        assertTrue("Cache backups directory must exist", cacheBackupDir.exists() && cacheBackupDir.isDirectory)

        val testBackupFile = File(cacheBackupDir, "test_backup_archive.zip")
        testBackupFile.writeText("SIMULATED_ENCRYPTED_ZIP_PAYLOAD")
        assertTrue("Test backup archive must be created on disk", testBackupFile.exists())
        assertEquals(31, testBackupFile.length())

        // Cleanup
        testBackupFile.delete()
        assertEquals(false, testBackupFile.exists())
    }

    @Test
    fun testCursorResetAndOutboxIsolation() = runBlocking {
        // Seed sync cursors
        database.syncMetadataDao().put("cursor_accounts", "1700000000000_doc123")
        database.syncMetadataDao().put("cursor_ledger", "1700000000000_doc456")

        assertEquals("1700000000000_doc123", database.syncMetadataDao().get("cursor_accounts"))

        // Seed outbox items
        database.syncOutboxDao().insert(
            SyncOutbox(
                entityType = "local_accounts",
                entityId = "acc-001",
                operation = "upsert",
                payloadJson = "{}",
                status = "pending"
            )
        )
        assertEquals(1, database.syncOutboxDao().getAllUnsyncedCount())

        // Simulate restore reset protocol
        database.withTransaction {
            database.syncMetadataDao().deleteAll()
            database.syncOutboxDao().clearPendingByEntityType("local_accounts")
        }

        assertEquals("Cursors must be cleared after restore reset", null, database.syncMetadataDao().get("cursor_accounts"))
        assertEquals("Outbox must be 0 after restore reset", 0, database.syncOutboxDao().getAllUnsyncedCount())
    }
}
