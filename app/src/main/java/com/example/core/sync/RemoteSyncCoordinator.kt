package com.example.core.sync

import android.util.Log
import androidx.room.withTransaction
import com.example.core.database.*
import com.example.core.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Origin source of incoming remote synchronization events.
 */
enum class RemoteEventSource {
    REALTIME,
    PULL,
    BOOTSTRAP,
    MANUAL
}

/**
 * Canonical representation of an incoming remote event.
 */
sealed interface RemoteEvent {
    val entityType: String
    val entityId: String
    val remoteVersion: Long
    val source: RemoteEventSource
    val operation: String
    val syncMutationId: String?
    val deduplicationKey: String
        get() = "$entityType:$entityId:$remoteVersion:$operation"

    data class AccountUpsert(
        override val entityId: String,
        override val remoteVersion: Long,
        override val source: RemoteEventSource,
        val account: LocalAccount,
        override val syncMutationId: String? = null
    ) : RemoteEvent {
        override val entityType: String = "local_accounts"
        override val operation: String = "UPSERT"
    }

    data class AccountDelete(
        override val entityId: String,
        override val remoteVersion: Long,
        override val source: RemoteEventSource,
        override val syncMutationId: String? = null
    ) : RemoteEvent {
        override val entityType: String = "local_accounts"
        override val operation: String = "DELETE"
    }

    data class LedgerUpsert(
        override val entityId: String,
        override val remoteVersion: Long,
        override val source: RemoteEventSource,
        val entry: LocalLedgerEntry,
        val preFetchedParentAccount: LocalAccount? = null,
        override val syncMutationId: String? = null
    ) : RemoteEvent {
        override val entityType: String = "local_ledger_entries"
        override val operation: String = "UPSERT"
    }

    data class LedgerDelete(
        override val entityId: String,
        override val remoteVersion: Long,
        override val source: RemoteEventSource,
        override val syncMutationId: String? = null
    ) : RemoteEvent {
        override val entityType: String = "local_ledger_entries"
        override val operation: String = "DELETE"
    }

    data class BatchUpsert(
        override val entityId: String,
        override val remoteVersion: Long,
        override val source: RemoteEventSource,
        val batch: ImportBatch,
        override val syncMutationId: String? = null
    ) : RemoteEvent {
        override val entityType: String = "import_batches"
        override val operation: String = "UPSERT"
    }

    data class UserSettingsUpdate(
        override val entityId: String,
        override val remoteVersion: Long,
        override val source: RemoteEventSource,
        val settingsJson: String,
        override val syncMutationId: String? = null
    ) : RemoteEvent {
        override val entityType: String = "user_settings"
        override val operation: String = "UPSERT"
    }
}

/**
 * Represents the known local version state for conflict resolution.
 * INV-06: Only server-assigned remote versions are authoritative.
 */
sealed class LocalVersionState {
    /** Server-assigned remote version exists in sync_metadata */
    data class ServerTracked(val version: Long) : LocalVersionState()
    
    /** Entity exists locally but was NEVER synced (no remote_version recorded).
     *  This happens for:
     *  - Freshly imported uTower data with active outbox (hasActiveMutation handles this)
     *  - Successfully pushed data where echo hasn't been processed yet  
     *  - Legacy data from before remote_version tracking existed
     *  
     *  The legacy fallback timestamp is preserved HERE for safe comparison,
     *  but ONLY from entities that match legacy criteria.
     */
    data class Untracked(val legacyFallback: Long?) : LocalVersionState()
    
    /** Entity does not exist locally at all */
    object New : LocalVersionState()
}

/**
 * Single authoritative coordinator for all remote event application.
 * Guarantees serialized execution, deterministic deduplication, and zero Outbox creation.
 */
class RemoteSyncCoordinator(
    private val appDatabase: AppDatabase,
    private val accountDao: LocalAccountDao,
    private val ledgerDao: LocalLedgerEntryDao,
    private val batchDao: ImportBatchDao,
    private val outboxDao: SyncOutboxDao,
    private val metadataDao: SyncMetadataDao,
    private val auditDao: AuditLogDao? = null
) {
    private val coordinatorMutex = Mutex()

    /**
     * INV-06: Resolves the authoritative local version for an entity.
     * 
     * SINGLE SOURCE OF TRUTH for "what version do we think we have locally?"
     * All 7 call sites MUST use this function instead of inline resolution.
     * 
     * @param entityType One of: "account", "ledger", "batch"
     * @param entityId The entity document ID
     * @return LocalVersionState describing what we know about local version
     */
    internal suspend fun resolveLocalVersion(
        entityType: String,
        entityId: String
    ): LocalVersionState {
        // 1. Authoritative: check sync_metadata for server-recorded version
        val storedVersion = metadataDao
            .get("remote_version:$entityType:$entityId")
            ?.toLongOrNull()
        
        if (storedVersion != null && storedVersion > 0L) {
            return LocalVersionState.ServerTracked(storedVersion)
        }
        
        // 2. No server version -> check if entity exists locally
        //    If it does, return Untracked (without synthetic cross-domain timestamp comparison)
        //    The CALLER (SyncConflictResolver) decides what to do with Untracked (falls through to UNKNOWN_MISSING).
        return when (entityType) {
            "account" -> {
                val existing = accountDao.getByIdOneShot(entityId)
                if (existing == null) LocalVersionState.New else LocalVersionState.Untracked(null)
            }
            "ledger" -> {
                val existing = ledgerDao.getByIdOneShot(entityId)
                if (existing == null) LocalVersionState.New else LocalVersionState.Untracked(null)
            }
            "batch" -> {
                val existing = batchDao.getById(entityId)
                if (existing == null) LocalVersionState.New else LocalVersionState.Untracked(null)
            }
            else -> LocalVersionState.New
        }
    }

    /**
     * Converts LocalVersionState to the Long? expected by SyncConflictResolver.
     */
    private fun LocalVersionState.toComparableTimestamp(): Long? = when (this) {
        is LocalVersionState.ServerTracked -> version
        is LocalVersionState.Untracked -> null
        is LocalVersionState.New -> null
    }

    // LRU cache for in-memory deduplication keys
    private val processedKeys = object : LinkedHashMap<String, Long>(1000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 2000
        }
    }

    /**
     * Serialized processing of remote events.
     * Returns an explicit [EventSyncResult] dictating the outcome and cursor advancement behavior.
     */
    suspend fun processEvent(event: RemoteEvent, passedCapturedGen: Long? = null): EventSyncResult {
        return coordinatorMutex.withLock {
            val key = event.deduplicationKey

            // 1. In-memory LRU deduplication check
            if (processedKeys.containsKey(key)) {
                Log.d("RemoteSyncCoordinator", "TESTDEBUG: Skipping duplicate event key=$key from source=${event.source}")
                return EventSyncResult.SKIPPED_DUPLICATE
            }

            // Capture local generation at remote operation start (P3-G4-REQ-02, INV-05, INV-11)
            val capturedGen = passedCapturedGen ?: metadataDao.getGeneration()

            var result = EventSyncResult.FAILED_RETRYABLE
            appDatabase.withTransaction {
                // Re-read current generation inside the write transaction
                val currentGen = appDatabase.syncMetadataDao().getGeneration()
                if (currentGen != capturedGen) {
                    Log.w(
                        "RemoteSyncCoordinator",
                        "Lineage generation mismatch during processEvent for ${event.entityType}:${event.entityId}: capturedGen=$capturedGen != currentGen=$currentGen. Rejecting stale remote result."
                    )
                    result = EventSyncResult.FAILED_RETRYABLE
                    return@withTransaction
                }

                result = when (event) {
                    is RemoteEvent.AccountUpsert -> applyAccountUpsert(event, capturedGen)
                    is RemoteEvent.AccountDelete -> applyAccountDelete(event, capturedGen)
                    is RemoteEvent.LedgerUpsert -> applyLedgerUpsert(event, capturedGen)
                    is RemoteEvent.LedgerDelete -> applyLedgerDelete(event, capturedGen)
                    is RemoteEvent.BatchUpsert -> applyBatchUpsert(event, capturedGen)
                    is RemoteEvent.UserSettingsUpdate -> applyUserSettingsUpdate(event, capturedGen)
                }
            }

            if (result.canAdvanceCursor()) {
                processedKeys[key] = System.currentTimeMillis()
            }
            result
        }
    }

    /**
     * Batch process multiple remote events sequentially.
     */
    suspend fun processEvents(events: List<RemoteEvent>, passedCapturedGen: Long? = null): Int {
        var count = 0
        for (event in events) {
            if (processEvent(event, passedCapturedGen).canAdvanceCursor()) {
                count++
            }
        }
        return count
    }

    private suspend fun applyAccountUpsert(event: RemoteEvent.AccountUpsert, capturedGen: Long): EventSyncResult {
        val currentGen = metadataDao.getGeneration()
        if (currentGen != capturedGen) {
            Log.w("RemoteSyncCoordinator", "TESTDEBUG: Lineage generation mismatch in applyAccountUpsert: captured=$capturedGen != current=$currentGen")
            return EventSyncResult.FAILED_RETRYABLE
        }

        val account = event.account
        val tombstoneStr = metadataDao.get("tombstone:account:${event.entityId}")
        val tombstoneTs = tombstoneStr?.toLongOrNull()
        if (tombstoneTs != null && tombstoneTs > 0L) {
            if (event.remoteVersion <= tombstoneTs) {
                Log.d("RemoteSyncCoordinator", "TESTDEBUG: Ignoring stale AccountUpsert for deleted account ${event.entityId}")
                return EventSyncResult.SKIPPED_DUPLICATE
            }
        }

        val hasActiveMutation = hasConflictingLocalMutation(event.entityId, "local_accounts", event.syncMutationId)
        val existing = accountDao.getByIdOneShot(event.entityId)
        val localVersion = resolveLocalVersion("account", event.entityId)
        val localTimestamp = localVersion.toComparableTimestamp()

        // G1: Deterministic resolution of sourceExternalId duplicate
        val incomingExternalId = account.sourceExternalId
        if (incomingExternalId != null) {
            val duplicate = accountDao.findBySourceExternalId(incomingExternalId)
            if (duplicate != null && duplicate.id != event.entityId) {
                Log.w("RemoteSyncCoordinator", "Account duplicate: remote ${event.entityId} vs local ${duplicate.id} for sourceExternalId $incomingExternalId")
                val duplicateActiveMutation = hasConflictingLocalMutation(duplicate.id, "local_accounts", event.syncMutationId)
                val duplicateLocalVersion = resolveLocalVersion("account", duplicate.id)
                val duplicateLocalTimestamp = duplicateLocalVersion.toComparableTimestamp()
                val decision = SyncConflictResolver.resolveIncomingChange(
                    localTimestamp = duplicateLocalTimestamp,
                    remoteUpdatedAt = event.remoteVersion,
                    remoteDeletedAt = null,
                    hasActiveLocalMutation = duplicateActiveMutation
                )
                if (decision == ConflictDecision.APPLY_UPSERT) {
                    val payloadHash = event.account.hashCode().toString()
                    val quarantineAudit = AuditLog(
                        action = "QUARANTINE_IDENTITY_CONFLICT",
                        entityType = "local_accounts",
                        entityId = event.entityId,
                        summary = "Quarantined deterministic sourceExternalId conflict: remote=${event.entityId}, local=${duplicate.id}, sourceExternalId=$incomingExternalId, reason=APPLY_UPSERT_COLLISION, version=${event.remoteVersion}, payloadHash=$payloadHash",
                        createdAt = System.currentTimeMillis(),
                        severity = "WARNING",
                        origin = AuditOrigin.SYSTEM_ACTION.name
                    )
                    auditDao?.insert(quarantineAudit)
                    return EventSyncResult.QUARANTINED_CONFLICT
                } else if (decision == ConflictDecision.REJECT_MALFORMED) {
                    val quarantineAudit = AuditLog(
                        action = "MALFORMED_REMOTE_EVENT",
                        entityType = "local_accounts",
                        entityId = event.entityId,
                        summary = "Quarantined malformed remote account with invalid/zero version: ${event.remoteVersion}",
                        createdAt = System.currentTimeMillis(),
                        severity = "WARNING",
                        origin = AuditOrigin.SYSTEM_ACTION.name
                    )
                    auditDao?.insert(quarantineAudit)
                    return EventSyncResult.QUARANTINED_MALFORMED
                } else {
                    return EventSyncResult.SKIPPED_DUPLICATE
                }
            }
        }

        val decision = SyncConflictResolver.resolveIncomingChange(
            localTimestamp = localTimestamp,
            remoteUpdatedAt = event.remoteVersion,
            remoteDeletedAt = null,
            hasActiveLocalMutation = hasActiveMutation
        )

        return when (decision) {
            ConflictDecision.APPLY_UPSERT -> {
                accountDao.upsert(account)
                metadataDao.putMonotonicRemoteVersion("remote_version:account:${event.entityId}", event.remoteVersion)
                EventSyncResult.APPLIED
            }
            ConflictDecision.REJECT_MALFORMED -> {
                val quarantineAudit = AuditLog(
                    action = "MALFORMED_REMOTE_EVENT",
                    entityType = "local_accounts",
                    entityId = event.entityId,
                    summary = "Quarantined malformed remote account with invalid/zero version: ${event.remoteVersion}",
                    createdAt = System.currentTimeMillis(),
                    severity = "WARNING",
                    origin = AuditOrigin.SYSTEM_ACTION.name
                )
                auditDao?.insert(quarantineAudit)
                EventSyncResult.QUARANTINED_MALFORMED
            }
            ConflictDecision.IGNORE_LOCAL_PENDING,
            ConflictDecision.IGNORE_STALE_REMOTE -> EventSyncResult.SKIPPED_DUPLICATE
            else -> EventSyncResult.FAILED_RETRYABLE
        }
    }

    private suspend fun applyAccountDelete(event: RemoteEvent.AccountDelete, capturedGen: Long): EventSyncResult {
        val currentGen = metadataDao.getGeneration()
        if (currentGen != capturedGen) {
            Log.w("RemoteSyncCoordinator", "Lineage generation mismatch in applyAccountDelete: captured=$capturedGen != current=$currentGen")
            return EventSyncResult.FAILED_RETRYABLE
        }

        val hasActiveMutation = hasConflictingLocalMutation(event.entityId, "local_accounts", event.syncMutationId)
        val existing = accountDao.getByIdOneShot(event.entityId)
        val localVersion = resolveLocalVersion("account", event.entityId)
        val localTimestamp = localVersion.toComparableTimestamp()

        val decision = SyncConflictResolver.resolveIncomingChange(
            localTimestamp = localTimestamp,
            remoteUpdatedAt = event.remoteVersion,
            remoteDeletedAt = event.remoteVersion,
            hasActiveLocalMutation = hasActiveMutation
        )

        return when (decision) {
            ConflictDecision.APPLY_DELETE -> {
                val childLedgers = ledgerDao.getByAccountIdOneShot(event.entityId, limit = Int.MAX_VALUE)
                val hasPendingChild = childLedgers.any { child -> hasConflictingLocalMutation(child.id, "local_ledger_entries", null) }
                if (!hasPendingChild) {
                    childLedgers.forEach { child ->
                        metadataDao.put("tombstone:ledger:${child.id}", event.remoteVersion.toString())
                        metadataDao.putMonotonicRemoteVersion("remote_version:ledger:${child.id}", event.remoteVersion)
                    }
                    if (existing != null) {
                        accountDao.update(existing.copy(isHistoryOnlySubscriber = true, updatedAt = event.remoteVersion))
                    }
                    metadataDao.put("tombstone:account:${event.entityId}", event.remoteVersion.toString())
                    metadataDao.putMonotonicRemoteVersion("remote_version:account:${event.entityId}", event.remoteVersion)
                    EventSyncResult.APPLIED
                } else EventSyncResult.FAILED_RETRYABLE
            }
            ConflictDecision.REJECT_MALFORMED -> {
                val quarantineAudit = AuditLog(
                    action = "MALFORMED_REMOTE_EVENT",
                    entityType = "local_accounts",
                    entityId = event.entityId,
                    summary = "Quarantined malformed remote account delete with invalid/zero version: ${event.remoteVersion}",
                    createdAt = System.currentTimeMillis(),
                    severity = "WARNING",
                    origin = AuditOrigin.SYSTEM_ACTION.name
                )
                auditDao?.insert(quarantineAudit)
                EventSyncResult.QUARANTINED_MALFORMED
            }
            ConflictDecision.IGNORE_LOCAL_PENDING,
            ConflictDecision.IGNORE_STALE_REMOTE -> EventSyncResult.SKIPPED_DUPLICATE
            else -> EventSyncResult.FAILED_RETRYABLE
        }
    }

    private suspend fun applyLedgerUpsert(event: RemoteEvent.LedgerUpsert, capturedGen: Long): EventSyncResult {
        val currentGen = metadataDao.getGeneration()
        if (currentGen != capturedGen) {
            Log.w("RemoteSyncCoordinator", "Lineage generation mismatch in applyLedgerUpsert: captured=$capturedGen != current=$currentGen")
            return EventSyncResult.FAILED_RETRYABLE
        }

        val entry = event.entry

        // 1. Check ledger tombstone
        val ledgerTombstoneStr = metadataDao.get("tombstone:ledger:${event.entityId}")
        val ledgerTombstoneTs = ledgerTombstoneStr?.toLongOrNull()
        if (ledgerTombstoneTs != null && ledgerTombstoneTs > 0L) {
            if (event.remoteVersion <= ledgerTombstoneTs) {
                Log.d("RemoteSyncCoordinator", "Ignoring stale LedgerUpsert for deleted ledger ${event.entityId}")
                return EventSyncResult.SKIPPED_DUPLICATE
            } else {
                metadataDao.put("tombstone:ledger:${event.entityId}", "0")
            }
        }

        // 2. Check parent account tombstone (zombie protection)
        val accountTombstoneStr = metadataDao.get("tombstone:account:${entry.accountId}")
        val accountTombstoneTs = accountTombstoneStr?.toLongOrNull()
        if (accountTombstoneTs != null && accountTombstoneTs > 0L) {
            if (event.remoteVersion <= accountTombstoneTs) {
                Log.d("RemoteSyncCoordinator", "Ignoring LedgerUpsert for account ${entry.accountId} which was deleted")
                return EventSyncResult.SKIPPED_DUPLICATE
            }
        }

        val hasActiveMutation = hasConflictingLocalMutation(event.entityId, "local_ledger_entries", event.syncMutationId)
        val existing = ledgerDao.getByIdOneShot(event.entityId)
        val localVersion = resolveLocalVersion("ledger", event.entityId)
        val localTimestamp = localVersion.toComparableTimestamp()

        // 3. Same-ID divergent-payload immutability protection (INV-01 / INV-11 / P1-11)
        if (existing != null) {
            val isDivergent = existing.accountId != entry.accountId ||
                    existing.typeRaw != entry.typeRaw ||
                    kotlin.math.abs(existing.amountIqd - entry.amountIqd) >= 0.0001
            if (isDivergent) {
                Log.w("RemoteSyncCoordinator", "Quarantining same-ID divergent ledger payload: id=${event.entityId}, local={account=${existing.accountId}, type=${existing.typeRaw}, amount=${existing.amountIqd}}, remote={account=${entry.accountId}, type=${entry.typeRaw}, amount=${entry.amountIqd}}, version=${event.remoteVersion}")
                val payloadHash = entry.hashCode().toString()
                val quarantineAudit = AuditLog(
                    action = "QUARANTINE_IDENTITY_CONFLICT",
                    entityType = "local_ledger_entries",
                    entityId = event.entityId,
                    summary = "Quarantined same-ID divergent ledger payload conflict: remote=${event.entityId}, localAccount=${existing.accountId}, remoteAccount=${entry.accountId}, localType=${existing.typeRaw}, remoteType=${entry.typeRaw}, localAmount=${existing.amountIqd}, remoteAmount=${entry.amountIqd}, version=${event.remoteVersion}, payloadHash=$payloadHash",
                    createdAt = System.currentTimeMillis(),
                    severity = "WARNING",
                    origin = AuditOrigin.SYSTEM_ACTION.name
                )
                auditDao?.insert(quarantineAudit)
                return EventSyncResult.QUARANTINED_CONFLICT
            }
        }

        // G2: Duplicate Ledger
        val incomingExternalId = entry.sourceExternalId
        if (incomingExternalId != null) {
            val duplicate = ledgerDao.findByAccountAndExternalId(entry.accountId, incomingExternalId)
            if (duplicate != null && duplicate.id != event.entityId) {
                Log.w("RemoteSyncCoordinator", "Ledger duplicate: remote ${event.entityId} vs local ${duplicate.id} for account ${entry.accountId} externalId $incomingExternalId")
                val duplicateActiveMutation = hasConflictingLocalMutation(duplicate.id, "local_ledger_entries", event.syncMutationId)
                val duplicateLocalVersion = resolveLocalVersion("ledger", duplicate.id)
                val duplicateLocalTimestamp = duplicateLocalVersion.toComparableTimestamp()
                val decision = SyncConflictResolver.resolveIncomingChange(
                    localTimestamp = duplicateLocalTimestamp,
                    remoteUpdatedAt = event.remoteVersion,
                    remoteDeletedAt = null,
                    hasActiveLocalMutation = duplicateActiveMutation
                )
                if (decision == ConflictDecision.APPLY_UPSERT) {
                    val payloadHash = entry.hashCode().toString()
                    val quarantineAudit = AuditLog(
                        action = "QUARANTINE_IDENTITY_CONFLICT",
                        entityType = "local_ledger_entries",
                        entityId = event.entityId,
                        summary = "Quarantined deterministic ledger externalId conflict: remote=${event.entityId}, local=${duplicate.id}, accountId=${entry.accountId}, externalId=$incomingExternalId, version=${event.remoteVersion}, payloadHash=$payloadHash",
                        createdAt = System.currentTimeMillis(),
                        severity = "WARNING",
                        origin = AuditOrigin.SYSTEM_ACTION.name
                    )
                    auditDao?.insert(quarantineAudit)
                    return EventSyncResult.QUARANTINED_CONFLICT
                } else if (decision == ConflictDecision.REJECT_MALFORMED) {
                    val quarantineAudit = AuditLog(
                        action = "MALFORMED_REMOTE_EVENT",
                        entityType = "local_ledger_entries",
                        entityId = event.entityId,
                        summary = "Quarantined malformed remote ledger with invalid/zero version: ${event.remoteVersion}",
                        createdAt = System.currentTimeMillis(),
                        severity = "WARNING",
                        origin = AuditOrigin.SYSTEM_ACTION.name
                    )
                    auditDao?.insert(quarantineAudit)
                    return EventSyncResult.QUARANTINED_MALFORMED
                } else {
                    return EventSyncResult.SKIPPED_DUPLICATE
                }
            }
        }

        val decision = SyncConflictResolver.resolveIncomingChange(
            localTimestamp = localTimestamp,
            remoteUpdatedAt = event.remoteVersion,
            remoteDeletedAt = null,
            hasActiveLocalMutation = hasActiveMutation
        )

        return when (decision) {
            ConflictDecision.APPLY_UPSERT -> {
                if (event.preFetchedParentAccount != null && accountDao.getByIdOneShot(entry.accountId) == null) {
                    val preFetchedAccountTombstone = metadataDao.get("tombstone:account:${entry.accountId}")?.toLongOrNull()
                    if (preFetchedAccountTombstone == null || preFetchedAccountTombstone <= 0L) {
                        accountDao.upsert(event.preFetchedParentAccount)
                        metadataDao.putMonotonicRemoteVersion("remote_version:account:${event.preFetchedParentAccount.id}", event.remoteVersion)
                    }
                }
                if (accountDao.getByIdOneShot(entry.accountId) != null) {
                    ledgerDao.upsert(entry)
                    metadataDao.putMonotonicRemoteVersion("remote_version:ledger:${event.entityId}", event.remoteVersion)
                    recalculateAccountBalance(entry.accountId)
                    EventSyncResult.APPLIED
                } else EventSyncResult.FAILED_RETRYABLE
            }
            ConflictDecision.REJECT_MALFORMED -> {
                val quarantineAudit = AuditLog(
                    action = "MALFORMED_REMOTE_EVENT",
                    entityType = "local_ledger_entries",
                    entityId = event.entityId,
                    summary = "Quarantined malformed remote ledger with invalid/zero version: ${event.remoteVersion}",
                    createdAt = System.currentTimeMillis(),
                    severity = "WARNING",
                    origin = AuditOrigin.SYSTEM_ACTION.name
                )
                auditDao?.insert(quarantineAudit)
                EventSyncResult.QUARANTINED_MALFORMED
            }
            ConflictDecision.IGNORE_LOCAL_PENDING,
            ConflictDecision.IGNORE_STALE_REMOTE -> EventSyncResult.SKIPPED_DUPLICATE
            else -> EventSyncResult.FAILED_RETRYABLE
        }
    }

    private suspend fun applyLedgerDelete(event: RemoteEvent.LedgerDelete, capturedGen: Long): EventSyncResult {
        val currentGen = metadataDao.getGeneration()
        if (currentGen != capturedGen) {
            Log.w("RemoteSyncCoordinator", "Lineage generation mismatch in applyLedgerDelete: captured=$capturedGen != current=$currentGen")
            return EventSyncResult.FAILED_RETRYABLE
        }

        val hasActiveMutation = hasConflictingLocalMutation(event.entityId, "local_ledger_entries", event.syncMutationId)
        val existing = ledgerDao.getByIdOneShot(event.entityId)
        val localVersion = resolveLocalVersion("ledger", event.entityId)
        val localTimestamp = localVersion.toComparableTimestamp()

        val decision = SyncConflictResolver.resolveIncomingChange(
            localTimestamp = localTimestamp,
            remoteUpdatedAt = event.remoteVersion,
            remoteDeletedAt = event.remoteVersion,
            hasActiveLocalMutation = hasActiveMutation
        )

        return when (decision) {
            ConflictDecision.APPLY_DELETE -> {
                metadataDao.put("tombstone:ledger:${event.entityId}", event.remoteVersion.toString())
                metadataDao.putMonotonicRemoteVersion("remote_version:ledger:${event.entityId}", event.remoteVersion)
                EventSyncResult.APPLIED
            }
            ConflictDecision.REJECT_MALFORMED -> {
                val quarantineAudit = AuditLog(
                    action = "MALFORMED_REMOTE_EVENT",
                    entityType = "local_ledger_entries",
                    entityId = event.entityId,
                    summary = "Quarantined malformed remote ledger delete with invalid/zero version: ${event.remoteVersion}",
                    createdAt = System.currentTimeMillis(),
                    severity = "WARNING",
                    origin = AuditOrigin.SYSTEM_ACTION.name
                )
                auditDao?.insert(quarantineAudit)
                EventSyncResult.QUARANTINED_MALFORMED
            }
            ConflictDecision.IGNORE_LOCAL_PENDING,
            ConflictDecision.IGNORE_STALE_REMOTE -> EventSyncResult.SKIPPED_DUPLICATE
            else -> EventSyncResult.FAILED_RETRYABLE
        }
    }

    private suspend fun applyBatchUpsert(event: RemoteEvent.BatchUpsert, capturedGen: Long): EventSyncResult {
        val currentGen = metadataDao.getGeneration()
        if (currentGen != capturedGen) {
            Log.w("RemoteSyncCoordinator", "Lineage generation mismatch in applyBatchUpsert: captured=$capturedGen != current=$currentGen")
            return EventSyncResult.FAILED_RETRYABLE
        }

        val batch = event.batch
        val existing = batchDao.getById(event.entityId)
        val localVersion = resolveLocalVersion("batch", event.entityId)
        val localTimestamp = localVersion.toComparableTimestamp()

        val decision = SyncConflictResolver.resolveIncomingChange(
            localTimestamp = localTimestamp,
            remoteUpdatedAt = event.remoteVersion,
            remoteDeletedAt = null,
            hasActiveLocalMutation = false
        )

        return when (decision) {
            ConflictDecision.APPLY_UPSERT -> {
                batchDao.insert(batch)
                metadataDao.putMonotonicRemoteVersion("remote_version:batch:${event.entityId}", event.remoteVersion)
                EventSyncResult.APPLIED
            }
            ConflictDecision.REJECT_MALFORMED -> {
                val quarantineAudit = AuditLog(
                    action = "MALFORMED_REMOTE_EVENT",
                    entityType = "import_batches",
                    entityId = event.entityId,
                    summary = "Quarantined malformed remote batch with invalid/zero version: ${event.remoteVersion}",
                    createdAt = System.currentTimeMillis(),
                    severity = "WARNING",
                    origin = AuditOrigin.SYSTEM_ACTION.name
                )
                auditDao?.insert(quarantineAudit)
                EventSyncResult.QUARANTINED_MALFORMED
            }
            ConflictDecision.IGNORE_LOCAL_PENDING,
            ConflictDecision.IGNORE_STALE_REMOTE -> EventSyncResult.SKIPPED_DUPLICATE
            else -> EventSyncResult.FAILED_RETRYABLE
        }
    }

    private suspend fun applyUserSettingsUpdate(event: RemoteEvent.UserSettingsUpdate, capturedGen: Long): EventSyncResult {
        val currentGen = metadataDao.getGeneration()
        if (currentGen != capturedGen) {
            Log.w("RemoteSyncCoordinator", "Lineage generation mismatch in applyUserSettingsUpdate: captured=$capturedGen != current=$currentGen")
            return EventSyncResult.FAILED_RETRYABLE
        }

        val lastVersionStr = metadataDao.get("user_settings_version")
        val lastVersion = lastVersionStr?.toLongOrNull() ?: 0L
        if (event.remoteVersion < lastVersion) {
            Log.d("RemoteSyncCoordinator", "Ignoring stale UserSettingsUpdate version ${event.remoteVersion} < $lastVersion")
            return EventSyncResult.SKIPPED_DUPLICATE
        }
        metadataDao.put("user_settings_version", event.remoteVersion.toString())
        metadataDao.put("user_settings_json", event.settingsJson)
        return EventSyncResult.APPLIED
    }

    private suspend fun hasConflictingLocalMutation(
        entityId: String,
        entityType: String,
        incomingSyncMutationId: String?
    ): Boolean {
        val hasActive = OutboxManager.hasActiveMutation(outboxDao, entityId, entityType)
        if (!hasActive) {
            return false
        }
        if (incomingSyncMutationId != null) {
            val activeItems = outboxDao.getByEntity(entityId, entityType)
            for (item in activeItems) {
                if (item.status in listOf("pending", "syncing", "failed")) {
                    val localMutationId = getSyncMutationIdFromPayload(item.payloadJson)
                    if (localMutationId == incomingSyncMutationId) {
                        Log.d("RemoteSyncCoordinator", "Found matching syncMutationId $incomingSyncMutationId for $entityType:$entityId, treating as non-conflicting confirmation")
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun getSyncMutationIdFromPayload(payloadJson: String?): String? {
        if (payloadJson.isNullOrEmpty()) return null
        return try {
            val json = org.json.JSONObject(payloadJson)
            if (json.has("syncMutationId") && !json.isNull("syncMutationId")) json.getString("syncMutationId") else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun recalculateAccountBalance(accountId: String) {
        val account = accountDao.getByIdOneShot(accountId) ?: return
        val ledgers = ledgerDao.getByAccountIdOneShot(accountId, limit = Int.MAX_VALUE)
        
        val isSnapshot = account.stateSource != null
        val unrecognizedTxs = mutableListOf<Pair<LocalLedgerEntry, String>>()
        val (derivedBalances, updatedEntries) = com.example.core.ledger.BalanceCalculator.reconstructCurrentPosition(
            openingDebt = account.openingDebtIqd,
            openingAdvance = account.openingAdvanceIqd,
            openingLoan = account.openingLoanIqd,
            transactions = ledgers,
            isSnapshotBaseline = isSnapshot,
            onUnrecognizedType = { tx, unrecType ->
                unrecognizedTxs.add(tx to unrecType)
            }
        )

        for ((tx, unrecType) in unrecognizedTxs) {
            val quarantineAudit = AuditLog(
                action = "UNRECOGNIZED_TRANSACTION_TYPE",
                entityType = "local_ledger_entries",
                entityId = tx.id,
                summary = "Unrecognized transaction type '$unrecType' encountered during balance derivation for account ${tx.accountId}",
                createdAt = System.currentTimeMillis(),
                severity = "WARNING",
                origin = AuditOrigin.SYSTEM_ACTION.name
            )
            auditDao?.insert(quarantineAudit)
        }

        val updatedLedgers = mutableListOf<LocalLedgerEntry>()
        for (updatedTx in updatedEntries) {
            val originalTx = ledgers.find { it.id == updatedTx.id }
            if (originalTx == null || kotlin.math.abs(originalTx.debtAfterIqd - updatedTx.debtAfterIqd) > 0.01) {
                updatedLedgers.add(updatedTx)
            }
        }

        // Preserve business updatedAt timestamp when updating derived calculation! (INV-04)
        accountDao.upsert(
            account.copy(
                debtIqd = derivedBalances.debtIqd,
                advanceIqd = derivedBalances.advanceIqd,
                loanIqd = derivedBalances.loanIqd
                // Note: updatedAt is NOT modified here so remote apply does not trigger outbox
            )
        )

        if (updatedLedgers.isNotEmpty()) {
            ledgerDao.insertAll(updatedLedgers)
        }
    }

    suspend fun clearCache() {
        coordinatorMutex.withLock {
            processedKeys.clear()
        }
    }
}
