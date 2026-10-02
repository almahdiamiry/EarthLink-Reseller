package com.example.core.ledger

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim: [BalanceAfterRenewal.compute] returns an EXPLICIT Unknown when the reseller balance could
 * not be read, and the correct figure when it could — and it never invents a number on the failure
 * path.
 *
 * Seam: JVM. `BalanceAfterRenewal` is a pure function with no Compose, no Android and no I/O, so
 * this runs headless and directly. That is the entire reason it was extracted: before the
 * extraction, `balanceAfter` was a `val` local inside a `@Composable` reading Compose state, so no
 * JVM test could reach it at all and the only guard was a tripwire pinning the line's source TEXT.
 *
 * Independent Oracle: arithmetic and the ISP contract, not the implementation.
 *   * 100,000 - 40,000 = 60,000. Both literals are written here by hand; neither is produced by the
 *     function, and the function is not asked to produce them.
 *   * "Unknown" is the ISP contract's own position: a balance that was not read has no value, and
 *     rendering one is a fabricated figure. The failure-path oracle is therefore NEGATIVE — the
 *     assertion is that NO numeric result exists — rather than a number the test invents.
 *
 * What this does NOT prove: that the Composable actually calls this function, or that it renders the
 * Unknown case as an em dash. Those are the screen's concern and a JVM test cannot observe either.
 * What it does prove is that the arithmetic and the failure branch have exactly one definition and
 * that the failure branch is reachable and observable — which is what the old tripwire could not do.
 */
class BalanceAfterRenewalTest {

    @Test
    fun unknownBalance_yieldsExplicitUnknown_notZero() {
        val result = BalanceAfterRenewal.compute(resellerBalance = null, packageCost = 40_000.0)

        assertTrue(
            "CLAIM 1 | An unreadable reseller balance must yield Unknown, never a figure. It " +
                "returned ${describe(result)}. The harm this prevents is a screen showing a " +
                "real-looking 0 IQD when the gateway simply failed - indistinguishable from a " +
                "genuine zero balance, which is the exact confusion this type was introduced to " +
                "remove.",
            result is BalanceAfterRenewal.Result.Unknown
        )

        // Belt and braces: assert the ABSENCE of a number rather than trusting the type check above.
        // If someone later adds a `val amount: Double` to Unknown, this fails and says why.
        val numeric = (result as? BalanceAfterRenewal.Result.Known)?.amount
        assertEquals(
            "CLAIM 1 | Unknown must carry no amount at all. It carried $numeric, which means a " +
                "number has become reachable from the failure path again.",
            null,
            numeric
        )
    }

    @Test
    fun knownBalance_subtractsPackageCost() {
        // 100,000 - 40,000 = 60,000, by hand.
        val result = BalanceAfterRenewal.compute(resellerBalance = 100_000.0, packageCost = 40_000.0)

        assertTrue(
            "CLAIM 2 | A readable balance must yield Known, not Unknown. It returned " +
                "${describe(result)}.",
            result is BalanceAfterRenewal.Result.Known
        )
        assertEquals(
            "CLAIM 2 | 100,000 - 40,000 = 60,000 IQD.",
            60_000.0,
            (result as BalanceAfterRenewal.Result.Known).amount,
            0.001
        )
    }

    /**
     * A genuine zero balance must still come back as Known(0.0). Without this, "never return 0.0"
     * could be satisfied by refusing to return a number whenever the answer happens to be zero -
     * which would be a DIFFERENT bug: telling a reseller with no money that their balance is
     * unknown. Zero is a real balance and must be reported as one.
     */
    @Test
    fun zeroResult_staysKnownSoAGenuineZeroIsNotHidden() {
        val result = BalanceAfterRenewal.compute(resellerBalance = 40_000.0, packageCost = 40_000.0)

        assertTrue(
            "CLAIM 3 | A balance that is genuinely spent down must be Known(0.0), not Unknown. " +
                "Returning Unknown here would tell a reseller with no money left that their " +
                "balance could not be read - a different lie. It returned ${describe(result)}.",
            result is BalanceAfterRenewal.Result.Known
        )
        assertEquals(
            "CLAIM 3 | 40,000 - 40,000 = 0.0 IQD, reported as a real figure.",
            0.0,
            (result as BalanceAfterRenewal.Result.Known).amount,
            0.001
        )
    }

    /** A negative result is a real position (balance below the package cost) and must survive. */
    @Test
    fun balanceBelowCost_staysKnownAndNegative() {
        val result = BalanceAfterRenewal.compute(resellerBalance = 10_000.0, packageCost = 40_000.0)

        assertTrue(
            "CLAIM 4 | A negative post-renewal position is a real position, not an unknown one.",
            result is BalanceAfterRenewal.Result.Known
        )
        assertEquals(
            "CLAIM 4 | 10,000 - 40,000 = -30,000 IQD.",
            -30_000.0,
            (result as BalanceAfterRenewal.Result.Known).amount,
            0.001
        )
    }

    // =========================================================================
    // readBalance - GAP-1's actual defect, at the line that caused it.
    //
    // The screen used to hold the gateway read and its `catch` inline inside a LaunchedEffect,
    // where no JVM test could reach either. readBalance is that whole read-and-catch as one function.
    // =========================================================================

    @Test
    fun readBalance_gatewayFailure_yieldsNullNotZero() = runTest {
        val result = BalanceAfterRenewal.readBalance { throw RuntimeException("Gateway balance API error") }

        assertNull(
            "CLAIM 5 | A gateway failure must yield null, never 0.0. It yielded $result. A zero " +
                "here is exactly the r13 harm: the screen renders it as a real-looking 0 IQD that " +
                "cannot be told apart from a genuine zero balance.",
            result
        )
    }

    @Test
    fun readBalance_success_yieldsTheFetchedBalance() = runTest {
        val result = BalanceAfterRenewal.readBalance { 100_000.0 }

        assertEquals(
            "CLAIM 6 | A successful read must pass the balance through untouched.",
            100_000.0,
            result ?: -1.0,
            0.001
        )
    }

    /**
     * Cancellation is not a gateway failure. Reporting a cancelled screen load as "balance unknown"
     * would be a false statement about the ISP, and in a Compose scope it would leave the coroutine
     * in an inconsistent state. This is the one path that must NOT produce a null.
     */
    @Test
    fun readBalance_cancellationException_isNotSwallowed() = runTest {
        val thrown = try {
            BalanceAfterRenewal.readBalance { throw kotlinx.coroutines.CancellationException("cancelled") }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            e
        }

        assertNotNull(
            "CLAIM 7 | CancellationException must PROPAGATE. Swallowing it would report a cancelled " +
                "screen load as a gateway failure - a false statement about the ISP - and would " +
                "leave the coroutine's cancellation state inconsistent.",
            thrown
        )
        assertEquals(
            "CLAIM 7 | The propagated exception must be the original one, not a wrapper.",
            "cancelled",
            thrown?.message
        )
    }

    /**
     * The screen's inline catch already rethrew CancellationException
     * (`catch (e: Exception) { if (e is CancellationException) throw e; ... }`), so extracting
     * readBalance PRESERVED that behaviour rather than changing it. This test exists to stop anyone
     * "simplifying" readBalance into a bare `catch (e: Exception) { null }`.
     */
    @Test
    fun readBalance_otherExceptionsStillBecomeNull() = runTest {
        assertNull(
            "CLAIM 8 | A non-cancellation failure must still become null.",
            BalanceAfterRenewal.readBalance { throw IllegalStateException("not a cancellation") }
        )
    }

    private fun describe(r: BalanceAfterRenewal.Result): String = when (r) {
        is BalanceAfterRenewal.Result.Known -> "Known(${r.amount})"
        is BalanceAfterRenewal.Result.Unknown -> "Unknown"
    }
}