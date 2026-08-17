package com.example.core.ledger

object TransactionTypeNormalizer {
    /**
     * Maps raw remote/uTower transaction types to canonical local transaction types
     * recognized by BalanceCalculator ("took", "gave", "renewal").
     */
    fun normalizeTransactionType(rawType: String?): String {
        if (rawType.isNullOrBlank()) return "note"
        return when (rawType.trim().uppercase()) {
            "ADD", "RENEWAL", "RENEW", "SUB_RENEW", "SUB_RENEWAL", "RENEWAL_PAYMENT", "DEBT_RENEW" -> "renewal"
            "DEBT_ADD", "TOOK", "DEBT", "DEBT_ADDED" -> "took"
            "GAVE", "PAYMENT", "DEPOSIT", "PAY" -> "gave"
            else -> rawType.trim().lowercase()
        }
    }
}
