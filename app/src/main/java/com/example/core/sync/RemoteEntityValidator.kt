package com.example.core.sync

import com.example.core.model.ImportBatch
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry

sealed class RemoteEntityValidationResult<out T> {
    data class Valid<T>(val entity: T) : RemoteEntityValidationResult<T>()
    data class Malformed(val reason: String) : RemoteEntityValidationResult<Nothing>()
    data class Retryable(val reason: String) : RemoteEntityValidationResult<Nothing>()
}

object RemoteEntityValidator {

    fun validateAndMapAccount(
        id: String,
        d: Map<String, Any>,
        remoteUpdatedAt: Long,
        existingLocalAccount: LocalAccount? = null
    ): RemoteEntityValidationResult<LocalAccount> {
        if (id.isBlank()) return RemoteEntityValidationResult.Malformed("Account ID is blank")

        val payloadKind = d["payloadKind"] as? String
        val isFullSnapshot = when (payloadKind) {
            "FULL_SNAPSHOT" -> true
            "PATCH" -> false
            else -> (d["isFullSnapshot"] as? Boolean) ?: (d["stateSource"] != null)
        }

        // Strict Contract for Full Snapshot vs Partial Update
        val debtNumber = d["debtIqd"] as? Number
        if (isFullSnapshot && debtNumber == null) {
            return RemoteEntityValidationResult.Malformed("Full snapshot account $id missing required financial field 'debtIqd'")
        }
        if (!isFullSnapshot && debtNumber == null && existingLocalAccount == null) {
            return RemoteEntityValidationResult.Malformed("Account $id missing required financial field 'debtIqd'")
        }
        val effectiveDebt = debtNumber?.toDouble() ?: existingLocalAccount?.debtIqd ?: 0.0

        val openingDebt = (d["openingDebtIqd"] as? Number)?.toDouble() ?: existingLocalAccount?.openingDebtIqd ?: effectiveDebt
        val openingAdvance = (d["openingAdvanceIqd"] as? Number)?.toDouble() ?: existingLocalAccount?.openingAdvanceIqd ?: 0.0
        val openingLoan = (d["openingLoanIqd"] as? Number)?.toDouble() ?: existingLocalAccount?.openingLoanIqd ?: 0.0

        val advanceIqd = (d["advanceIqd"] as? Number)?.toDouble() ?: existingLocalAccount?.advanceIqd ?: openingAdvance
        val loanIqd = (d["loanIqd"] as? Number)?.toDouble() ?: existingLocalAccount?.loanIqd ?: openingLoan

        val rawDisplayName = d["displayName"] as? String
        val displayName = if (isFullSnapshot) {
            rawDisplayName ?: return RemoteEntityValidationResult.Malformed("Full snapshot account $id missing required field 'displayName'")
        } else {
            rawDisplayName ?: existingLocalAccount?.displayName ?: ""
        }

        val createdAt = RemoteSyncCursor.parseRemoteTimestamp(d["createdAt"])
            ?: existingLocalAccount?.createdAt
            ?: remoteUpdatedAt

        val stateSource = d["stateSource"] as? String ?: existingLocalAccount?.stateSource
        val stateConfidence = d["stateConfidence"] as? String ?: existingLocalAccount?.stateConfidence
        val snapshotCapturedAt = (d["snapshotCapturedAt"] as? Number)?.toLong() ?: existingLocalAccount?.snapshotCapturedAt

        // Fail-Closed Validation for Snapshot Semantics
        if (stateSource.isNullOrBlank()) {
            val hasExplicitSnapshotOpeningDebt = (openingDebt != 0.0)
            val hasSnapshotCapturedAt = (snapshotCapturedAt != null && snapshotCapturedAt > 0)
            if (isFullSnapshot && (hasExplicitSnapshotOpeningDebt || hasSnapshotCapturedAt)) {
                return RemoteEntityValidationResult.Malformed(
                    "Incomplete snapshot contract for account $id: opening baseline or snapshot capture timestamp present but 'stateSource' is missing"
                )
            }
        }

        val account = LocalAccount(
            id = id,
            sourceExternalId = d["sourceExternalId"] as? String ?: existingLocalAccount?.sourceExternalId,
            sourceBatchId = d["sourceBatchId"] as? String ?: existingLocalAccount?.sourceBatchId,
            displayName = displayName,
            earthlinkUsername = d["earthlinkUsername"] as? String ?: existingLocalAccount?.earthlinkUsername,
            phone1 = d["phone1"] as? String ?: existingLocalAccount?.phone1,
            phone2 = d["phone2"] as? String ?: existingLocalAccount?.phone2,
            packageName = d["packageName"] as? String ?: existingLocalAccount?.packageName,
            isLegacy = if (existingLocalAccount?.isLegacy == true) true else (d["isLegacy"] as? Boolean ?: false),
            isHistoryOnlySubscriber = if (d["isHistoryOnlySubscriber"] == true) true else existingLocalAccount?.isHistoryOnlySubscriber ?: false,
            currentPriceIqd = (d["currentPriceIqd"] as? Number)?.toDouble() ?: existingLocalAccount?.currentPriceIqd ?: 0.0,
            debtIqd = effectiveDebt,
            loanIqd = loanIqd,
            advanceIqd = advanceIqd,
            towerName = d["towerName"] as? String ?: existingLocalAccount?.towerName,
            zoneName = d["zoneName"] as? String ?: existingLocalAccount?.zoneName,
            address = d["address"] as? String ?: existingLocalAccount?.address,
            nanoIp = d["nanoIp"] as? String ?: existingLocalAccount?.nanoIp,
            latitude = (d["latitude"] as? Number)?.toDouble() ?: existingLocalAccount?.latitude,
            longitude = (d["longitude"] as? Number)?.toDouble() ?: existingLocalAccount?.longitude,
            note = d["note"] as? String ?: existingLocalAccount?.note,
            expiresAt = d["expiresAt"] as? String ?: existingLocalAccount?.expiresAt,
            lastPaymentAt = (d["lastPaymentAt"] as? Number)?.toLong() ?: existingLocalAccount?.lastPaymentAt,
            rawJson = d["rawJson"] as? String ?: existingLocalAccount?.rawJson,
            openingDebtIqd = openingDebt,
            openingAdvanceIqd = openingAdvance,
            openingLoanIqd = openingLoan,
            stateSource = stateSource,
            stateConfidence = stateConfidence,
            snapshotCapturedAt = snapshotCapturedAt,
            createdAt = createdAt,
            updatedAt = remoteUpdatedAt
        )
        return RemoteEntityValidationResult.Valid(account)
    }

    fun validateAndMapLedgerEntry(
        id: String,
        d: Map<String, Any>,
        remoteUpdatedAt: Long,
        existingLocalLedgerEntry: LocalLedgerEntry? = null
    ): RemoteEntityValidationResult<LocalLedgerEntry> {
        if (id.isBlank()) return RemoteEntityValidationResult.Malformed("Ledger ID is blank")
        val accountId = d["accountId"] as? String ?: existingLocalLedgerEntry?.accountId
        if (accountId.isNullOrBlank()) return RemoteEntityValidationResult.Malformed("Ledger entry $id has missing or blank accountId")

        val amountNumber = d["amountIqd"] as? Number
        val amountIqd = amountNumber?.toDouble() ?: existingLocalLedgerEntry?.amountIqd
            ?: return RemoteEntityValidationResult.Malformed("Ledger entry $id is missing mandatory amountIqd")

        val typeRaw = d["typeRaw"] as? String ?: existingLocalLedgerEntry?.typeRaw
            ?: return RemoteEntityValidationResult.Malformed("Ledger entry $id is missing mandatory typeRaw")

        val occurredAt = RemoteSyncCursor.parseRemoteTimestamp(d["occurredAt"])
            ?: RemoteSyncCursor.parseRemoteTimestamp(d["timestamp"])
            ?: existingLocalLedgerEntry?.occurredAt
            ?: return RemoteEntityValidationResult.Malformed("Ledger entry $id has invalid or missing occurredAt timestamp")

        val createdAt = RemoteSyncCursor.parseRemoteTimestamp(d["createdAt"]) ?: existingLocalLedgerEntry?.createdAt ?: remoteUpdatedAt
        val debtAfterNumber = d["debtAfterIqd"] as? Number
        val debtAfterIqd = debtAfterNumber?.toDouble() ?: existingLocalLedgerEntry?.debtAfterIqd ?: 0.0
        val isSnapshotHistory = d["isSnapshotHistory"] as? Boolean ?: existingLocalLedgerEntry?.isSnapshotHistory ?: false

        val entry = LocalLedgerEntry(
            id = id,
            accountId = accountId,
            sourceExternalId = d["sourceExternalId"] as? String ?: existingLocalLedgerEntry?.sourceExternalId,
            sourceBatchId = d["sourceBatchId"] as? String ?: existingLocalLedgerEntry?.sourceBatchId,
            typeRaw = typeRaw,
            amountIqd = amountIqd,
            debtAfterIqd = debtAfterIqd,
            note = d["note"] as? String ?: existingLocalLedgerEntry?.note,
            occurredAt = occurredAt,
            rawJson = d["rawJson"] as? String ?: existingLocalLedgerEntry?.rawJson,
            createdAt = createdAt,
            isSnapshotHistory = isSnapshotHistory,
            correctsEntryId = d["correctsEntryId"] as? String ?: existingLocalLedgerEntry?.correctsEntryId
        )
        return RemoteEntityValidationResult.Valid(entry)
    }

    fun validateAndMapImportBatch(
        id: String,
        d: Map<String, Any>,
        remoteUpdatedAt: Long
    ): RemoteEntityValidationResult<ImportBatch> {
        if (id.isBlank()) return RemoteEntityValidationResult.Malformed("Import batch ID is blank")
        val fileName = d["fileName"] as? String ?: "Imported"
        val fileHash = d["fileHash"] as? String ?: ""
        val accountsImported = (d["accountsImported"] as? Number)?.toInt() ?: 0
        val transactionsImported = (d["transactionsImported"] as? Number)?.toInt() ?: 0
        val totalDebtIqd = (d["totalDebtIqd"] as? Number)?.toDouble() ?: 0.0
        val warningsJson = d["warningsJson"] as? String
        val createdAt = RemoteSyncCursor.parseRemoteTimestamp(d["createdAt"]) ?: remoteUpdatedAt
        val status = d["status"] as? String
        if (status.isNullOrBlank()) {
            return RemoteEntityValidationResult.Malformed("Import batch $id is missing mandatory 'status' field")
        }

        val batch = ImportBatch(
            id = id,
            fileName = fileName,
            fileHash = fileHash,
            accountsImported = accountsImported,
            transactionsImported = transactionsImported,
            totalDebtIqd = totalDebtIqd,
            warningsJson = warningsJson,
            createdAt = createdAt,
            status = status
        )
        return RemoteEntityValidationResult.Valid(batch)
    }
}
