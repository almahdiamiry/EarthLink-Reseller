package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.DivergentPayloadConflictException
import com.example.core.model.LocalAccount
import com.example.core.sync.UtowerImporter
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
 * Phase 4 G5 Identity & Import Collision Safety Test Suite (P4-G5-REQ-01 / P4-G5-REQ-02 / P4-G5-REQ-03 / P4-G5-REQ-04).
 *
 * Verifies that:
 * 1. Runtime idempotency keys reused across retries return the accepted transaction without duplicating ledger rows.
 * 2. Distinct legitimate mutations receive distinct IDs and create separate ledger records.
 * 3. Concurrent invocations with the same idempotency key produce exactly 1 ledger record.
 * 4. Same idempotency key with divergent payload fails closed with DivergentPayloadConflictException (INV-01).
 * 5. uTower Importer handles two distinct rows with identical business fields by assigning stable distinct provenance coordinates.
 * 6. Repeated uTower import of the same source file is 100% idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase4RuntimeLedgerIdentityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var accountRepo: LocalAccountRepository
    private lateinit var ledgerRepo: LocalLedgerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()
        pendingDao = db.pendingExternalOperationDao()

        accountRepo = LocalAccountRepositoryImpl(
            database = db,
            accountDao = accountDao,
            outboxDao = outboxDao
        )
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = outboxDao,
            pendingDao = pendingDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createUtowerJsonFile(accounts: List<JSONObject>, transactions: List<JSONObject>): File {
        val file = File(context.cacheDir, "utower_identity_${UUID.randomUUID()}.json")
        val root = JSONObject().apply {
            val liveObj = JSONObject()
            for ((idx, sub) in accounts.withIndex()) {
                val key = sub.optString("id", sub.optString("key", "sub_$idx"))
                liveObj.put(key, sub)
            }
            put("live_users", liveObj)

            val txObj = JSONObject()
            for ((idx, tx) in transactions.withIndex()) {
                val key = tx.optString("id", tx.optString("key", "tx_$idx"))
                txObj.put(key, tx)
            }
            put("messagesofhistory", txObj)
        }
        file.writeText(root.toString(), Charsets.UTF_8)
        return file
    }

    @Test
    fun testPaymentIdempotencyKeyReuse_returnsExistingRow() = runBlocking {
        val account = LocalAccount(id = "acc_1", displayName = "User 1", debtIqd = 10000.0)
        accountRepo.saveAccount(account)

        val key = "pay_key_123"
        val entry1 = ledgerRepo.addPayment(account.id, 4000.0, "Test payment", key)
        assertEquals(key, entry1.id)
        assertEquals(4000.0, entry1.amountIqd, 0.001)

        val accAfter1 = accountRepo.getAccountByIdOneShot(account.id)
        assertEquals(6000.0, accAfter1?.debtIqd ?: 0.0, 0.001)

        // Replay same idempotency key with identical payload
        val entry2 = ledgerRepo.addPayment(account.id, 4000.0, "Test payment", key)
        assertEquals(entry1.id, entry2.id)

        // Ledger count must remain exactly 1, balance must NOT decrease twice
        val allEntries = db.localLedgerEntryDao().getByAccountIdOneShot(account.id)
        assertEquals(1, allEntries.size)

        val accAfter2 = accountRepo.getAccountByIdOneShot(account.id)
        assertEquals(6000.0, accAfter2?.debtIqd ?: 0.0, 0.001)
    }

    @Test
    fun testDebtIdempotencyKeyReuse_returnsExistingRow() = runBlocking {
        val account = LocalAccount(id = "acc_2", displayName = "User 2", debtIqd = 0.0)
        accountRepo.saveAccount(account)

        val key = "debt_key_456"
        val entry1 = ledgerRepo.addDebt(account.id, 5000.0, "Test debt", key)
        assertEquals(key, entry1.id)
        assertEquals(5000.0, entry1.amountIqd, 0.001)

        val accAfter1 = accountRepo.getAccountByIdOneShot(account.id)
        assertEquals(5000.0, accAfter1?.debtIqd ?: 0.0, 0.001)

        // Replay same idempotency key with identical payload
        val entry2 = ledgerRepo.addDebt(account.id, 5000.0, "Test debt", key)
        assertEquals(entry1.id, entry2.id)

        val allEntries = db.localLedgerEntryDao().getByAccountIdOneShot(account.id)
        assertEquals(1, allEntries.size)

        val accAfter2 = accountRepo.getAccountByIdOneShot(account.id)
        assertEquals(5000.0, accAfter2?.debtIqd ?: 0.0, 0.001)
    }

    @Test
    fun testDistinctIdempotencyKeys_createDistinctRows() = runBlocking {
        val account = LocalAccount(id = "acc_3", displayName = "User 3", debtIqd = 10000.0)
        accountRepo.saveAccount(account)

        val entry1 = ledgerRepo.addPayment(account.id, 2000.0, "Payment 1", "key_p1")
        val entry2 = ledgerRepo.addPayment(account.id, 3000.0, "Payment 2", "key_p2")

        assertNotEquals(entry1.id, entry2.id)
        val allEntries = db.localLedgerEntryDao().getByAccountIdOneShot(account.id)
        assertEquals(2, allEntries.size)

        val accAfter = accountRepo.getAccountByIdOneShot(account.id)
        assertEquals(5000.0, accAfter?.debtIqd ?: 0.0, 0.001)
    }

    @Test
    fun testSameIdDivergentPayload_throwsConflict() = runBlocking {
        val account = LocalAccount(id = "acc_4", displayName = "User 4", debtIqd = 10000.0)
        accountRepo.saveAccount(account)

        val key = "pay_divergent_key"
        ledgerRepo.addPayment(account.id, 2000.0, "Initial payment", key)

        try {
            // Divergent amount (5000 instead of 2000)
            ledgerRepo.addPayment(account.id, 5000.0, "Modified payment", key)
            fail("Expected DivergentPayloadConflictException")
        } catch (e: DivergentPayloadConflictException) {
            assertTrue(e.message?.contains("divergent payload conflict") == true)
        }

        // Ledger must still have only 1 row with original amount
        val allEntries = db.localLedgerEntryDao().getByAccountIdOneShot(account.id)
        assertEquals(1, allEntries.size)
        assertEquals(2000.0, allEntries[0].amountIqd, 0.001)
    }

    @Test
    fun testConcurrentSameKeyPayment_producesExactlyOneEntry() = runBlocking {
        val account = LocalAccount(id = "acc_5", displayName = "User 5", debtIqd = 10000.0)
        accountRepo.saveAccount(account)

        val key = "concurrent_pay_key"
        val deferreds = (1..5).map {
            async(Dispatchers.IO) {
                ledgerRepo.addPayment(account.id, 2000.0, "Concurrent tap", key)
            }
        }
        val results = deferreds.awaitAll()

        assertEquals(5, results.size)
        results.forEach { assertEquals(key, it.id) }

        val allEntries = db.localLedgerEntryDao().getByAccountIdOneShot(account.id)
        assertEquals(1, allEntries.size)
        val finalAcc = accountRepo.getAccountByIdOneShot(account.id)
        assertEquals(8000.0, finalAcc?.debtIqd ?: 0.0, 0.001)
    }

    @Test
    fun testUtowerImporter_twoIdenticalLegitimateRows_remainDistinct() = runBlocking {
        val importer = UtowerImporter(context, db)

        val accountJson = JSONObject().apply {
            put("id", "sub_utower_1")
            put("fullName", "Subscriber 1")
            put("phone", "07700000001")
            put("debt", 0)
        }

        // Two identical transaction rows in the uTower file (same timestamp, same amount, same type, no sourceKey)
        val txJson1 = JSONObject().apply {
            put("toWho", "sub_utower_1")
            put("type", "took")
            put("amount", 25000)
            put("date", "2026-01-01 10:00:00")
            put("comment", "Card refill")
        }
        val txJson2 = JSONObject().apply {
            put("toWho", "sub_utower_1")
            put("type", "took")
            put("amount", 25000)
            put("date", "2026-01-01 10:00:00")
            put("comment", "Card refill")
        }

        val file = createUtowerJsonFile(listOf(accountJson), listOf(txJson1, txJson2))
        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue(result.success)
        assertEquals(2, result.transactionsImported)

        val accounts = db.localAccountDao().getAllOneShot()
        assertEquals(1, accounts.size)

        val entries = db.localLedgerEntryDao().getByAccountIdOneShot(accounts[0].id)
        assertEquals("Both distinct legitimate rows must be inserted", 2, entries.size)
        assertNotEquals("Distinct rows must receive distinct stable IDs", entries[0].id, entries[1].id)
    }

    @Test
    fun testUtowerImporter_repeatedImport_isIdempotent() = runBlocking {
        val importer = UtowerImporter(context, db)

        val accountJson = JSONObject().apply {
            put("id", "sub_utower_2")
            put("fullName", "Subscriber 2")
            put("phone", "07700000002")
            put("debt", 0)
        }

        val txJson = JSONObject().apply {
            put("toWho", "sub_utower_2")
            put("type", "took")
            put("amount", 35000)
            put("date", "2026-02-01 12:00:00")
            put("comment", "Monthly renewal")
        }

        val file = createUtowerJsonFile(listOf(accountJson), listOf(txJson))
        val result1 = importer.importFromFile(file, shouldReplace = false)
        assertTrue(result1.success)
        assertEquals(1, result1.transactionsImported)

        val accounts1 = db.localAccountDao().getAllOneShot()
        val entries1 = db.localLedgerEntryDao().getByAccountIdOneShot(accounts1[0].id)
        assertEquals(1, entries1.size)

        // Re-importing exact same file
        val result2 = importer.importFromFile(file, shouldReplace = false)
        assertTrue(result2.success)

        val accounts2 = db.localAccountDao().getAllOneShot()
        assertEquals(1, accounts2.size)

        val entries2 = db.localLedgerEntryDao().getByAccountIdOneShot(accounts2[0].id)
        assertEquals("Repeated import must NOT create duplicate entries", 1, entries2.size)
        assertEquals(entries1[0].id, entries2[0].id)
    }
}
