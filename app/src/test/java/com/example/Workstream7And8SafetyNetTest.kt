package com.example

import com.example.core.database.AppDatabase
import com.example.core.ledger.BalanceCalculator
import com.example.core.ledger.TransactionTypeNormalizer
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.SyncOutbox
import com.example.core.sync.SubscriberMatcher
import com.example.core.sync.UtowerDebtResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Workstream7And8SafetyNetTest {

    @Test
    fun testTransactionTypeNormalizerSingleSourceOfTruth() {
        assertTrue(TransactionTypeNormalizer.isRecognizedType("took"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("gave"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("renewal"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("ADD"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("PAYMENT"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType(""))
        assertTrue(TransactionTypeNormalizer.isRecognizedType(null))

        assertFalse(TransactionTypeNormalizer.isRecognizedType("INVALID_UNKNOWN_TYPE"))
        assertFalse(TransactionTypeNormalizer.isRecognizedType("FOO_BAR"))

        assertTrue(TransactionTypeNormalizer.SQL_TOOK_RENEWAL_IN_CLAUSE.contains("'took'"))
        assertTrue(TransactionTypeNormalizer.SQL_GAVE_IN_CLAUSE.contains("'gave'"))
    }

    @Test
    fun testBalanceCalculatorSurfacesUnrecognizedTypes() {
        var callbackFired = false
        var capturedRawType: String? = null

        val testTx = LocalLedgerEntry(
            id = "tx_unknown_1",
            accountId = "acc_1",
            amountIqd = 1000.0,
            debtAfterIqd = 1000.0,
            typeRaw = "UNKNOWN_CUSTOM_TYPE",
            occurredAt = 1000000L
        )

        BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 0.0,
            openingAdvance = 0.0,
            openingLoan = 0.0,
            transactions = listOf(testTx),
            onUnrecognizedType = { entry, rawType ->
                callbackFired = true
                capturedRawType = rawType
            }
        )

        assertTrue(callbackFired)
        assertEquals("UNKNOWN_CUSTOM_TYPE", capturedRawType)
    }

    @Test
    fun testUtowerDebtResolverPriority3AnchorsOnExplicitDebt() {
        val account = LocalAccount(
            id = "acc_utower_1",
            displayName = "Test User",
            openingDebtIqd = 50000.0,
            debtIqd = 50000.0
        )

        val tx = LocalLedgerEntry(
            id = "tx_1",
            accountId = "acc_utower_1",
            amountIqd = 10000.0,
            debtAfterIqd = 60000.0,
            typeRaw = "took",
            occurredAt = 200000L
        )

        // Without explicitSourceDebt, openingDebt is 50000.0 + 10000.0 = 60000.0
        val resolvedWithExplicit = UtowerDebtResolver.resolveDebtForAccount(
            account = account,
            postResetTxs = listOf(tx),
            explicitSourceDebt = 25000.0
        )

        // Anchoring on explicitSourceDebt (25000.0) + took 10000.0 = 35000.0
        assertEquals(35000.0, resolvedWithExplicit, 0.01)
    }

    @Test
    fun testUtowerDebtResolverAppliesSubsequentTransactionsAfterSnapshot() {
        val account = LocalAccount(
            id = "acc_utower_2",
            displayName = "Snapshot User",
            debtIqd = 0.0
        )

        // Tx1 has explicit snapshot debt
        val txSnapshot = LocalLedgerEntry(
            id = "tx_snap",
            accountId = "acc_utower_2",
            amountIqd = 35000.0,
            debtAfterIqd = 35000.0,
            typeRaw = "renewal",
            occurredAt = 100000L,
            rawJson = """{"debt_after": 35000}"""
        )

        // Tx2 occurs after Tx1 and is a payment without explicit debtAfter in rawJson
        val txPayment = LocalLedgerEntry(
            id = "tx_pay",
            accountId = "acc_utower_2",
            amountIqd = 20000.0,
            debtAfterIqd = 0.0,
            typeRaw = "gave",
            occurredAt = 200000L,
            rawJson = """{"note": "payment"}"""
        )

        val resolved = UtowerDebtResolver.resolveDebtForAccount(
            account = account,
            postResetTxs = listOf(txSnapshot, txPayment)
        )

        // 35,000 snapshot debt - 20,000 payment = 15,000 IQD final debt
        assertEquals(15000.0, resolved, 0.01)
    }

    @Test
    fun testSubscriberMatcherMergesOnFieldChangedReImport() {
        val existingAccount = LocalAccount(
            id = "acc_uuid_123",
            sourceExternalId = "ext_1001",
            earthlinkUsername = "user_alpha",
            phone1 = "07700000000",
            displayName = "Alpha User"
        )

        // Re-import with corrected phone number (07711111111) but matching username and sourceExternalId
        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(existingAccount),
            extId = "ext_1001",
            username = "user_alpha",
            phone = "07711111111",
            name = "Alpha User"
        )

        assertNotNull(matched)
        assertEquals("acc_uuid_123", matched?.id)
    }
}
