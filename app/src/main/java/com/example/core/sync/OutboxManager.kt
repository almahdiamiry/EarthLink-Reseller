package com.example.core.sync

import com.example.core.model.SyncOutbox
import com.example.core.database.SyncOutboxDao
import org.json.JSONObject
import java.util.UUID

/**
 * Authoritative protocol manager for Sync Outbox lifecycle operations (INV-13 / P1-G2-REQ-01).
 * Enforces atomic transaction-scoped outbox updates, deduplication policies,
 * and durable status transitions (in-flight, succeeded, failed/retryable).
 *
 * Terminal "dead_letter" semantics are strictly prohibited. Every mutation remains
 * durable in the local SQLite database and retryable with bounded backoff until
 * confirmed by the remote server.
 */
object OutboxManager {

    /**
     * Helper to embed syncMutationId in payloadJson of an outbox item.
     */
    private fun injectMutationId(payloadJson: String): String {
        return try {
            val json = JSONObject(payloadJson)
            if (!json.has("syncMutationId")) {
                json.put("syncMutationId", UUID.randomUUID().toString())
            }
            json.toString()
        } catch (e: Exception) {
            payloadJson
        }
    }

    /**
     * Enqueues a new [SyncOutbox] entry without clearing existing entries.
     */
    suspend fun enqueue(
        outboxDao: SyncOutboxDao,
        entityType: String,
        entityId: String,
        operation: String,
        payloadJson: String,
        errorReason: String? = null,
        importBatchId: String? = null
    ): SyncOutbox {
        val finalPayload = injectMutationId(payloadJson)
        val item = SyncOutbox(
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            payloadJson = finalPayload,
            status = "pending",
            lastError = errorReason,
            createdAt = System.currentTimeMillis(),
            importBatchId = importBatchId
        )
        val rowId = outboxDao.insert(item)
        return item.copy(id = rowId.toInt())
    }

    /**
     * Clears any existing pending outbox entries for [entityId] and [entityType] (deduplication policy)
     * and enqueues a new pending [SyncOutbox] entry.
     */
    suspend fun enqueueOrReplace(
        outboxDao: SyncOutboxDao,
        entityType: String,
        entityId: String,
        operation: String,
        payloadJson: String,
        errorReason: String? = null,
        importBatchId: String? = null
    ): SyncOutbox {
        outboxDao.clearPendingByEntity(entityId, entityType)
        val finalPayload = injectMutationId(payloadJson)
        val item = SyncOutbox(
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            payloadJson = finalPayload,
            status = "pending",
            lastError = errorReason,
            createdAt = System.currentTimeMillis(),
            importBatchId = importBatchId
        )
        val rowId = outboxDao.insert(item)
        return item.copy(id = rowId.toInt())
    }

    /**
     * Clears any pending outbox entries for [entityId] and enqueues an upsert entry.
     * Must be called inside an existing transaction.
     */
    suspend fun upsertWithOutbox(
        outboxDao: SyncOutboxDao,
        entityType: String,
        entityId: String,
        payloadJson: String,
        errorReason: String? = null,
        importBatchId: String? = null
    ): SyncOutbox {
        return enqueueOrReplace(
            outboxDao = outboxDao,
            entityType = entityType,
            entityId = entityId,
            operation = "upsert",
            payloadJson = payloadJson,
            errorReason = errorReason,
            importBatchId = importBatchId
        )
    }

    /**
     * Clears any pending outbox entries for [entityId] and enqueues a delete tombstone.
     * Must be called inside an existing transaction.
     */
    suspend fun deleteWithTombstone(
        outboxDao: SyncOutboxDao,
        entityType: String,
        entityId: String,
        payloadJson: String = "{}"
    ) {
        val finalPayload = injectMutationId(payloadJson)
        enqueueOrReplace(
            outboxDao = outboxDao,
            entityType = entityType,
            entityId = entityId,
            operation = "delete",
            payloadJson = finalPayload
        )
    }

    /**
     * Clears pending outbox entries for a list of [entityIds] and enqueues delete tombstones.
     * Useful for batch deletions.
     */
    suspend fun deleteWithTombstoneBatch(
        outboxDao: SyncOutboxDao,
        entityType: String,
        entityIds: List<String>,
        payloadJson: String = "{}"
    ) {
        if (entityIds.isEmpty()) return
        outboxDao.clearPendingByEntityIds(entityIds, entityType)
        val now = System.currentTimeMillis()
        val tombstones = entityIds.map { id ->
            val finalPayload = injectMutationId(payloadJson)
            SyncOutbox(
                entityType = entityType,
                entityId = id,
                operation = "delete",
                payloadJson = finalPayload,
                status = "pending",
                createdAt = now
            )
        }
        outboxDao.insertAll(tombstones)
    }

    /**
     * Marks a list of outbox items as in-flight ("syncing") and increments their attempt count.
     */
    suspend fun markInFlight(
        outboxDao: SyncOutboxDao,
        items: List<SyncOutbox>
    ): List<SyncOutbox> {
        if (items.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val updatedItems = items.map {
            it.copy(
                status = "syncing",
                attemptCount = it.attemptCount + 1,
                updatedAt = now
            )
        }
        updatedItems.forEach { outboxDao.update(it) }
        return updatedItems
    }

    /**
     * Marks a single outbox item as in-flight ("syncing") and increments its attempt count.
     */
    suspend fun markInFlight(
        outboxDao: SyncOutboxDao,
        item: SyncOutbox
    ): SyncOutbox {
        val updated = markInFlight(outboxDao, listOf(item))
        return updated.first()
    }

    /**
     * Marks outbox items as successfully committed to remote storage by purging them from the outbox.
     */
    suspend fun markSucceeded(
        outboxDao: SyncOutboxDao,
        outboxIds: List<Int>
    ) {
        if (outboxIds.isEmpty()) return
        outboxDao.deleteByIds(outboxIds)
    }

    /**
     * Marks a single outbox item as successfully committed to remote storage.
     */
    suspend fun markSucceeded(
        outboxDao: SyncOutboxDao,
        outboxId: Int
    ) {
        outboxDao.deleteById(outboxId)
    }

    /**
     * Marks outbox items as having failed a sync attempt.
     * Records bounded error diagnostic metadata and ensures obligations remain
     * durable and retryable in "failed" status (INV-13 / P1-G2-REQ-02).
     */
    suspend fun markRetryableFailure(
        outboxDao: SyncOutboxDao,
        items: List<SyncOutbox>,
        errorReason: String
    ) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val sanitizedError = errorReason.take(1000)
        items.forEach { item ->
            val updatedCount = if (item.status == "syncing") item.attemptCount else item.attemptCount + 1
            outboxDao.update(
                item.copy(
                    status = "failed",
                    attemptCount = updatedCount,
                    lastError = sanitizedError,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * Marks a single outbox item as having failed a sync attempt.
     */
    suspend fun markRetryableFailure(
        outboxDao: SyncOutboxDao,
        item: SyncOutbox,
        errorReason: String
    ) {
        markRetryableFailure(outboxDao, listOf(item), errorReason)
    }

    /**
     * Retrieves all pending or failed outbox records queued for synchronization.
     */
    suspend fun getPending(outboxDao: SyncOutboxDao): List<SyncOutbox> {
        return outboxDao.getPending()
    }

    /**
     * Retrieves all outbox records that are retryable.
     * Per INV-13 and P1-G2-REQ-01, all pending and failed obligations remain durable and retryable indefinitely.
     */
    suspend fun getRetryable(outboxDao: SyncOutboxDao): List<SyncOutbox> {
        return outboxDao.getPending()
    }

    /**
     * Clears pending outbox entries, optionally filtered by [entityType].
     */
    suspend fun clear(outboxDao: SyncOutboxDao, entityType: String? = null) {
        if (entityType != null) {
            outboxDao.clearPendingByEntityType(entityType)
        } else {
            outboxDao.resetSyncingToPending()
        }
    }

    /**
     * Clears pending outbox entries for a specific entity.
     */
    suspend fun clearByEntity(outboxDao: SyncOutboxDao, entityId: String, entityType: String) {
        outboxDao.clearPendingByEntity(entityId, entityType)
    }

    /**
     * Checks if an entity has any active (pending/syncing/failed) unsynced outbox entries.
     */
    suspend fun hasActiveMutation(outboxDao: SyncOutboxDao, entityId: String, entityType: String): Boolean {
        return outboxDao.hasActiveMutation(entityId, entityType) > 0
    }

    /**
     * Retrieves outbox entries for a specific entity.
     */
    suspend fun getByEntity(outboxDao: SyncOutboxDao, entityId: String, entityType: String): List<SyncOutbox> {
        return outboxDao.getByEntity(entityId, entityType)
    }

    /**
     * Resets stuck 'syncing' entries back to 'pending' upon startup / crash recovery.
     */
    suspend fun resetInFlight(outboxDao: SyncOutboxDao): Int {
        return outboxDao.resetSyncingToPending()
    }

    /**
     * Resets stuck 'syncing' entries back to 'pending'.
     */
    suspend fun resetSyncingToPending(outboxDao: SyncOutboxDao): Int {
        return outboxDao.resetSyncingToPending()
    }
}


