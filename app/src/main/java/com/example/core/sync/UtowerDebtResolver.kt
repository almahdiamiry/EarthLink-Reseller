package com.example.core.sync

import com.example.core.ledger.BalanceCalculator
import com.example.core.ledger.TransactionTypeNormalizer
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import org.json.JSONObject

object UtowerDebtResolver {

    /**
     * Resolves authoritative debt for a uTower account following strict priority:
     * 1. If no post-reset transactions exist, retain explicit uTower subscriber debt (no history does not equal 0).
     * 2. If post-reset transactions exist, check for explicit snapshot totalDebitAfter (including explicit 0.0).
     * 3. Reconstruct balance incrementally from validated post-reset transactions.
     */
    fun resolveDebtForAccount(
        account: LocalAccount,
        postResetTxs: List<LocalLedgerEntry>,
        explicitSourceDebt: Double? = null
    ): Double {
        // Priority 1: No post-reset transactions -> Retain explicit subscriber debt baseline
        if (postResetTxs.isEmpty()) {
            if (explicitSourceDebt != null && explicitSourceDebt >= 0.0) {
                return explicitSourceDebt
            }
            return account.debtIqd
        }

        // Priority 2: Latest post-reset transaction with explicit debtAfter snapshot (including 0.0)
        val latestTxWithDebtAfter = postResetTxs.reversed().firstOrNull { tx ->
            if (!tx.rawJson.isNullOrEmpty()) {
                try {
                    val json = JSONObject(tx.rawJson)
                    json.has("totalDebitAfter") || json.has("debt_after") || json.has("debt_after_iqd") || json.has("debtAfter") || json.has("debt_after_unit")
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
        }

        if (latestTxWithDebtAfter != null) {
            return latestTxWithDebtAfter.debtAfterIqd
        }

        // Priority 3: Reconstruct incrementally from valid post-reset movements
        var resolvedDebt = 0.0
        var resolvedAdvance = 0.0
        var resolvedLoan = 0.0

        for (tx in postResetTxs) {
            val canonicalType = TransactionTypeNormalizer.normalizeTransactionType(tx.typeRaw)
            val balances = BalanceCalculator.applyTransaction(resolvedDebt, resolvedAdvance, resolvedLoan, canonicalType, tx.amountIqd)
            resolvedDebt = balances.debtIqd
            resolvedAdvance = balances.advanceIqd
            resolvedLoan = balances.loanIqd
        }

        if (postResetTxs.last().debtAfterIqd > 0.0) {
            return postResetTxs.last().debtAfterIqd
        }

        return resolvedDebt
    }
}
