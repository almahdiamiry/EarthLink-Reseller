package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SyncOutboxDao
import com.example.core.model.SyncOutbox
import com.example.core.sync.OutboxManager
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
 * Phase 1 Outbox Durability & Anti-Dead-Letter Test Suite (INV-13 / P1-G2-REQ-01).
 *
 * Verifies that:
 * 1. Outbox obligations are permanently durable and never dropped or blackholed.
 * 2. Status transitions are strictly non-terminal (pending, syncing, failed).
 * 3. Attempt count increments on failure without dropping the obligation.
 * 4. High failure populations maintain fairness, progress, and bounded diagnostic footprint.
 * 5. Poison items remain isolated and observable without corrupting adjacent obligations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1OutboxDurabilityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var outboxDao: SyncOutboxDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outboxDao = db.syncOutboxDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. Attempt count increases on failure without obligation deletion
    @Test
    fun case1_attemptCountIncreasesWithoutObligationDeletion() = runBlocking {
        val entityId = "acc_durability_01"
        val payload = """{"id":"$entityId","name":"Test Account"}"""
        val item = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = entityId,
            operation = "upsert",
            payloadJson = payload
        )

        assertEquals("pending", item.status)
        assertEquals(0, item.attemptCount)

        // Simulate 15 consecutive failed sync cycles
        for (i in 1..15) {
            val pending = outboxDao.getPending()
            assertEquals(1, pending.size)
            OutboxManager.markRetryableFailure(
                outboxDao = outboxDao,
                items = pending,
                errorReason = "Simulated network timeout cycle #$i"
            )
        }

        val survivor = outboxDao.getByEntity(entityId, "local_accounts")
        assertEquals(1, survivor.size)
        val survivorItem = survivor.first()
        assertEquals("failed", survivorItem.status)
        assertEquals(15, survivorItem.attemptCount)
        assertEquals("Simulated network timeout cycle #15", survivorItem.lastError)
        assertEquals(1, outboxDao.getRetryableCount())
        assertEquals(1, outboxDao.getAllUnsyncedCount())
    }

    // 2. Long-running failure keeps the row retryable in getPending() and getRetryable()
    @Test
    fun case2_longRunningFailure_remainsRetryableInGetPending() = runBlocking {
        val entityId = "tx_durability_50"
        val payload = """{"id":"$entityId","amount":5000.0}"""
        OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = entityId,
            operation = "upsert",
            payloadJson = payload
        )

        // 50 consecutive failed attempts
        for (i in 1..50) {
            val pending = outboxDao.getPending()
            OutboxManager.markRetryableFailure(
                outboxDao = outboxDao,
                items = pending,
                errorReason = "Persistent 503 Service Unavailable attempt $i"
            )
        }

        val pendingList = OutboxManager.getPending(outboxDao)
        assertEquals(1, pendingList.size)
        assertEquals(entityId, pendingList.first().entityId)
        assertEquals(50, pendingList.first().attemptCount)

        val retryableList = OutboxManager.getRetryable(outboxDao)
        assertEquals(1, retryableList.size)
        assertEquals(entityId, retryableList.first().entityId)

        assertEquals(1, outboxDao.getFailedCount())
        assertEquals(1, outboxDao.getRetryableCount())
    }

    // 3. No dead_letter status can be produced under any failure sequence
    @Test
    fun case3_noDeadLetterStatusCanBeProduced() = runBlocking {
        val entityIds = (1..20).map { "batch_entity_$it" }
        for (id in entityIds) {
            OutboxManager.enqueue(
                outboxDao = outboxDao,
                entityType = "import_batches",
                entityId = id,
                operation = "upsert",
                payloadJson = """{"id":"$id"}"""
            )
        }

        // Stress through multiple failure loops
        for (round in 1..12) {
            val pending = outboxDao.getPending()
            OutboxManager.markRetryableFailure(
                outboxDao = outboxDao,
                items = pending,
                errorReason = "Failure round $round"
            )
        }

        val allItems = outboxDao.getAllOneShot()
        assertEquals(20, allItems.size)
        assertTrue("No outbox items may ever have dead_letter status", allItems.none { it.status == "dead_letter" })
        assertTrue("All items must be in 'failed' status", allItems.all { it.status == "failed" })
        assertTrue("All items must have attemptCount == 12", allItems.all { it.attemptCount == 12 })
    }

    // 4. Poison item remains isolated and observable without dropping or blocking adjacent items
    @Test
    fun case4_poisonItemRemainsIsolatedAndObservable() = runBlocking {
        // Enqueue valid item A, poison item B, and valid item C
        val itemA = OutboxManager.enqueue(outboxDao, "local_accounts", "acc_valid_A", "upsert", """{"id":"acc_valid_A"}""")
        val itemB = OutboxManager.enqueue(outboxDao, "local_accounts", "acc_poison_B", "upsert", """{"id":"acc_poison_B","malformed":true}""")
        val itemC = OutboxManager.enqueue(outboxDao, "local_accounts", "acc_valid_C", "upsert", """{"id":"acc_valid_C"}""")

        assertEquals(3, outboxDao.getPending().size)

        // Pass 1: Items A & C succeed, Item B fails
        OutboxManager.markSucceeded(outboxDao, listOf(itemA.id, itemC.id))
        OutboxManager.markRetryableFailure(outboxDao, listOf(itemB), "Schema validation rejection on poison item")

        val remainingAfterPass1 = outboxDao.getPending()
        assertEquals(1, remainingAfterPass1.size)
        assertEquals("acc_poison_B", remainingAfterPass1.first().entityId)
        assertEquals("failed", remainingAfterPass1.first().status)
        assertEquals(1, remainingAfterPass1.first().attemptCount)

        // Pass 2: Enqueue new valid item D
        val itemD = OutboxManager.enqueue(outboxDao, "local_accounts", "acc_valid_D", "upsert", """{"id":"acc_valid_D"}""")
        val pendingPass2 = outboxDao.getPending()
        assertEquals(2, pendingPass2.size)

        // Item D succeeds, Item B fails again
        OutboxManager.markSucceeded(outboxDao, listOf(itemD.id))
        OutboxManager.markRetryableFailure(outboxDao, listOf(remainingAfterPass1.first()), "Schema rejection again")

        val finalPending = outboxDao.getPending()
        assertEquals(1, finalPending.size)
        assertEquals("acc_poison_B", finalPending.first().entityId)
        assertEquals(2, finalPending.first().attemptCount)
        assertEquals(1, outboxDao.getFailedCount())
    }

    // 5. Outbox rows survive multiple failed sync attempts without payload or metadata data loss
    @Test
    fun case5_outboxRowsSurviveMultipleFailedSyncAttemptsWithoutDataLoss() = runBlocking {
        val richPayload = """{"id":"acc_rich","displayName":"أحمد محمد","debtIqd":150000.0,"notes":"ملاحظة هامة"}"""
        val original = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = "acc_rich",
            operation = "upsert",
            payloadJson = richPayload,
            importBatchId = "batch_alpha_01"
        )

        for (cycle in 1..25) {
            val pending = outboxDao.getPending()
            OutboxManager.markRetryableFailure(outboxDao, pending, "Transient failure $cycle")
        }

        val survivor = outboxDao.getByEntity("acc_rich", "local_accounts").first()
        assertEquals("acc_rich", survivor.entityId)
        assertEquals("local_accounts", survivor.entityType)
        assertEquals("upsert", survivor.operation)
        assertEquals("batch_alpha_01", survivor.importBatchId)
        assertEquals(original.createdAt, survivor.createdAt)
        assertEquals(25, survivor.attemptCount)
        assertTrue(survivor.payloadJson.contains("أحمد محمد"))
        assertTrue(survivor.payloadJson.contains("150000"))
    }

    // 6. Stress evidence covering large retained failure population and valid item placed behind it
    @Test
    fun case6_stressRetainedFailurePopulation_withValidItemBehindIt_fairnessAndBoundedDiagnostics() = runBlocking {
        val populationSize = 100
        val now = System.currentTimeMillis()

        // Create 100 failed items in the past with high attempt counts
        val failedItems = (1..populationSize).map { i ->
            SyncOutbox(
                entityType = "local_ledger_entries",
                entityId = "tx_fail_$i",
                operation = "upsert",
                payloadJson = """{"id":"tx_fail_$i","amount":$i.0}""",
                status = "failed",
                attemptCount = 20 + (i % 30),
                lastError = "A".repeat(2000), // Deliberately long error string
                createdAt = now - (populationSize - i + 1) * 1000L,
                updatedAt = now - (populationSize - i + 1) * 1000L
            )
        }
        outboxDao.insertAll(failedItems)

        // Place a fresh valid item strictly behind the 100 failed items
        val validItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = "acc_fresh_tail",
            operation = "upsert",
            payloadJson = """{"id":"acc_fresh_tail"}"""
        )

        // 1. Verify fairness: All 101 items are retrieved in createdAt order
        val allPending = OutboxManager.getPending(outboxDao)
        assertEquals(populationSize + 1, allPending.size)
        assertEquals("acc_fresh_tail", allPending.last().entityId)

        // 2. Mark retryable failure with oversized diagnostic string and verify bounded metadata footprint
        val oversizedError = "E".repeat(5000)
        OutboxManager.markRetryableFailure(outboxDao, listOf(allPending.first()), oversizedError)

        val updatedFirst = outboxDao.getByEntity(allPending.first().entityId, allPending.first().entityType).first()
        assertTrue("Error diagnostic string must be bounded in length", (updatedFirst.lastError?.length ?: 0) <= 1000)

        // 3. Process valid item at tail: it succeeds and leaves the 100 failed items intact
        OutboxManager.markSucceeded(outboxDao, listOf(validItem.id))
        val remainingPending = outboxDao.getPending()
        assertEquals(populationSize, remainingPending.size)
        assertTrue(remainingPending.none { it.entityId == "acc_fresh_tail" })
    }

    // 7. resetFailedItems resets status to pending and clears attemptCount and lastError
    @Test
    fun case7_resetFailedItems_resetsAllFailedItemsToPending() = runBlocking {
        val items = (1..5).map { i ->
            SyncOutbox(
                entityType = "local_accounts",
                entityId = "acc_reset_$i",
                operation = "upsert",
                payloadJson = """{"id":"acc_reset_$i"}""",
                status = "failed",
                attemptCount = 8,
                lastError = "Error $i"
            )
        }
        outboxDao.insertAll(items)

        assertEquals(5, outboxDao.getFailedCount())
        assertEquals(5, outboxDao.getFailedItems().size)

        val resetCount = outboxDao.resetFailedItems()
        assertEquals(5, resetCount)
        assertEquals(0, outboxDao.getFailedCount())

        val pending = outboxDao.getPending()
        assertEquals(5, pending.size)
        assertTrue(pending.all { it.status == "pending" })
        assertTrue(pending.all { it.attemptCount == 0 })
        assertTrue(pending.all { it.lastError == null })
    }

    // 8. In-flight crash recovery resets syncing items to pending
    @Test
    fun case8_inFlightCrashRecovery_resetsToPending() = runBlocking {
        val item1 = OutboxManager.enqueue(outboxDao, "local_accounts", "acc_crash_1", "upsert", "{}")
        val item2 = OutboxManager.enqueue(outboxDao, "local_accounts", "acc_crash_2", "upsert", "{}")

        OutboxManager.markInFlight(outboxDao, listOf(item1, item2))
        assertEquals(2, outboxDao.getInFlightCount())

        // Simulate crash recovery
        val recovered = OutboxManager.resetSyncingToPending(outboxDao)
        assertEquals(2, recovered)
        assertEquals(0, outboxDao.getInFlightCount())

        val pending = outboxDao.getPending()
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.status == "pending" })
    }
}
