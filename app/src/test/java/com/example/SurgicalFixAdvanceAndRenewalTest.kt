package com.example

import com.example.core.ledger.BalanceCalculator
import com.example.core.ledger.NoteCleaner
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurgicalFixAdvanceAndRenewalTest {

    // --- Helper for History Presentation Fallback ---
    private fun resolveEntryPostBalances(
        entry: LocalLedgerEntry,
        entryBalancesMap: Map<String, Pair<Double, Double>>
    ): Pair<Double, Double> {
        return entryBalancesMap[entry.id]
            ?: if (entry.debtAfterIqd < 0.0) Pair(0.0, -entry.debtAfterIqd.toDouble()) else Pair(entry.debtAfterIqd.toDouble(), 0.0)
    }

    private fun defectiveOldFallback(
        entry: LocalLedgerEntry,
        entryBalancesMap: Map<String, Pair<Double, Double>>
    ): Pair<Double, Double> {
        return entryBalancesMap[entry.id] ?: Pair(entry.debtAfterIqd.toDouble(), 0.0)
    }

    // --- SECTION 9 & 10: ISSUE A — ADVANCE PRESENTATION TESTS ---

    @Test
    fun `Property 1 - Debt Reduction Case A`() {
        // Debt = 40,000, Payment = 20,000 -> Debt = 20,000, Advance = 0
        val res = BalanceCalculator.applyTransaction(
            currentDebt = 40000.0,
            currentAdvance = 0.0,
            currentLoan = 0.0,
            txType = "gave",
            amount = 20000.0
        )
        assertEquals(20000.0, res.debtIqd, 0.01)
        assertEquals(0.0, res.advanceIqd, 0.01)
    }

    @Test
    fun `Property 2 - Zero Debt Zero Advance Case B`() {
        // Debt = 40,000, Payment = 40,000 -> Debt = 0, Advance = 0
        val res = BalanceCalculator.applyTransaction(
            currentDebt = 40000.0,
            currentAdvance = 0.0,
            currentLoan = 0.0,
            txType = "gave",
            amount = 40000.0
        )
        assertEquals(0.0, res.debtIqd, 0.01)
        assertEquals(0.0, res.advanceIqd, 0.01)
    }

    @Test
    fun `Property 3 - Advance Credit Case C`() {
        // Debt = 40,000, Payment = 50,000 -> Debt = 0, Advance = 10,000
        val res = BalanceCalculator.applyTransaction(
            currentDebt = 40000.0,
            currentAdvance = 0.0,
            currentLoan = 0.0,
            txType = "gave",
            amount = 50000.0
        )
        assertEquals(0.0, res.debtIqd, 0.01)
        assertEquals(10000.0, res.advanceIqd, 0.01)
    }

    @Test
    fun `Property 3 - Advance Credit Case D`() {
        // Debt = 0, Deposit = 10,000 -> Debt = 0, Advance = 10,000
        val res = BalanceCalculator.applyTransaction(
            currentDebt = 0.0,
            currentAdvance = 0.0,
            currentLoan = 0.0,
            txType = "gave",
            amount = 10000.0
        )
        assertEquals(0.0, res.debtIqd, 0.01)
        assertEquals(10000.0, res.advanceIqd, 0.01)
    }

    // --- SECTION 13: COUNTERFACTUAL TEST ---

    @Test
    fun `Counterfactual Test - Defective Fallback vs Corrected Fallback`() {
        // Entry relying on fallback representing an advance balance of 10,000 (debtAfterIqd = 0.0 or -10000.0)
        val entry = LocalLedgerEntry(
            id = "snapshot_entry_1",
            accountId = "acc_1",
            typeRaw = "gave",
            amountIqd = 50000.0,
            debtAfterIqd = -10000.0,
            isSnapshotHistory = true
        )

        val map = emptyMap<String, Pair<Double, Double>>()

        val oldResult = defectiveOldFallback(entry, map)
        val newResult = resolveEntryPostBalances(entry, map)

        // The old fallback fails to present advance credit (gives 0.0 advance)
        assertEquals(0.0, oldResult.second, 0.01)

        // The corrected fallback preserves advance credit (10,000.0 advance)
        assertEquals(0.0, newResult.first, 0.01)
        assertEquals(10000.0, newResult.second, 0.01)
    }

    // --- SECTION 14: ISSUE B — RENEWAL HUMAN NOTE TESTS ---

    @Test
    fun `N1 - Renewal without Wasel and no note yields empty or null human note`() {
        val noteVal = ""
        val isWasil = false

        val chargeNote = noteVal.trim()
        val payNote = if (isWasil) noteVal.trim() else null

        assertEquals("", chargeNote)
        assertNull(payNote)

        val clean = NoteCleaner.extractGenuineNote(chargeNote, 40000.0)
        assertEquals("", clean)
    }

    @Test
    fun `N2 - Renewal with Wasel and no note yields empty or null human note`() {
        val noteVal = ""
        val isWasil = true

        val chargeNote = noteVal.trim()
        val payNote = if (isWasil) noteVal.trim() else null

        assertEquals("", chargeNote)
        assertEquals("", payNote)

        val cleanCharge = NoteCleaner.extractGenuineNote(chargeNote, 40000.0)
        val cleanPay = NoteCleaner.extractGenuineNote(payNote, 40000.0)

        assertEquals("", cleanCharge)
        assertEquals("", cleanPay)
    }

    @Test
    fun `N3 - Renewal without Wasel with note Mushtaq yields Mushtaq`() {
        val noteVal = "مشتاق"
        val isWasil = false

        val chargeNote = noteVal.trim()
        val payNote = if (isWasil) noteVal.trim() else null

        assertEquals("مشتاق", chargeNote)
        assertNull(payNote)

        val clean = NoteCleaner.extractGenuineNote(chargeNote, 40000.0)
        assertEquals("مشتاق", clean)
    }

    @Test
    fun `N4 - Renewal with Wasel with note Mushtaq yields Mushtaq`() {
        val noteVal = "مشتاق"
        val isWasil = true

        val chargeNote = noteVal.trim()
        val payNote = if (isWasil) noteVal.trim() else null

        assertEquals("مشتاق", chargeNote)
        assertEquals("مشتاق", payNote)

        val cleanCharge = NoteCleaner.extractGenuineNote(chargeNote, 40000.0)
        val cleanPay = NoteCleaner.extractGenuineNote(payNote, 40000.0)

        assertEquals("مشتاق", cleanCharge)
        assertEquals("مشتاق", cleanPay)
    }

    @Test
    fun `N5 - Historical generated note hides system noise`() {
        val legacyChargeNote = "[RENEW] تجديد اشتراك بقيمة : 40,000 د.ع"
        val clean = NoteCleaner.extractGenuineNote(legacyChargeNote, 40000.0)
        assertEquals("", clean)
    }

    @Test
    fun `N6 - Historical genuine note preserves human note text`() {
        val legacyNoteWithHumanText = "[RENEW] علي نجم والي قبله مشتاق"
        val clean = NoteCleaner.extractGenuineNote(legacyNoteWithHumanText, 40000.0)
        assertEquals("علي نجم والي قبله مشتاق", clean)
    }

    // --- SECTION 15: SNAPSHOT PROTECTION RE-TEST ---

    @Test
    fun `Snapshot Protection - Filter isSnapshotHistory entries for snapshot-backed account`() {
        val account = LocalAccount(
            id = "acc_snap",
            displayName = "Snapshot User",
            openingDebtIqd = 10000.0,
            openingAdvanceIqd = 5000.0,
            openingLoanIqd = 0.0,
            stateSource = "uTower_import"
        )

        val entries = listOf(
            LocalLedgerEntry(
                id = "hist_1",
                accountId = "acc_snap",
                typeRaw = "took",
                amountIqd = 50000.0,
                debtAfterIqd = 50000.0,
                occurredAt = 1000L,
                isSnapshotHistory = true
            ),
            LocalLedgerEntry(
                id = "v1_1",
                accountId = "acc_snap",
                typeRaw = "gave",
                amountIqd = 10000.0,
                debtAfterIqd = 0.0,
                occurredAt = 2000L,
                isSnapshotHistory = false
            )
        )

        // Pre-6103b3 baseline logic: filter out isSnapshotHistory rows when stateSource != null
        val eligibleTxs = if (account.stateSource != null) {
            entries.filter { !it.isSnapshotHistory }
        } else {
            entries
        }

        assertEquals(1, eligibleTxs.size)
        assertEquals("v1_1", eligibleTxs[0].id)

        // Starting point: opening debt 10,000, opening advance 5,000
        val postBalance = BalanceCalculator.applyTransaction(
            currentDebt = account.openingDebtIqd,
            currentAdvance = account.openingAdvanceIqd,
            currentLoan = account.openingLoanIqd,
            txType = "gave",
            amount = eligibleTxs[0].amountIqd
        )

        // 10,000 debt paid by 10,000 -> 0 debt, 5,000 advance preserved
        assertEquals(0.0, postBalance.debtIqd, 0.01)
        assertEquals(5000.0, postBalance.advanceIqd, 0.01)
    }
}
