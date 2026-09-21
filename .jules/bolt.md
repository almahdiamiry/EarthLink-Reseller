## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2026-09-21 - Fast-Path String Normalization in TransactionTypeNormalizer

**Learning:** High-frequency normalization utilities like `TransactionTypeNormalizer.normalizeTransactionType` executed repeatedly during ledger recalculations and list rendering create heap allocation pressure if they immediately call `rawType.trim().uppercase()`.
**Action:** Add an exact-match `when` fast-path for standard canonical and common raw string literals to return constant strings directly, bypassing temporary `.trim().uppercase()` string allocations.
