package com.example.core.ledger

/**
 * The "Balance After Renewal" computation, extracted so it can be reasoned about without a device.
 *
 * ## Why this is not a one-liner in the Composable
 *
 * The row this feeds displayed a fabricated `0 IQD` whenever the gateway balance could not be read.
 * `resellerBalance` is Compose state and `balanceAfter` was a `val` local inside a `@Composable`, so
 * no JVM test could observe either of them. The only thing guarding it was a tripwire that pinned the
 * TEXT of the line, which proves spelling and not behaviour — it would stay green if the expression
 * were extracted into a helper that then ignored the null.
 *
 * This object is that helper, and the difference it makes is that the failure branch is now a
 * distinct, named, testable case rather than an absence.
 */
object BalanceAfterRenewal {

    /**
     * A known figure, or an explicitly UNKNOWN one.
     *
     * A sealed type rather than `Double?` on purpose. `null` and `0.0` are both "no money", and the
     * bug this type exists to prevent is precisely that a caller cannot tell them apart and renders
     * `0 IQD` where the honest answer is "I do not know". Making UNKNOWN a value the compiler
     * requires you to handle means no call site can quietly collapse it into a number.
     */
    sealed interface Result {
        /** [amount] is the post-renewal balance in IQD. May legitimately be negative or zero when the
         *  balance genuinely is. */
        data class Known(val amount: Double) : Result

        /** The reseller balance could not be read. NEVER a number — not zero, not a guess. */
        data object Unknown : Result
    }

    /**
     * @param resellerBalance the reseller's current balance in IQD, or `null` when the gateway
     *        balance could not be read. `null` is the failure signal, and it is the ONLY failure
     *        signal: this function never invents a figure.
     * @param packageCost the renewal cost in IQD.
     * @return [Result.Known] when [resellerBalance] was readable, [Result.Unknown] otherwise.
     *
     * No Compose, no Android, no I/O, no clock — so it is directly callable from a JVM test, which is
     * the whole reason it exists.
     */
    fun compute(resellerBalance: Double?, packageCost: Double): Result =
        if (resellerBalance == null) Result.Unknown
        else Result.Known(resellerBalance - packageCost)
}