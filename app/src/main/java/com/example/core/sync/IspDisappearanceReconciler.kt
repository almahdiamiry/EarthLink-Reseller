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
     * 3. Monotonic transition: if earthlinkUsername not in authoritativeIspUserIds, set isHistoryOnlySubscriber = true.
     *    Never reset true -> false.
     * 4. Audit logging: logs ISP_SUBSCRIBER_DISAPPEARED for each transitioned account.
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
        isFetchComplete: Boolean
    ): List<String> {
        if (!isFetchComplete) {
            return emptyList()
        }

        val normalizedIspUserIds = authoritativeIspUserIds.map { it.trim().lowercase() }.toSet()
        val transitionedAccountIds = mutableListOf<String>()
        val moshi = Moshi.Builder().build()
        val accountAdapter = moshi.adapter(LocalAccount::class.java)

        database.withTransaction {
            val allAccounts = accountDao.getAllOneShot(limit = Int.MAX_VALUE)
            val now = System.currentTimeMillis()

            for (acc in allAccounts) {
                val username = acc.earthlinkUsername?.trim()
                // Rule 2: Exclude accounts with null or blank earthlinkUsername
                if (username.isNullOrBlank()) {
                    continue
                }

                // If already history-only, nothing to change (monotonic)
                if (acc.isHistoryOnlySubscriber) {
                    continue
                }

                // Check if username is present in ISP
                if (!normalizedIspUserIds.contains(username.lowercase())) {
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
                            summary = "Subscriber '${acc.displayName}' ($username) is no longer present in authoritative ISP subscriber list. Transitioned to history-only.",
                            createdAt = now,
                            severity = AuditSeverity.WARNING.name,
                            actor = "system",
                            origin = AuditOrigin.SYSTEM_ACTION.name,
                            metadataJsonMasked = "{\"earthlinkUsername\":\"$username\",\"accountId\":\"${acc.id}\"}"
                        )
                    )
                }
            }
        }

        return transitionedAccountIds
    }
}
