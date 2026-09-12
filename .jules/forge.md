## 2026-09-12 - Post-Snapshot Incremental Transaction Reconciliation in UtowerDebtResolver

**Defect:** In `UtowerDebtResolver.resolveDebtForAccount`, Priority 2 located the latest transaction containing an explicit `debtAfter` snapshot, but immediately returned `latestTxWithDebtAfter.debtAfterIqd` without applying subsequent post-snapshot transactions in `postResetTxs` that lacked explicit `debtAfter` metadata. This resulted in post-snapshot payments/additions being ignored during uTower debt reconciliation.
**Learning:** When anchoring debt calculation on an explicit snapshot transaction within a chronologically sorted list, any transactions occurring after the snapshot index must be applied incrementally starting from the snapshot debt using `BalanceCalculator.applyTransaction`.
**Prevention:** Always ensure that snapshot-anchored calculations evaluate the index of the snapshot and process all remaining items in the list.
