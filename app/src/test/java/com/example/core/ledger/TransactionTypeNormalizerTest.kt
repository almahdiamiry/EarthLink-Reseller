package com.example.core.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTypeNormalizerTest {

    @Test
    fun testNormalizeTransactionType_FastPathExactCanonicalMatches() {
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("renewal"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("took"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("gave"))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("note"))
    }

    @Test
    fun testNormalizeTransactionType_FastPathExactUppercaseRawMatches() {
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("RENEWAL"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("RENEW"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("ADD"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("SUB_RENEW"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("SUB_RENEWAL"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("RENEWAL_PAYMENT"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("DEBT_RENEW"))

        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("TOOK"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("DEBT"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("DEBT_ADD"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("DEBT_ADDED"))

        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("GAVE"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("PAYMENT"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("DEPOSIT"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("PAY"))

        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("NOTE"))
    }

    @Test
    fun testNormalizeTransactionType_FallbackPaddedAndUnusualStrings() {
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("  GAVE  "))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("  took  "))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("  Add  "))
        assertEquals("custom_fee", TransactionTypeNormalizer.normalizeTransactionType("CUSTOM_FEE"))
    }

    @Test
    fun testNormalizeTransactionType_NullAndBlankInputs() {
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType(null))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType(""))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("   "))
    }

    @Test
    fun testIsRecognizedType() {
        assertTrue(TransactionTypeNormalizer.isRecognizedType(null))
        assertTrue(TransactionTypeNormalizer.isRecognizedType(""))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("renewal"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("GAVE"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("DEBT_ADD"))
        assertFalse(TransactionTypeNormalizer.isRecognizedType("UNKNOWN_SPECIAL_TYPE"))
    }
}
