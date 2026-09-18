package com.example

import com.example.core.ledger.TransactionTypeNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTypeNormalizerTest {

    @Test
    fun `test canonical types fast path`() {
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("renewal"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("took"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("gave"))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("note"))
    }

    @Test
    fun `test common raw uppercase fast path`() {
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("RENEWAL"))
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType("ADD"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("TOOK"))
        assertEquals("took", TransactionTypeNormalizer.normalizeTransactionType("DEBT"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("GAVE"))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType("PAYMENT"))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType("NOTE"))
    }

    @Test
    fun `test untrimmed and custom lower fallback`() {
        assertEquals("renewal", TransactionTypeNormalizer.normalizeTransactionType(" renewal "))
        assertEquals("gave", TransactionTypeNormalizer.normalizeTransactionType(" payment "))
        assertEquals("custom_type", TransactionTypeNormalizer.normalizeTransactionType("CUSTOM_TYPE"))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType(null))
        assertEquals("note", TransactionTypeNormalizer.normalizeTransactionType(""))
    }

    @Test
    fun `test isRecognizedType`() {
        assertTrue(TransactionTypeNormalizer.isRecognizedType("renewal"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType("PAYMENT"))
        assertTrue(TransactionTypeNormalizer.isRecognizedType(null))
    }
}
