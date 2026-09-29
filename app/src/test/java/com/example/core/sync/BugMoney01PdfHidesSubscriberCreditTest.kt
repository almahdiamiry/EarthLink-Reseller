package com.example.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * RED regression test for BUG-MONEY-1.
 *
 * Claim: a PDF statement must not misrepresent a subscriber's balance.
 * This app encodes an overpayment/advance as a NEGATIVE `debtAfterIqd`
 * (UserDetailScreenV2.storedFallbackBalance maps < 0 to Pair(debt 0, advance = -x)).
 * PdfStatementGenerator collapsed every value <= 0.0 into the literal "0 (settled)",
 * so a subscriber holding a real credit was shown to the customer as owing nothing
 * and holding nothing.
 *
 * Seam / Environment: JVM — pure string formatting, no Android runtime required.
 *
 * Independent Oracle (from real data, not from production code):
 * `.forensic-bug02/real_data.json` contains 27 ledger rows across 20 real ACTIVE
 * accounts with a negative `debtAfterIqd` (e.g. `gave` 40,000 -> -40,000),
 * 995,000 IQD in total; first example account `Aliabbas@sacx`. For such a row the
 * correct rendering is a credit of 40,000, not zero.
 * Authority: Target Product Contract v0.6 — no known behavior that corrupts account
 * history presented to a customer.
 */
class BugMoney01PdfHidesSubscriberCreditTest {

    @Test
    fun negativeDebtAfter_rendersAsVisibleCredit_notAsZeroSettled() {
        // Real-world shape from real_data.json (Aliabbas@sacx).
        val rendered = PdfStatementGenerator.balanceTextFor(-40000.0)

        assertFalse(
            "A subscriber holding a 40,000 IQD credit is rendered as zero/settled. " +
                "The customer is told the account is clear when the app itself shows " +
                "debt 0 / advance 40,000.",
            rendered.startsWith("0")
        )
        assertEquals(
            "The credit magnitude must be shown to the customer.",
            "-40,000 د.ع (دفعة زائدة)",
            rendered
        )
    }

    @Test
    fun exactlyZeroDebtAfter_stillRendersAsSettled() {
        assertEquals(
            "Zero balance is legitimately settled.",
            "0 د.ع (خالص)",
            PdfStatementGenerator.balanceTextFor(0.0)
        )
    }

    @Test
    fun positiveDebtAfter_rendersAsOutstandingAmount() {
        assertEquals(
            "A positive debt must render as the outstanding amount.",
            "50,000 د.ع",
            PdfStatementGenerator.balanceTextFor(50000.0)
        )
    }
}
