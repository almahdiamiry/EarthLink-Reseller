package com.example.core.sync

import androidx.room.withTransaction
import com.example.core.database.AppDatabase
import com.example.core.database.AuditLogDao
import com.example.core.database.LocalAccountDao
import com.example.core.model.AuditLog
import com.example.core.model.AuditOrigin
import com.example.core.model.AuditSeverity
import com.example.core.model.LocalAccount
import com.squareup.moshi.Moshi

object IspDisappearanceReconciler {

    /**
     * Evaluates all local accounts against a complete, verified set of ISP user IDs.
     *
     * Invariants (RC-04):
     * 1. Mapping: LocalAccount.earthlinkUsername <-> API field userID.
     * 2. Any LocalAccount with null/blank earthlinkUsername MUST be excluded entirely.
     * 3. Transition: if earthlinkUsername not in authoritativeIspUserIds, set isHistoryOnlySubscriber = true.
     *    If history-only subscriber reappears in authoritativeIspUserIds, restore isHistoryOnlySubscriber = false.
     * 4. Audit logging: logs ISP_SUBSCRIBER_DISAPPEARED or ISP_SUBSCRIBER_RESTORED.
     * 5. Outbox queuing: queues outbox entry for updated account.
     * 6. Incomplete fetch abort: If isFetchComplete is false, aborts without transitions (INV-12).
     *
     * @param database AppDatabase for transaction
     * @param accountDao LocalAccountDao
     * @param auditDao AuditLogDao
     * @param authoritativeIspUserIds Set of user IDs from a verified COMPLETE ISP fetch
     * @param isFetchComplete If false, aborts immediately without applying transitions (INV-12)
     * @return List of account IDs that transitioned to history-only
     */
    suspend fun reconcile(
        database: AppDatabase,
        accountDao: LocalAccountDao,
        auditDao: AuditLogDao?,
        authoritativeIspUserIds: Set<String>,
        isFetchComplete: Boolean,
        targetProvider: String = com.example.core.model.SasProviders.EARTHLINK,
        migrationUsernames: Set<String> = emptySet(),
        migrationTargetProvider: String? = null
    ): List<String> {
        if (!isFetchComplete) {
            return emptyList()
        }

        val normalizedIspUserIds = authoritativeIspUserIds.map { it.trim().lowercase() }.toSet()
        val normalizedMigration = migrationUsernames.map { it.trim().lowercase() }.toSet()
        val canMigrate = migrationTargetProvider != null && migrationTargetProvider != targetProvider
        val transitionedAccountIds = mutableListOf<String>()
        val moshi = Moshi.Builder().build()
        val accountAdapter = moshi.adapter(LocalAccount::class.java)

        database.withTransaction {
            val allAccounts = accountDao.getAllPersistedOneShot(limit = Int.MAX_VALUE)
            val now = System.currentTimeMillis()

            for (acc in allAccounts) {
                // Multi-provider scope guard: only evaluate accounts belonging to target provider
                if (acc.operationProvider != targetProvider) {
                    continue
                }

                val username = acc.earthlinkUsername?.trim()
                // Rule 2: Exclude accounts with null or blank earthlinkUsername
                if (username.isNullOrBlank()) {
                    continue
                }

                val isPresentInIsp = normalizedIspUserIds.contains(username.lowercase())

                // Re-appearance restoration: if history-only account is now present in authoritative roster, restore it
                if (acc.isHistoryOnlySubscriber) {
                    if (isPresentInIsp) {
                        val restored = acc.copy(
                            isHistoryOnlySubscriber = false,
                            updatedAt = now
                        )
                        accountDao.update(restored)
                        OutboxManager.upsertWithOutbox(
                            database.syncOutboxDao(),
                            "local_accounts",
                            restored.id,
                            accountAdapter.toJson(restored)
                        )
                        auditDao?.insert(
                            AuditLog(
                                action = "ISP_SUBSCRIBER_RESTORED",
                                entityType = "local_accounts",
                                entityId = acc.id,
                                summary = "Subscriber '${acc.displayName}' ($username) reappeared in authoritative $targetProvider ISP subscriber list. Restored from history-only.",
                                createdAt = now,
                                severity = AuditSeverity.INFO.name,
                                actor = "system",
                                origin = AuditOrigin.SYSTEM_ACTION.name,
                                metadataJsonMasked = "{\"earthlinkUsername\":\"$username\",\"accountId\":\"${acc.id}\",\"provider\":\"$targetProvider\"}"
                            )
                        )
                    }
                    continue
                }

                // Check if username is absent from ISP
                if (!isPresentInIsp) {
                    // Gone from this provider but present on the other one: the subscriber MOVED ISP.
                    // They are not historical, so their history must stay live under the new provider.
                    // This runs before the history-only write because monotonicity can never undo that.
                    if (canMigrate && normalizedMigration.contains(username.lowercase())) {
                        val moved = acc.copy(
                            operationProvider = migrationTargetProvider!!,
                            updatedAt = now
                        )
                        accountDao.update(moved)
                        OutboxManager.upsertWithOutbox(
                            database.syncOutboxDao(),
                            "local_accounts",
                            moved.id,
                            accountAdapter.toJson(moved)
                        )
                        auditDao?.insert(
                            AuditLog(
                                action = "ISP_SUBSCRIBER_MIGRATED",
                                entityType = "local_accounts",
                                entityId = acc.id,
                                summary = "Subscriber '${acc.displayName}' ($username) disappeared from $targetProvider but is present on $migrationTargetProvider. Re-pointed to the new provider; local history preserved.",
                                createdAt = now,
                                severity = AuditSeverity.WARNING.name,
                                actor = "system",
                                origin = AuditOrigin.SYSTEM_ACTION.name,
                                metadataJsonMasked = "{\"earthlinkUsername\":\"$username\",\"accountId\":\"${acc.id}\",\"from\":\"$targetProvider\",\"to\":\"$migrationTargetProvider\"}"
                            )
                        )
                        continue
                    }

                    val updated = acc.copy(
                        isHistoryOnlySubscriber = true,
                        updatedAt = now
                    )
                    accountDao.update(updated)
                    transitionedAccountIds.add(acc.id)

                    // Outbox sync
                    OutboxManager.upsertWithOutbox(
                        database.syncOutboxDao(),
                        "local_accounts",
                        updated.id,
                        accountAdapter.toJson(updated)
                    )

                    // Audit log
                    auditDao?.insert(
                        AuditLog(
                            action = "ISP_SUBSCRIBER_DISAPPEARED",
                            entityType = "local_accounts",
                            entityId = acc.id,
                            summary = "Subscriber '${acc.displayName}' ($username) is no longer present in authoritative $targetProvider ISP subscriber list. Transitioned to history-only.",
                            createdAt = now,
                            severity = AuditSeverity.WARNING.name,
                            actor = "system",
                            origin = AuditOrigin.SYSTEM_ACTION.name,
                            metadataJsonMasked = "{\"earthlinkUsername\":\"$username\",\"accountId\":\"${acc.id}\",\"provider\":\"$targetProvider\"}"
                        )
                    )
                }
            }
        }

        return transitionedAccountIds
    }

    /**
     * Purges or archives local accounts when an ISP provider is logged out.
     * - Accounts with 0 debt, no ledger transactions, and no active in-flight pending operations are physically purged.
     * - Accounts with outstanding debt, ledger transactions, or in-flight pending operations are archived to history-only.
     *
     * @return Pair(deletedCount, archivedCount)
     */
    suspend fun handleLogout(
        database: AppDatabase,
        accountDao: LocalAccountDao,
        auditDao: AuditLogDao?,
        loggedOutProvider: String
    ): Pair<Int, Int> {
        val moshi = Moshi.Builder().build()
        val accountAdapter = moshi.adapter(LocalAccount::class.java)
        var deletedCount = 0
        var archivedCount = 0

        database.withTransaction {
            val allAccounts = accountDao.getAllPersistedOneShot(limit = Int.MAX_VALUE)
            val targetAccounts = allAccounts.filter { it.operationProvider == loggedOutProvider }
            if (targetAccounts.isEmpty()) return@withTransaction

            val pendingAccountIds = accountDao.getAccountIdsWithActiveInFlightOperations(targetAccounts.map { it.id }).toSet()
            val now = System.currentTimeMillis()

            for (acc in targetAccounts) {
                val username = acc.earthlinkUsername?.trim()
                val hasPending = pendingAccountIds.contains(acc.id)
                val hasDebt = acc.debtIqd > 0.0 || acc.advanceIqd > 0.0
                val hasLedger = database.localLedgerEntryDao().getByAccountIdOneShot(acc.id, limit = 1).isNotEmpty()

                if (!hasDebt && !hasLedger && !hasPending) {
                    accountDao.deleteById(acc.id)
                    OutboxManager.deleteWithTombstone(database.syncOutboxDao(), "local_accounts", acc.id, "{}")
                    deletedCount++
                } else if (!acc.isHistoryOnlySubscriber) {
                    val archived = acc.copy(
                        isHistoryOnlySubscriber = true,
                        updatedAt = now
                    )
                    accountDao.update(archived)
                    OutboxManager.upsertWithOutbox(
                        database.syncOutboxDao(),
                        "local_accounts",
                        archived.id,
                        accountAdapter.toJson(archived)
                    )
                    auditDao?.insert(
                        AuditLog(
                            action = "PROVIDER_LOGOUT_ACCOUNT_ARCHIVED",
                            entityType = "local_accounts",
                            entityId = acc.id,
                            summary = "Subscriber '${acc.displayName}' ($username) archived as history-only on $loggedOutProvider logout to preserve debt/ledger/pending ops.",
                            createdAt = now,
                            severity = AuditSeverity.WARNING.name,
                            actor = "system",
                            origin = AuditOrigin.SYSTEM_ACTION.name,
                            metadataJsonMasked = "{\"username\":\"$username\",\"debtIqd\":${acc.debtIqd}}"
                        )
                    )
                    archivedCount++
                }
            }
        }

        return Pair(deletedCount, archivedCount)
    }
}
