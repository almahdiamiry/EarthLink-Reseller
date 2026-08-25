package com.example.core.ledger

data class AccountBalances(
    val debtIqd: Double,
    val advanceIqd: Double,
    val loanIqd: Double
)

object BalanceCalculator {
    fun applyTransaction(currentDebt: Double, currentAdvance: Double, currentLoan: Double, txType: String, amount: Double): AccountBalances {
        // Optimization: Skip String allocation if txType is already lowercase
        val lowerType = if (txType.all { !it.isUpperCase() }) txType else txType.lowercase()
        return when (lowerType) {
            "took", "debt", "debt_added", "renewal", "renew", "sub_renew", "sub_renewal", "debt_renew" -> {
                val advanceUsed = minOf(currentAdvance, amount)
                val debtAdded = amount - advanceUsed
                val newAdvance = currentAdvance - advanceUsed
                val newDebt = currentDebt + debtAdded
                AccountBalances(debtIqd = newDebt, advanceIqd = newAdvance, loanIqd = currentLoan)
            }
            "gave", "payment", "deposit", "pay" -> {
                val debtPayment = minOf(currentDebt, amount)
                val advanceAdded = amount - debtPayment
                val newDebt = currentDebt - debtPayment
                val newAdvance = currentAdvance + advanceAdded
                AccountBalances(debtIqd = newDebt, advanceIqd = newAdvance, loanIqd = currentLoan)
            }
            else -> AccountBalances(currentDebt, currentAdvance, currentLoan)
        }
    }

    fun revertTransaction(currentDebt: Double, currentAdvance: Double, currentLoan: Double, txType: String, amount: Double): AccountBalances {
        // Optimization: Skip String allocation if txType is already lowercase
        val lowerType = if (txType.all { !it.isUpperCase() }) txType else txType.lowercase()
        return when (lowerType) {
            "gave", "payment", "deposit", "pay" -> {
                val reversedAdvance = maxOf(0.0, currentAdvance - amount)
                val remainingPaymentToRevert = maxOf(0.0, amount - currentAdvance)
                val newDebt = currentDebt + remainingPaymentToRevert
                AccountBalances(debtIqd = newDebt, advanceIqd = reversedAdvance, loanIqd = currentLoan)
            }
            "took", "debt", "debt_added", "renewal", "renew", "sub_renew", "sub_renewal", "debt_renew" -> {
                val reversedDebt = maxOf(0.0, currentDebt - amount)
                val remainingDebtToRevert = maxOf(0.0, amount - currentDebt)
                val newAdvance = currentAdvance + remainingDebtToRevert
                AccountBalances(debtIqd = reversedDebt, advanceIqd = newAdvance, loanIqd = currentLoan)
            }
            else -> AccountBalances(currentDebt, currentAdvance, currentLoan)
        }
    }

    /**
     * Pure, deterministic derivation of current financial position (P2-G3-REQ-04 / INV-01 / INV-06 / INV-11).
     *
     * Formula:
     *   Current Position = Accepted Baseline + Eligible Ledger History
     *
     * Invariants:
     * 1. Stored balances are cached values and never an independent financial authority.
     * 2. Snapshot semantics: isSnapshotHistory marks imported historical/snapshot context. Whether such rows participate
     *    in position reconstruction depends on current baseline/state-source rules in BalanceCalculator (e.g. when stateSource != null,
     *    openingDebtIqd already incorporates the imported snapshot baseline).
     * 3. Deterministic chronological sorting: occurredAt ASC, then sourceExternalId ASC, then id ASC.
     * 4. Returns both the final AccountBalances and updated ledger entries with exact rolling debtAfterIqd.
     */
    fun reconstructCurrentPosition(
        openingDebt: Double,
        openingAdvance: Double,
        openingLoan: Double,
        transactions: List<com.example.core.model.LocalLedgerEntry>,
        isSnapshotBaseline: Boolean = false,
        onUnrecognizedType: ((com.example.core.model.LocalLedgerEntry, String) -> Unit)? = null
    ): Pair<AccountBalances, List<com.example.core.model.LocalLedgerEntry>> {
        val eligibleTxs = if (isSnapshotBaseline) {
            transactions.filter { !it.isSnapshotHistory }
        } else {
            transactions
        }

        val sortedTxs = eligibleTxs.sortedWith(
            compareBy<com.example.core.model.LocalLedgerEntry> { it.occurredAt }
                .thenBy { it.sourceExternalId ?: "" }
                .thenBy { it.id }
        )

        var runningDebt = openingDebt
        var runningAdvance = openingAdvance
        var runningLoan = openingLoan

        val updatedEntries = mutableListOf<com.example.core.model.LocalLedgerEntry>()

        for (tx in sortedTxs) {
            // Optimization: Normalize raw transaction type once per entry and reuse canonical type
            val canonicalType = TransactionTypeNormalizer.normalizeTransactionType(tx.typeRaw)
            if (!TransactionTypeNormalizer.isRecognizedCanonicalType(canonicalType)) {
                onUnrecognizedType?.invoke(tx, tx.typeRaw ?: "NULL")
            }
            val updatedBalances = applyTransaction(
                currentDebt = runningDebt,
                currentAdvance = runningAdvance,
                currentLoan = runningLoan,
                txType = canonicalType,
                amount = tx.amountIqd
            )
            runningDebt = updatedBalances.debtIqd
            runningAdvance = updatedBalances.advanceIqd
            runningLoan = updatedBalances.loanIqd
            updatedEntries.add(tx.copy(debtAfterIqd = runningDebt))
        }

        return Pair(
            AccountBalances(debtIqd = runningDebt, advanceIqd = runningAdvance, loanIqd = runningLoan),
            updatedEntries
        )
    }

    /**
     * Helper to compute derived balances for an account object from eligible transactions.
     */
    fun deriveAccountBalance(
        account: com.example.core.model.LocalAccount,
        transactions: List<com.example.core.model.LocalLedgerEntry>,
        onUnrecognizedType: ((com.example.core.model.LocalLedgerEntry, String) -> Unit)? = null
    ): AccountBalances {
        val isSnapshot = account.stateSource != null
        val (balances, _) = reconstructCurrentPosition(
            openingDebt = account.openingDebtIqd,
            openingAdvance = account.openingAdvanceIqd,
            openingLoan = account.openingLoanIqd,
            transactions = transactions,
            isSnapshotBaseline = isSnapshot,
            onUnrecognizedType = onUnrecognizedType
        )
        return balances
    }
}
