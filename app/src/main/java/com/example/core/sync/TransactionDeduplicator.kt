package com.example.core.sync

import com.example.core.model.LocalLedgerEntry

/**
 * Shared transaction deduplication primitive for import pipelines (UtowerImporter and Repositories.commitImport).
 * Provides strict deduplication by:
 * 1. External ID (`sourceExternalId` per account)
 * 2. Fallback match key (`accountId_occurredAt_amountIqd_typeRaw_note`)
 *
 * Transactions sharing timestamp + amount + type but having different notes are NOT treated as duplicates.
 */
object TransactionDeduplicator {

    fun buildExtIdKey(accountId: String, extId: String): String {
        return "${accountId}_${extId.trim()}"
    }

    fun buildMatchKey(
        accountId: String,
        occurredAt: Long,
        amountIqd: Double,
        typeRaw: String,
        note: String?
    ): String {
        val cleanNote = note?.trim() ?: ""
        return "${accountId}_${occurredAt}_${amountIqd}_${typeRaw.trim()}_${cleanNote}"
    }

    fun findDuplicate(
        existingByExtId: Map<String, LocalLedgerEntry>,
        existingByMatch: Map<String, LocalLedgerEntry>,
        tx: LocalLedgerEntry
    ): LocalLedgerEntry? {
        val extId = tx.sourceExternalId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (extId != null) {
            val dupByExtId = existingByExtId[buildExtIdKey(tx.accountId, extId)]
            if (dupByExtId != null) return dupByExtId
        }

        val matchKey = buildMatchKey(tx.accountId, tx.occurredAt, tx.amountIqd, tx.typeRaw, tx.note)
        return existingByMatch[matchKey]
    }

    fun isDuplicate(
        existingByExtId: Map<String, LocalLedgerEntry>,
        existingByMatch: Map<String, LocalLedgerEntry>,
        tx: LocalLedgerEntry
    ): Boolean {
        return findDuplicate(existingByExtId, existingByMatch, tx) != null
    }
}
