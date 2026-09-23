package com.example.core.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTypeNormalizerTest {

    @Test
    fun normalizeTransactionType_nullOrBlank_returnsNote() {
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType(null))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType(""))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("   "))
    }

    @Test
    fun normalizeTransactionType_canonicalTypes_returnsCanonical() {
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("renewal"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("took"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("gave"))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("note"))
    }

    @Test
    fun normalizeTransactionType_uppercaseCommonRawTypes_returnsCanonical() {
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("RENEWAL"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("ADD"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("RENEW"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("TOOK"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("DEBT"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("GAVE"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("PAYMENT"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("DEPOSIT"))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("NOTE"))
    }

    @Test
    fun normalizeTransactionType_paddedAndMixedCaseTypes_returnsCanonical() {
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("  Renewal  "))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("  Debt_Add  "))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("  Pay  "))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("  Note  "))
    }

    @Test
    fun normalizeTransactionType_unknownTypes_returnsTrimmedLowercase() {
        assertEquals("custom_fee", TransactionTypeNormalizer.normalizeTransactionType("CUSTOM_FEE"))
        assertEquals("adjustment", TransactionTypeNormalizer.normalizeTransactionType("  ADJUSTMENT  "))
    }

    @Test
    fun isRecognizedType_validatesKnownTypes() {
        assertTrue(TransactionTypeNormalizer.isRecognizedType(null))
        assertTrue(TransactionTypeNormalizer.isRecognizedType(""))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("gave"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("PAYMENT"))
        assertFalse(TransactionTypeNormalizer.isRecognizedType("custom_unknown_type"))
    }
}
