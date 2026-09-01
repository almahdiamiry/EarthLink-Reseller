package com.example.ui.viewmodels

import com.example.core.model.AccountStatementItem
import com.example.core.model.LocalLedgerEntry
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.LocalLedgerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claim: StatementViewModel correctly maps synthetic pending ledger entries to AccountStatementItem,
 * asserting that "gave" (payments/deposits) entries map to depositAmount = amount and withdrawalAmount = 0.0,
 * and that occurredAt uses the entry's actual timestamp rather than the current system time.
 * Seam / Environment: ROBOLECTRIC
 * Independent Oracle: Derived directly from Target Product Contract v0.6 transaction type rules and LocalLedgerEntry.occurredAt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StatementViewModelTest {

    @Test
    fun testLoadStatement_syntheticItemMapping_preservesOccurredAtAndCorrectTypeAmounts() = runBlocking {
        val mockGateway = mock(EarthlinkGateway::class.java)
        val mockLedgerRepo = mock(LocalLedgerRepository::class.java)

        `when`(mockGateway.getAccountStatement(anyInt(), anyInt(), anyString())).thenReturn(emptyList())

        val fixedTimestamp = 1600000000000L // Specific epoch millis timestamp
        val expectedDateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(fixedTimestamp))

        val syntheticGave = LocalLedgerEntry(
            id = "tx1",
            accountId = "acc1",
            typeRaw = "GAVE",
            amountIqd = 50000.0,
            debtAfterIqd = 0.0,
            note = "Payment received",
            occurredAt = fixedTimestamp
        )

        val syntheticTook = LocalLedgerEntry(
            id = "tx2",
            accountId = "acc1",
            typeRaw = "took",
            amountIqd = 25000.0,
            debtAfterIqd = 25000.0,
            note = "Renewal debt",
            occurredAt = fixedTimestamp
        )

        `when`(mockLedgerRepo.getPendingSyntheticHistory()).thenReturn(listOf(syntheticGave, syntheticTook))

        val viewModel = StatementViewModel(mockGateway, mockLedgerRepo)

        // Wait for coroutine to complete loadStatement
        kotlinx.coroutines.delay(200)

        val transactions = viewModel.transactions.value
        assertEquals(2, transactions.size)

        // Verify deposit mapping for "GAVE"
        val depositItem = transactions[0]
        assertEquals("PENDING_GAVE", depositItem.operation)
        assertEquals(50000.0, depositItem.depositAmount ?: 0.0, 0.001)
        assertEquals(0.0, depositItem.withdrawalAmount ?: 0.0, 0.001)
        assertEquals(expectedDateStr, depositItem.occurredAt)
        assertEquals("Payment received", depositItem.note)

        // Verify withdrawal mapping for "took"
        val withdrawalItem = transactions[1]
        assertEquals("PENDING_took", withdrawalItem.operation)
        assertEquals(0.0, withdrawalItem.depositAmount ?: 0.0, 0.001)
        assertEquals(25000.0, withdrawalItem.withdrawalAmount ?: 0.0, 0.001)
        assertEquals(expectedDateStr, withdrawalItem.occurredAt)
        assertEquals("Renewal debt", withdrawalItem.note)
    }
}
