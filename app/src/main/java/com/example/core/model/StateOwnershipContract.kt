package com.example.core.model

/**
 * StateOwnershipContract (RC-A — Unified State Ownership)
 *
 * Establishes explicit boundaries and contracts across the four system states:
 *
 * 1. Historical State (ماذا حدث في الماضي؟):
 *    - Unchanging recorded source transactions (e.g. uTower operations/history).
 *    - Represented by [LocalLedgerEntry] where [LocalLedgerEntry.isSnapshotHistory] == true.
 *    - Immutable; NEVER re-applied on top of Snapshot State during runtime recalculations.
 *
 * 2. Snapshot State (أين كان الحساب عند نقطة الهجرة/الاسترجاع؟):
 *    - Authoritative opening financial baseline captured at migration or restore time.
 *    - Represented by [LocalAccount.openingDebtIqd], [LocalAccount.openingAdvanceIqd],
 *      [LocalAccount.openingLoanIqd], [LocalAccount.stateSource], [LocalAccount.stateConfidence],
 *      and [LocalAccount.snapshotCapturedAt].
 *
 * 3. Runtime State (حالة التطبيق بعد Snapshot):
 *    - Live account balance ([LocalAccount.debtIqd], [LocalAccount.advanceIqd], [LocalAccount.loanIqd]).
 *    - Mutated ONLY by post-snapshot local user transactions or accepted remote business mutations.
 *    - Formula: Runtime Balance = Snapshot Baseline + Sum(Post-Snapshot Runtime Mutations).
 *
 * 4. Remote State (الحالة المنقولة عبر السحابة / Firebase):
 *    - Faithful serialization of Snapshot State + Runtime State + Historical State.
 *    - Preserves snapshot boundary fields across serialization/deserialization.
 */
object StateOwnershipContract {
    const val SOURCE_UTOWER_CURRENT = "UTOWER_CURRENT_STATE"
    const val SOURCE_UTOWER_RESOLVED = "UTOWER_SNAPSHOT_RESOLVED"
    const val SOURCE_MANUAL_SNAPSHOT = "MANUAL_SNAPSHOT"
    const val SOURCE_BACKUP_RESTORE = "BACKUP_RESTORE"

    const val CONFIDENCE_AUTHORITATIVE = "AUTHORITATIVE"
    const val CONFIDENCE_ESTIMATED = "ESTIMATED"
}

/**
 * First-Class Domain Extensions for [LocalAccount]
 */
val LocalAccount.isSnapshotAccount: Boolean
    get() = !stateSource.isNullOrBlank()

val LocalAccount.isAuthoritativeSnapshot: Boolean
    get() = stateConfidence == StateOwnershipContract.CONFIDENCE_AUTHORITATIVE

val LocalAccount.snapshotDebtBaseline: Double
    get() = if (isSnapshotAccount) openingDebtIqd else 0.0

val LocalAccount.snapshotAdvanceBaseline: Double
    get() = if (isSnapshotAccount) openingAdvanceIqd else 0.0

val LocalAccount.snapshotLoanBaseline: Double
    get() = if (isSnapshotAccount) openingLoanIqd else 0.0

/**
 * First-Class Domain Extensions for [LocalLedgerEntry]
 */
val LocalLedgerEntry.isHistoricalRecord: Boolean
    get() = isSnapshotHistory

val LocalLedgerEntry.isRuntimeMutation: Boolean
    get() = !isSnapshotHistory
