package com.example.core.sync

import com.example.core.model.SyncOutbox
import com.example.core.database.SyncOutboxDao
import org.json.JSONObject
import java.util.UUID

/**
 * Authoritative protocol manager for Sync Outbox lifecycle operations.
 * Enforces atomic transaction-scoped outbox updates, deduplication policies,
 * and status transitions (in-flight, succeeded, failure, dead-letter).
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
        outboxDao.insert(item)
        return item
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
        outboxDao.insert(item)
        return item
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
     * Marks outbox items as successfully committed to remote storage by purging them from the outbox.
     */
    suspend fun markSucceeded(
        outboxDao: SyncOutboxDao,
        outboxIds: List<Int>
    ) {
        if (outboxIds.isEmpty()) return
        outboxIds.forEach { id ->
            outboxDao.deleteById(id)
        }
    }

    /**
     * Marks outbox items as having failed a retryable sync attempt.
     */
    suspend fun markRetryableFailure(
        outboxDao: SyncOutboxDao,
        items: List<SyncOutbox>,
        errorReason: String
    ) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        items.forEach { item ->
            outboxDao.update(
                item.copy(
                    status = "failed",
                    attemptCount = item.attemptCount + 1,
                    lastError = errorReason,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * Marks outbox items as permanently un-syncable (dead letter queue).
     */
    suspend fun markDeadLetter(
        outboxDao: SyncOutboxDao,
        items: List<SyncOutbox>,
        errorReason: String
    ) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        items.forEach { item ->
            outboxDao.update(
                item.copy(
                    status = "dead_letter",
                    attemptCount = item.attemptCount + 1,
                    lastError = errorReason,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * Retrieves all pending or failed outbox records queued for synchronization.
     */
    suspend fun getPending(outboxDao: SyncOutboxDao): List<SyncOutbox> {
        return outboxDao.getPending()
    }

    /**
     * Retrieves all outbox records that are retryable (attemptCount < 10).
     */
    suspend fun getRetryable(outboxDao: SyncOutboxDao): List<SyncOutbox> {
        return outboxDao.getPending().filter { it.attemptCount < 10 }
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
     * Dead-letter entries are explicitly not considered active.
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
     * Resets stuck 'syncing' entries back to 'pending'.
     */
    suspend fun resetSyncingToPending(outboxDao: SyncOutboxDao): Int {
        return outboxDao.resetSyncingToPending()
    }
}

