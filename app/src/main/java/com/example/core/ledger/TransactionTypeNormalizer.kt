package com.example.core.ledger

object TransactionTypeNormalizer {
    val RENEWAL_TYPES = setOf("ADD", "RENEWAL", "RENEW", "SUB_RENEW", "SUB_RENEWAL", "RENEWAL_PAYMENT", "DEBT_RENEW")
    val TOOK_TYPES = setOf("DEBT_ADD", "TOOK", "DEBT", "DEBT_ADDED")
    val GAVE_TYPES = setOf("GAVE", "PAYMENT", "DEPOSIT", "PAY")
    val NOTE_TYPES = setOf("NOTE")

    val ALL_TOOK_RENEWAL_TYPES_LOWERCASE: Set<String> = (RENEWAL_TYPES + TOOK_TYPES).map { it.lowercase() }.toSet()
    val ALL_GAVE_TYPES_LOWERCASE: Set<String> = GAVE_TYPES.map { it.lowercase() }.toSet()

    val SQL_TOOK_RENEWAL_IN_CLAUSE: String = ALL_TOOK_RENEWAL_TYPES_LOWERCASE.joinToString(", ") { "'$it'" }
    val SQL_GAVE_IN_CLAUSE: String = ALL_GAVE_TYPES_LOWERCASE.joinToString(", ") { "'$it'" }

    /**
     * Maps raw remote/uTower transaction types to canonical local transaction types
     * recognized by BalanceCalculator ("took", "gave", "renewal", "note").
     */
    fun normalizeTransactionType(rawType: String?): String {
        if (rawType.isNullOrBlank()) return "note"
        val trimmedUpper = rawType.trim().uppercase()
        return when {
            trimmedUpper in RENEWAL_TYPES -> "renewal"
            trimmedUpper in TOOK_TYPES -> "took"
            trimmedUpper in GAVE_TYPES -> "gave"
            trimmedUpper in NOTE_TYPES -> "note"
            else -> rawType.trim().lowercase()
        }
    }

    fun normalize(rawType: String?): String = normalizeTransactionType(rawType)

    val RECOGNIZED_CANONICAL_TYPES = setOf("took", "gave", "renewal", "note")

    fun isRecognizedCanonicalType(canonicalType: String): Boolean =
        canonicalType in RECOGNIZED_CANONICAL_TYPES

    /**
     * Returns true if [rawType] resolves to a known canonical type ("took", "gave", "renewal", "note").
     */
    fun isRecognizedType(rawType: String?): Boolean {
        if (rawType.isNullOrBlank()) return true
        return isRecognizedCanonicalType(normalizeTransactionType(rawType))
    }
}
