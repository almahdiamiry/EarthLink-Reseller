package com.example.core.ledger

data class AccountBalances(
    val debtIqd: Double,
    val advanceIqd: Double,
    val loanIqd: Double
)

object BalanceCalculator {
    fun applyTransaction(currentDebt: Double, currentAdvance: Double, currentLoan: Double, txType: String, amount: Double): AccountBalances {
        return when (txType.lowercase()) {
            "took", "debt", "debt_added", "renewal", "renew", "sub_renew", "sub_renewal", "debt_renew" -> {
                val advanceUsed = minOf(currentAdvance, amount)
                val debtAdded = amount - advanceUsed
                val newAdvance = currentAdvance - advanceUsed
                val newDebt = currentDebt + debtAdded
                AccountBalances(debtIqd = newDebt, advanceIqd = newAdvance, loanIqd = newDebt)
            }
            "gave", "payment", "deposit", "pay" -> {
                val debtPayment = minOf(currentDebt, amount)
                val advanceAdded = amount - debtPayment
                val newDebt = currentDebt - debtPayment
                val newAdvance = currentAdvance + advanceAdded
                AccountBalances(debtIqd = newDebt, advanceIqd = newAdvance, loanIqd = newDebt)
            }
            else -> AccountBalances(currentDebt, currentAdvance, currentLoan)
        }
    }

    fun revertTransaction(currentDebt: Double, currentAdvance: Double, currentLoan: Double, txType: String, amount: Double): AccountBalances {
        return when (txType.lowercase()) {
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
}
