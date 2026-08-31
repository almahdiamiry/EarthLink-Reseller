package com.example.core.sync

import com.example.core.ledger.TransactionTypeNormalizer
import com.example.core.model.LocalLedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfStatementGeneratorTest {

    @Test
    fun testTransactionTypeNormalizationForPdfTotals() {
        val transactions = listOf(
            LocalLedgerEntry(
                id = "tx1",
                accountId = "acc1",
                typeRaw = "GAVE", // Uppercase raw
                amountIqd = 50000.0,
                debtAfterIqd = 0.0
            ),
            LocalLedgerEntry(
                id = "tx2",
                accountId = "acc1",
                typeRaw = "payment", // Lowercase raw
                amountIqd = 25000.0,
                debtAfterIqd = 0.0
            ),
            LocalLedgerEntry(
                id = "tx3",
                accountId = "acc1",
                typeRaw = "DEPOSIT", // Alternative raw
                amountIqd = 10000.0,
                debtAfterIqd = 0.0
            ),
            LocalLedgerEntry(
                id = "tx4",
                accountId = "acc1",
                typeRaw = "TOOK", // Uppercase raw
                amountIqd = 40000.0,
                debtAfterIqd = 40000.0
            ),
            LocalLedgerEntry(
                id = "tx5",
                accountId = "acc1",
                typeRaw = "RENEWAL", // Uppercase raw
                amountIqd = 45000.0,
                debtAfterIqd = 85000.0
            ),
            LocalLedgerEntry(
                id = "tx6",
                accountId = "acc1",
                typeRaw = "NOTE", // Non-financial note
                amountIqd = 0.0,
                debtAfterIqd = 85000.0
            )
        )

        val totalPayments = transactions.filter {
            TransactionTypeNormalizer.normalizeTransactionType(it.typeRaw) == "gave"
        }.sumOf { it.amountIqd }

        val totalCharges = transactions.filter {
            val norm = TransactionTypeNormalizer.normalizeTransactionType(it.typeRaw)
            norm == "took" || norm == "renewal"
        }.sumOf { it.amountIqd }

        assertEquals(85000.0, totalPayments, 0.001)
        assertEquals(85000.0, totalCharges, 0.001)
    }
}
