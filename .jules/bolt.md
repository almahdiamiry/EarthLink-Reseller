## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2.2 Fast-path String Literals for Frequent Normalization

**Learning:** Calling `.trim().uppercase()` repeatedly on string parameters inside high-frequency utility methods (such as `TransactionTypeNormalizer.normalizeTransactionType` executed per ledger transaction during list rendering and balance calculation) allocates temporary `String` instances on every call even when inputs are already standard canonical strings.
**Action:** Add an explicit `when` fast-path matching exact canonical and common raw string literals to return constant canonical strings directly, bypassing `.trim().uppercase()` allocations while maintaining 100% fallback behavioral equivalence.
