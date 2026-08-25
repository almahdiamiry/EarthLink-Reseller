# Bolt's Journal - Critical Performance Learnings

## 2026-03-29 - Ledger Type Normalization Double-Invocations and Set Allocations
**Learning:** During current position balance reconstruction (`BalanceCalculator.reconstructCurrentPosition`), `TransactionTypeNormalizer.isRecognizedType` and `normalizeTransactionType` were both called sequentially per transaction in a loop. `isRecognizedType` instantiated `setOf("took", "gave", "renewal", "note")` on every call and normalized the type internally, resulting in duplicate string normalization and `Set` object creation for every transaction.
**Action:** Always compute `normalizeTransactionType` once per loop iteration, reuse the resulting `canonicalType` for recognition validation, and store static lookup sets as private class-level constants to avoid GC overhead during balance derivation.
